package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.domain.pii.BirthDate;
import com.ga.disclosure.domain.pii.CustomerName;
import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.infra.crypto.CiphertextRejectedException;
import com.ga.disclosure.infra.crypto.LocalFileKeyProvider;
import com.ga.disclosure.infra.persistence.CustomerVaultRepository;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.workflow.customer.Customer;
import com.ga.disclosure.workflow.customer.CustomerNotFoundException;
import com.ga.disclosure.workflow.customer.NewCustomer;
import com.ga.disclosure.workflow.customer.NotificationPurpose;
import com.ga.disclosure.workflow.customer.RekeyReport;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 2 P3: 암호화 왕복, 다른 행·컬럼·키 ID·테넌트로 옮긴 암호문은 복호화 실패(AAD), 키 순환 도중 구·신 키가 함께 복호화되고
 * 순환이 끝나면 구 키 재료가 파기되며 재암호화된 행만 남는다. 키 저장소와 고객 행의 DB 불변식(GD060~GD063, 암호문 형식 CHECK).
 */
class CustomerEncryptionIT {

    private static final PostgresHarness DB = PostgresHarness.get();

    private final CatalogCustomerSetup s = new CatalogCustomerSetup("2026-09-01T00:00:00Z");

    private static NewCustomer customer(String name, String phone, String birth) {
        return new NewCustomer(CustomerName.of(name), phone == null ? null : PhoneNumber.of(phone), birth == null ? null : BirthDate.parse(birth));
    }

    private CustomerRef register(TenantId t, String name, String phone, String birth) {
        return s.customers.register(t, CatalogCustomerSetup.OPERATOR, customer(name, phone, birth));
    }

    private Customer lookup(TenantId t, CustomerRef ref) {
        return s.customers.lookup(t, CatalogCustomerSetup.OPERATOR, ref);
    }

    private static int superuser(String sql, Object... params) {
        try (Connection c = DB.superuserDataSource().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new PostgresHarness.UncheckedSqlException(e);
        }
    }

    private static List<String> rows(TenantId t, String sql) {
        return DB.asApp(t.value(), c -> {
            List<String> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ 왕복

    @Test
    void roundTripKeepsValuesAndStoresOnlyCiphertext() {
        TenantId t = s.freshTenant("ENC");
        CustomerRef ref = register(t, "홍길동", "010-1234-5678", "1980-02-29");
        CustomerRef noPhone = register(t, "성춘향", null, null);
        Customer c = lookup(t, ref);
        assertThat(c.name()).isEqualTo(CustomerName.of("홍길동"));
        assertThat(c.phone()).contains(PhoneNumber.of("01012345678"));
        assertThat(c.birthDate()).contains(BirthDate.parse("19800229"));
        assertThat(BirthDate.matches(c.birthDate().orElseThrow(), "1980-02-29")).isTrue();
        assertThat(c.toString()).doesNotContain("홍길동", "5678", "1980");
        assertThat(lookup(t, noPhone).phone()).isEmpty();
        assertThat(ref.value()).matches("CR-[0-9a-f]{32}");

        // 저장값: 형식 머리 0x01, 평문 바이트 없음, 같은 이름이라도 nonce가 달라 암호문이 다르다
        List<String> hex = rows(t, "SELECT encode(name_enc, 'hex') FROM customer_ref ORDER BY created_at, customer_ref");
        assertThat(hex).allSatisfy(h -> assertThat(h).startsWith("01"));
        String plainHex = java.util.HexFormat.of().formatHex("홍길동".getBytes(StandardCharsets.UTF_8));
        assertThat(rows(t, "SELECT encode(name_enc, 'hex') || encode(coalesce(phone_enc, ''), 'hex') FROM customer_ref"))
                .noneMatch(h -> h.contains(plainHex) || h.contains(java.util.HexFormat.of().formatHex("01012345678".getBytes(StandardCharsets.UTF_8))));
        CustomerRef twin = register(t, "홍길동", null, null);
        assertThat(rows(t, "SELECT count(DISTINCT name_enc) FROM customer_ref WHERE customer_ref IN ('" + ref.value() + "','" + twin.value() + "')"))
                .containsExactly("2");
    }

    @Test
    void phoneIsReleasedOnlyForNotificationAndTheReadIsAudited() {
        TenantId t = s.freshTenant("ENC_PHONE");
        CustomerRef ref = register(t, "홍길동", "010-1234-5678", null);
        CustomerRef noPhone = register(t, "성춘향", null, null);
        assertThat(s.customers.phoneForNotification(t, CatalogCustomerSetup.OPERATOR, ref, NotificationPurpose.REMOTE_LINK, "SESSION-1"))
                .isEqualTo(PhoneNumber.of("01012345678"));
        assertThatThrownBy(() -> s.customers.phoneForNotification(t, CatalogCustomerSetup.OPERATOR, noPhone, NotificationPurpose.REMOTE_LINK,
                "SESSION-2")).isInstanceOf(CustomerNotFoundException.class).hasMessageContaining("no phone number");
        AuditRecord read = s.auditOf(t).stream().filter(r -> r.entry().action() == AuditAction.CUSTOMER_PHONE_READ).findFirst().orElseThrow();
        assertThat(read.entry().targetId()).isEqualTo(ref.value());
        assertThat(read.entry().detail().path("purpose").asString()).isEqualTo("REMOTE_LINK");
        assertThat(read.entry().detail().path("reference").asString()).isEqualTo("SESSION-1");
        assertThat(s.auditOf(t)).extracting(r -> r.entry().action())
                .containsExactly(AuditAction.CUSTOMER_REGISTER, AuditAction.CUSTOMER_REGISTER, AuditAction.CUSTOMER_PHONE_READ);
        assertThat(s.auditOf(t).toString()).doesNotContain("홍길동", "5678");
    }

    // ------------------------------------------------------------------ 이식 공격(AAD)

    @Test
    void ciphertextMovedToAnotherRowFailsToDecrypt() {
        TenantId t = s.freshTenant("ENC_ROW");
        CustomerRef a = register(t, "홍길동", "010-1234-5678", "1980-02-29");
        CustomerRef b = register(t, "성춘향", "010-9999-8888", "1990-01-01");
        superuser("UPDATE customer_ref SET name_enc = (SELECT name_enc FROM customer_ref WHERE tenant_id = ? AND customer_ref = ?) "
                + "WHERE tenant_id = ? AND customer_ref = ?", t.value(), b.value(), t.value(), a.value());
        assertThatThrownBy(() -> lookup(t, a)).isInstanceOf(CiphertextRejectedException.class)
                .hasMessageNotContaining("성춘향").hasMessageNotContaining("홍길동");
        assertThat(lookup(t, b).name()).isEqualTo(CustomerName.of("성춘향"));
    }

    @Test
    void ciphertextMovedToAnotherColumnFailsToDecrypt() {
        TenantId t = s.freshTenant("ENC_COL");
        CustomerRef a = register(t, "01012345678", "010-1234-5678", null);   // 이름과 전화가 같은 바이트여도 컬럼이 다르면 실패
        superuser("UPDATE customer_ref SET phone_enc = name_enc WHERE tenant_id = ? AND customer_ref = ?", t.value(), a.value());
        assertThatThrownBy(() -> lookup(t, a)).isInstanceOf(CiphertextRejectedException.class);
    }

    @Test
    void ciphertextMovedToAnotherTenantFailsToDecrypt() {
        TenantId t1 = s.freshTenant("ENC_T1");
        TenantId t2 = s.freshTenant("ENC_T2");
        CustomerRef a = register(t1, "홍길동", null, null);
        CustomerRef b = register(t2, "성춘향", null, null);
        // 같은 KEK 파일을 쓰는 두 테넌트: 암호문도, 감싼 데이터 키도 다른 테넌트로 옮기면 풀리지 않는다
        superuser("UPDATE customer_ref SET name_enc = (SELECT name_enc FROM customer_ref WHERE tenant_id = ? AND customer_ref = ?) "
                + "WHERE tenant_id = ? AND customer_ref = ?", t1.value(), a.value(), t2.value(), b.value());
        assertThatThrownBy(() -> lookup(t2, b)).isInstanceOf(CiphertextRejectedException.class);
        String k1 = rows(t1, "SELECT enc_key_id FROM customer_ref").getFirst();
        String k2 = rows(t2, "SELECT enc_key_id FROM customer_ref").getFirst();
        byte[] wrapped1 = DB.asApp(t1.value(), c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT wrapped_key FROM customer_data_key")) {
                rs.next();
                return rs.getBytes(1);
            }
        });
        assertThatThrownBy(() -> s.keys.unwrap(t2, k2, "KEK-TEST-1", wrapped1)).isInstanceOf(CiphertextRejectedException.class);
        assertThatThrownBy(() -> s.keys.unwrap(t1, k2, "KEK-TEST-1", wrapped1)).as("key id is bound too")
                .isInstanceOf(CiphertextRejectedException.class);
        assertThat(s.keys.unwrap(t1, k1, "KEK-TEST-1", wrapped1)).hasSize(32);
    }

    // ------------------------------------------------------------------ 최초 DEK 생성 경합 (Phase 2 D3 → Phase 3A 선행 B)

    /** 동시에 시작한 작업을 모두 끝까지 기다리고, 실패는 삼키지 않고 모아 돌려준다. */
    private static List<Throwable> concurrently(int n, java.util.function.IntConsumer work) throws InterruptedException {
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(n);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(n)) {
            for (int i = 0; i < n; i++) {
                int index = i;
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        work.accept(index);
                    } catch (Throwable e) {
                        failures.add(e);
                    }
                });
            }
            ready.await();
            go.countDown();
        }
        return failures;
    }

    /**
     * 새 테넌트의 첫 등록 50건을 동시에 시작한다. 테넌트 DEK는 {@code INSERT … ON CONFLICT DO NOTHING} 후 재조회로 멱등하게
     * 만들어지므로 실패 0, ACTIVE 키 정확히 1개, 50행 모두 그 키, 감사 50행이다(호출자 재시도 없음).
     */
    @Test
    void firstRegistrationsAreIdempotentUnderConcurrency() throws InterruptedException {
        TenantId t = s.freshTenant("ENC_RACE");
        int n = 50;
        List<CustomerRef> refs = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = concurrently(n, i -> refs.add(register(t, "가상고객" + i, null, null)));

        assertThat(failures.stream().map(e -> e.getClass().getSimpleName()).toList()).as("예외 종류만 출력(규칙 6)").isEmpty();
        assertThat(refs).hasSize(n).doesNotHaveDuplicates();
        assertThat(rows(t, "SELECT count(*) FROM customer_ref")).containsExactly(String.valueOf(n));
        List<String> active = rows(t, "SELECT key_id FROM customer_data_key WHERE status = 'ACTIVE'");
        assertThat(active).hasSize(1);
        assertThat(rows(t, "SELECT count(*) FROM customer_data_key")).as("진 쪽의 키는 저장되지 않는다").containsExactly("1");
        assertThat(rows(t, "SELECT DISTINCT enc_key_id FROM customer_ref")).containsExactly(active.getFirst());
        assertThat(s.auditOf(t)).filteredOn(r -> r.entry().action() == AuditAction.CUSTOMER_REGISTER).hasSize(n);
        refs.forEach(ref -> assertThat(lookup(t, ref).name().field()).isNotNull());
    }

    /** 순환과 등록이 겹쳐도 실패 없이 ACTIVE 키는 하나이고, 모든 행이 복호화된다(순환 경로는 ACTIVE를 먼저 RETIRED로 바꾼다). */
    @Test
    void registrationsDuringRotationNeverFail() throws InterruptedException {
        TenantId t = s.freshTenant("ENC_RACE_ROT");
        register(t, "가상고객", null, null);
        int n = 20;
        List<CustomerRef> refs = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = concurrently(n + 1, i -> {
            if (i == n) {
                s.in(t, () -> s.vault.rotate(s.clock.instant()));
            } else {
                refs.add(register(t, "가상고객" + i, null, null));
            }
        });

        assertThat(failures.stream().map(e -> e.getClass().getSimpleName()).toList()).as("예외 종류만 출력(규칙 6)").isEmpty();
        assertThat(rows(t, "SELECT count(*) FROM customer_data_key WHERE status = 'ACTIVE'")).containsExactly("1");
        assertThat(rows(t, "SELECT count(*) FROM customer_ref")).containsExactly(String.valueOf(n + 1));
        refs.forEach(ref -> assertThat(lookup(t, ref).name().field()).isNotNull());
    }

    // ------------------------------------------------------------------ 키 순환

    @Test
    void rotationDecryptsOldAndNewKeysMeanwhileAndLeavesOnlyReencryptedRows() {
        TenantId t = s.freshTenant("ENC_ROT");
        List<CustomerRef> refs = List.of(register(t, "고객일", "010-1111-1111", "1970-01-01"), register(t, "고객이", null, "1971-02-02"),
                register(t, "고객삼", "010-3333-3333", null));
        String k1 = rows(t, "SELECT DISTINCT enc_key_id FROM customer_ref").getFirst();

        // 순환 도중: 새 키 1개 행, 구 키 2개 행 — 모두 복호화된다
        CustomerVaultRepository vault = s.vault;
        String k2 = s.in(t, () -> vault.rotate(s.clock.instant())).activeKeyId();
        assertThat(s.in(t, () -> vault.reencryptBatch(1))).isEqualTo(1);
        assertThat(rows(t, "SELECT enc_key_id FROM customer_ref ORDER BY enc_key_id")).containsExactlyInAnyOrder(k1, k1, k2);
        refs.forEach(ref -> assertThat(lookup(t, ref).name().field()).isNotNull());
        assertThat(lookup(t, refs.get(1)).birthDate()).contains(BirthDate.parse("1971-02-02"));
        // 구 키를 쓰는 행이 남아 있으면 파기되지 않는다(서비스도, DB도)
        assertThat(s.in(t, () -> vault.destroyUnusedRetiredKeys(s.clock.instant()))).isEmpty();
        assertThatThrownBy(() -> asAppInTenant(t, "UPDATE customer_data_key SET status = 'DESTROYED', wrapped_key = NULL, "
                + "destroyed_at = now() WHERE tenant_id = ? AND key_id = ?", t.value(), k1))
                .isInstanceOf(PostgresHarness.UncheckedSqlException.class)
                .satisfies(e -> assertThat(((PostgresHarness.UncheckedSqlException) e).sqlState()).isEqualTo("GD061"));

        // 서비스로 순환 완료: 다시 순환(K3)하고 전 행 재암호화 → K1·K2 파기
        RekeyReport report = s.rekey.rekey(t, CatalogCustomerSetup.OPERATOR, 2);
        assertThat(report.retiredKeyId()).contains(k2);
        assertThat(report.reencrypted()).isEqualTo(3);
        assertThat(report.destroyedKeyIds()).containsExactlyInAnyOrder(k1, k2);
        String k3 = report.activeKeyId();
        assertThat(rows(t, "SELECT DISTINCT enc_key_id FROM customer_ref")).containsExactly(k3);
        assertThat(rows(t, "SELECT key_id || ':' || status || ':' || (wrapped_key IS NULL) FROM customer_data_key ORDER BY created_at, key_id"))
                .containsExactlyInAnyOrder(k1 + ":DESTROYED:true", k2 + ":DESTROYED:true", k3 + ":ACTIVE:false");
        assertThat(lookup(t, refs.getFirst()).phone()).contains(PhoneNumber.of("01011111111"));
        assertThat(lookup(t, refs.get(2)).name()).isEqualTo(CustomerName.of("고객삼"));

        // 파기된 키로 암호화된 행이 있다고 가정하면(트리거 우회) 복호화는 명시적으로 실패한다
        superuser("UPDATE customer_ref SET enc_key_id = ? WHERE tenant_id = ? AND customer_ref = ?", k1, t.value(), refs.getFirst().value());
        assertThatThrownBy(() -> lookup(t, refs.getFirst())).isInstanceOf(CiphertextRejectedException.class)
                .hasMessageContaining("destroyed");
        assertThat(s.auditOf(t)).extracting(r -> r.entry().action()).contains(AuditAction.CUSTOMER_KEY_ROTATE, AuditAction.CUSTOMER_REKEY,
                AuditAction.CUSTOMER_KEY_DESTROY);
    }

    @Test
    void anotherMasterKeyCannotUnwrapTheDataKeys() {
        TenantId t = s.freshTenant("ENC_KEK");
        CustomerRef ref = register(t, "홍길동", null, null);
        CatalogCustomerSetup otherKek = new CatalogCustomerSetup("2026-09-01T00:00:00Z", CatalogCustomerSetup.newKekFile());
        assertThatThrownBy(() -> otherKek.customers.lookup(t, CatalogCustomerSetup.OPERATOR, ref)).isInstanceOf(CiphertextRejectedException.class);
    }

    @Test
    void keyFileReadableByOthersIsRefused() throws Exception {
        Path file = CatalogCustomerSetup.newKekFile();
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> LocalFileKeyProvider.load(file)).isInstanceOf(IllegalStateException.class).hasMessageContaining("chmod 600");
        assertThatThrownBy(() -> LocalFileKeyProvider.initialize(file, "KEK-X")).as("never overwrite an existing KEK")
                .isInstanceOf(IllegalStateException.class);
    }

    // ------------------------------------------------------------------ DB 불변식

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "GD062|DELETE FROM customer_data_key WHERE tenant_id = current_setting('app.tenant_id')",
            "GD060|UPDATE customer_data_key SET kek_id = 'KEK-OTHER' WHERE tenant_id = current_setting('app.tenant_id')",
            "GD060|UPDATE customer_data_key SET wrapped_key = decode(repeat('11', 32), 'hex') WHERE tenant_id = current_setting('app.tenant_id')",
            "GD060|UPDATE customer_data_key SET status = 'DESTROYED', wrapped_key = NULL, retired_at = now(), destroyed_at = now() "
                    + "WHERE tenant_id = current_setting('app.tenant_id')",
            "GD064|DELETE FROM customer_ref WHERE tenant_id = current_setting('app.tenant_id')",
            "GD063|UPDATE customer_ref SET customer_ref = 'CR-" + "f" + "0000000000000000000000000000000" + "' WHERE tenant_id = current_setting('app.tenant_id')",
            "23514|UPDATE customer_ref SET name_enc = convert_to('홍길동', 'UTF8') WHERE tenant_id = current_setting('app.tenant_id')",
            "23514|UPDATE customer_ref SET phone_enc = convert_to('01012345678', 'UTF8') WHERE tenant_id = current_setting('app.tenant_id')",
            "23503|UPDATE customer_ref SET enc_key_id = 'DEK-NOPE' WHERE tenant_id = current_setting('app.tenant_id')"})
    void keyStoreAndCustomerRowsAreGuardedByTheDatabase(String caseSpec) {
        String[] parts = caseSpec.split("\\|", 2);
        TenantId t = s.freshTenant("ENC_GUARD");
        register(t, "홍길동", "010-1234-5678", "1980-02-29");
        assertThatThrownBy(() -> DB.asApp(t.value(), c -> {
            try (Statement st = c.createStatement()) {
                return st.executeUpdate(parts[1]);
            }
        })).isInstanceOf(PostgresHarness.UncheckedSqlException.class)
                .satisfies(e -> assertThat(((PostgresHarness.UncheckedSqlException) e).sqlState()).isEqualTo(parts[0]));
    }

    /** 스키마 소유자도 트리거에 묶인다: TRUNCATE(CASCADE 포함)로 키 저장소·고객 행을 한꺼번에 지울 수 없다. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GD062|customer_data_key", "GD064|customer_ref"})
    void ownerCannotTruncateKeyStoreOrCustomers(String caseSpec) {
        String[] parts = caseSpec.split("\\|", 2);
        TenantId t = s.freshTenant("ENC_TRUNC");
        register(t, "홍길동", "010-1234-5678", "1980-02-29");
        assertThat(TriggerAssertions.sqlStateOf(() -> DB.seed(t.value(), c -> SeedData.exec(c, "TRUNCATE " + parts[1] + " CASCADE"))))
                .isEqualTo(parts[0]);
    }

    private static int asAppInTenant(TenantId t, String sql, Object... params) {
        return DB.asAppCommitting(t.value(), c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                return ps.executeUpdate();
            }
        });
    }
}
