package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.verify.FindingCode;
import com.ga.disclosure.audit.verify.VerifyReport;
import com.ga.disclosure.audit.verify.VerifySchemas;
import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.infra.crypto.CiphertextRejectedException;
import com.ga.disclosure.infra.crypto.TenantKeyProvider;
import com.ga.disclosure.infra.persistence.AnchorRepository;
import com.ga.disclosure.infra.persistence.KekRewrapRepository;
import com.ga.disclosure.infra.persistence.SealChainRepository;
import com.ga.disclosure.infra.persistence.TenantKekRepository;
import com.ga.disclosure.infra.secret.FileSecretSource;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.infra.testing.TestKeks;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.artifact.ArtifactUnreadableException;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.kek.KekRewrapStore;
import com.ga.disclosure.workflow.kek.TenantKekService;
import com.ga.disclosure.workflow.kek.TenantKekService.Outcome;
import com.ga.disclosure.workflow.kek.TenantKekStore;
import com.ga.disclosure.workflow.secret.SecretName;
import com.ga.disclosure.workflow.verify.TenantVerifier;
import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 8 테넌트 KEK(8 계획 승인 Q2, 1b): 레지스트리 가드(GD140), 재래핑 함수의 전제(GD141)와 불변 트리거의 재래핑 분기, 회전(v1 → v2 — 행마다 감사, 두 번째
 * 실행 0건, 파기된 키 제외, 다른 테넌트 불변, 옛 KEK 바이트 없이 전부 열림), 전역 시절 KEK ID로 감싼 키는 풀지 않고 {@code verify tenant}가
 * {@code KEK_UNREGISTERED}로 보고, 재래핑 함수로 검증할 수 없는 바이트를 넣으면 {@code verify tenant}가 {@code KEK_UNWRAP_FAILED}로 잡는다(보안 검토 반영).
 * 전역 → 테넌트 이행 자체의 시험(이중 읽기·옮기기·주입 P8-1·P8-2)은 1a 커밋 {@code 270e18d}의 이 클래스다 — 1b는 그 경로를 지웠다.
 */
class TenantKekIT {

    private static final String OPERATOR = "ops-kek@test";

    private final JobSetup j = new JobSetup();
    private final WorkflowSetup w = j.s.w;
    private final PostgresHarness db = w.db;
    private final TenantKekRepository registry = new TenantKekRepository(w.gateway);

    @AfterEach
    void close() {
        j.close();
    }

    private Caller operator() {
        return Caller.cli(w.tenant, OPERATOR);
    }

    private TenantKekService service() {
        return new TenantKekService(registry, new KekRewrapRepository(w.gateway), w.keys, w.audit, w.tx, Callers.authz(w.clock), w.clock);
    }

    private TenantVerifier verifier() {
        return new TenantVerifier(w.audit, new SealChainRepository(w.gateway), new AnchorRepository(w.gateway), j.s.records, j.s.cipher, j.s.bucket,
                new RuleResolver(w.rules), w.flags, w.tx, w.clock, Callers.authz(w.clock), new KekRewrapRepository(w.gateway), w.keys);
    }

    private List<String> keks(String sql) {
        return db.asApp(w.tenant.value(), c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        });
    }

    private List<String> allWrappingKeks() {
        List<String> all = new ArrayList<>(keks("SELECT kek_key_id FROM document_key WHERE wrapped_dek IS NOT NULL"));
        all.addAll(keks("SELECT kek_id FROM customer_data_key WHERE status <> 'DESTROYED'"));
        all.addAll(keks("SELECT report_kek_id FROM async_job WHERE report_key_wrapped IS NOT NULL"));
        return all;
    }

    private byte[] pdf(DisclosureId id) {
        ArtifactService.View view = j.s.artifacts.view(Callers.of(w.tenant, SealSetup.MANAGER), id, ArtifactKind.PDF);
        assertThat(view).isInstanceOf(ArtifactService.View.Granted.class);
        return ((ArtifactService.View.Granted) view).plaintext();
    }

    private String v2() {
        String v2 = w.tenant.value() + "-KEK-2";
        TestKeks.shared().ensure(w.tenant, v2);
        return v2;
    }

    private void report(String json) {
        j.runner().run(List.of(j.operator()), JobKind.RECONCILE, JobSetup.params(), JobSetup.fixed(json));
    }

    // ------------------------------------------------------------------ 회전: 테넌트 KEK v1 → v2 (G5 — 재래핑 배치의 두 번째 실행)

    @Test
    void rotatingTheTenantKekRewrapsEverythingAndTheOldKekIsNoLongerNeeded() {
        DisclosureId sealed = j.s.sealReasoned().id();
        report("{\"v\":1}");
        String v1 = TestKeks.firstKekId(w.tenant.value());
        assertThat(allWrappingKeks()).containsOnly(v1);

        String v2 = v2();
        TenantKekService keks = service();
        assertThat(keks.register(operator(), v2)).isTrue();
        assertThat(keks.register(operator(), v2)).as("registering the current KEK again is a NOOP").isFalse();
        assertThat(w.tx.inTenant(w.tenant, registry::all)).extracting(TenantKekIT::view).containsExactlyInAnyOrder(v1 + ":RETIRED", v2 + ":CURRENT");
        assertThat(j.s.audit()).filteredOn(r -> r.entry().action() == AuditAction.KEK_REGISTERED).singleElement()
                .satisfies(r -> assertThat(r.entry().detail().get("retired").asString()).isEqualTo(v1));
        DisclosureId after = j.s.sealReasoned().id();
        assertThat(keks("SELECT kek_key_id FROM document_key WHERE disclosure_id = '" + after + "'")).as("new seals use the new KEK").containsExactly(v2);

        TenantKekService.Report dry = keks.rewrap(operator(), false, UUID.randomUUID());
        assertThat(dry.count(Outcome.PENDING)).as("document, customer and report keys under v1").isGreaterThanOrEqualTo(3);
        assertThat(allWrappingKeks()).as("a dry run writes nothing").contains(v1);
        assertThat(j.s.audit()).noneMatch(r -> r.entry().action() == AuditAction.KEK_REWRAPPED);

        UUID job = UUID.randomUUID();
        TenantKekService.Report applied = keks.rewrap(operator(), true, job);
        assertThat(applied.count(Outcome.REWRAPPED)).isEqualTo(dry.count(Outcome.PENDING));
        assertThat(applied.count(Outcome.FAILED)).isZero();
        assertThat(VerifySchemas.kekRewrapReport(Canonicalizer.parseStrict(new String(Canonicalizer.canonicalize(applied.toJson()))))).isEmpty();
        assertThat(allWrappingKeks()).containsOnly(v2);
        assertThat(j.s.audit()).filteredOn(r -> r.entry().action() == AuditAction.KEK_REWRAPPED).as("one audit row per rewrapped key")
                .hasSize((int) applied.count(Outcome.REWRAPPED)).allSatisfy(r -> {
                    assertThat(r.entry().detail().get("fromKekId").asString()).isEqualTo(v1);
                    assertThat(r.entry().detail().get("toKekId").asString()).isEqualTo(v2);
                    assertThat(r.entry().detail().get("jobId").asString()).isEqualTo(job.toString());
                    assertThat(r.entry().actorRole()).isEqualTo("OPERATOR");
                });
        assertThat(keks.rewrap(operator(), true, UUID.randomUUID()).items()).as("the second run has nothing to do").isEmpty();

        // 옛 KEK 바이트가 없는 출처로도 전부 열린다 — 문서·verify(고객·보고서 키 포함 살아 있는 키 전부 풀림)
        TestKeks onlyV2 = TestKeks.fresh();
        copy(TestKeks.shared(), onlyV2, v2);
        w.keys.use(new TenantKeyProvider(onlyV2.secrets(), t -> w.tx.inTenant(t, registry::current)));
        assertThat(pdf(sealed)).startsWith("%PDF".getBytes());
        VerifyReport verify = verifier().run(operator(), null);
        assertThat(verify.findings()).extracting(VerifyReport.Finding::code).doesNotContain(FindingCode.KEK_UNREGISTERED, FindingCode.KEK_UNWRAP_FAILED,
                FindingCode.OBJECT_HASH_MISMATCH);
    }

    @Test
    void aShreddedDocumentKeyIsNotRewrapped() {
        DisclosureId sealed = j.s.sealReasoned().id();
        // 파기 묘비: 정의자 롤 + 표식(파기 함수가 하는 것과 같은 분기 — 트리거를 끄지 않는다)
        db.seed(w.tenant.value(), c -> SeedData.exec(c, "SET ROLE disclosure_destroy_definer; DO $$ BEGIN "
                + "PERFORM set_config('ga.destroy', 'key:" + sealed + "', true); "
                + "UPDATE document_key SET wrapped_dek = NULL, shredded_at = now(), shredded_by = 'it' WHERE disclosure_id = '" + sealed + "'; "
                + "END $$; RESET ROLE"));
        service().register(operator(), v2());
        TenantKekService.Report r = service().rewrap(operator(), true, UUID.randomUUID());
        assertThat(r.items()).noneMatch(i -> i.target() == KekRewrapStore.Target.DOCUMENT_KEY);
        assertThat(keks("SELECT kek_key_id || ':' || (wrapped_dek IS NULL) FROM document_key"))
                .containsExactly(TestKeks.firstKekId(w.tenant.value()) + ":true");
    }

    @Test
    void anotherTenantsKeysAreUntouched() {
        j.s.sealReasoned();
        JobSetup other = new JobSetup();
        try {
            other.s.sealReasoned();
            service().register(operator(), v2());
            service().rewrap(operator(), true, UUID.randomUUID());
            String otherKek = other.s.w.db.asApp(other.s.w.tenant.value(), c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT kek_key_id FROM document_key"); ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            });
            assertThat(otherKek).isEqualTo(TestKeks.firstKekId(other.s.w.tenant.value()));
            assertThatThrownBy(() -> w.keys.unwrap(w.tenant, "X", TestKeks.firstKekId(other.s.w.tenant.value()), new byte[40]))
                    .as("a tenant never uses another tenant's KEK").isInstanceOf(CiphertextRejectedException.class);
        } finally {
            other.close();
        }
    }

    @Test
    void registeringAKekWhoseBytesAreMissingIsRefusedBeforeTheRegistryChanges() {
        String missing = w.tenant.value() + "-KEK-9";
        assertThatThrownBy(() -> service().register(operator(), missing)).isInstanceOf(IllegalStateException.class).hasMessageContaining(missing);
        assertThat(w.tx.inTenant(w.tenant, registry::current)).contains(TestKeks.firstKekId(w.tenant.value()));
    }

    // ------------------------------------------------------------------ 전역 시절 키(1b): 풀지 않고 verify가 드러낸다

    @Test
    void aKeyUnderAGlobalEraKekIdIsNotUnwrappedAndVerifyReportsIt() {
        j.s.sealReasoned();
        db.seed(w.tenant.value(), c -> SeedData.exec(c, """
                INSERT INTO customer_data_key (tenant_id, key_id, kek_id, wrapped_key, status, created_at, retired_at)
                VALUES (?, 'DEK-00000000000000000000000000000001', 'KEK-LOCAL-1', decode('01' || repeat('00', 60), 'hex'), 'RETIRED', now(), now())
                """, w.tenant.value()));
        assertThatThrownBy(() -> w.keys.unwrap(w.tenant, "DEK-00000000000000000000000000000001", "KEK-LOCAL-1", new byte[61]))
                .as("the global-era read path is gone").isInstanceOf(CiphertextRejectedException.class);
        VerifyReport r = verifier().run(operator(), null);
        assertThat(r.findings()).filteredOn(f -> f.code() == FindingCode.KEK_UNREGISTERED).singleElement().satisfies(f -> {
            assertThat(f.where()).containsEntry("target", "CUSTOMER_DATA_KEY").containsEntry("kekId", "KEK-LOCAL-1");
            assertThat(f.detail()).containsEntry("rows", 1L);
        });
        assertThat(r.findings()).extracting(VerifyReport.Finding::code).doesNotContain(FindingCode.KEK_UNWRAP_FAILED);
        assertThat(service().rewrap(operator(), true, UUID.randomUUID()).count(Outcome.FAILED)).as("rewrap reports it and leaves it").isEqualTo(1);
    }

    /**
     * 보안 검토(V21): 재래핑 함수는 새 바이트가 같은 DEK를 감쌌는지 DB에서 확인할 수 없다 — 앱 롤이 검증할 수 없는 바이트를 넣으면 그 문서는 보존기간 중에
     * 읽을 수 없게 된다. 막을 수 없는 이것을 verify tenant가 잡는다(살아 있는 키 전부 풀기 + 산출물 복호화).
     */
    @Test
    void verifyCatchesARewrapWithBytesThatDoNotUnwrap() {
        DisclosureId sealed = j.s.sealReasoned().id();
        String v2 = v2();
        service().register(operator(), v2);
        String keyId = keks("SELECT key_id FROM document_key").getFirst();
        db.asAppCommitting(w.tenant.value(), c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT ga_kek_rewrap(?, 'document_key', ?, ?, ?, decode('01' || repeat('ab', 60), 'hex'))")) {
                ps.setString(1, w.tenant.value());
                ps.setString(2, keyId);
                ps.setString(3, TestKeks.firstKekId(w.tenant.value()));
                ps.setString(4, v2);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            }
        });
        assertThatThrownBy(() -> pdf(sealed)).isInstanceOfAny(CiphertextRejectedException.class, ArtifactUnreadableException.class);
        VerifyReport r = verifier().run(operator(), null);
        assertThat(r.findings()).filteredOn(f -> f.code() == FindingCode.KEK_UNWRAP_FAILED).singleElement()
                .satisfies(f -> assertThat(f.where()).containsEntry("target", "DOCUMENT_KEY").containsEntry("rowKey", keyId));
        assertThat(r.matches()).isFalse();
        assertThat(j.s.audit()).as("an integrity finding raises CHAIN_BROKEN").anyMatch(a -> a.entry().action() == AuditAction.FLAG_RAISE);
    }

    // ------------------------------------------------------------------ DB 가드

    /** 앱 롤에는 레지스트리 삭제·식별 변경과 감싼 키 갱신 권한부터 없다(42501). 권한이 있는 소유자도 트리거에 막힌다(GD140·GD092·GD060·GD121). */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "app|42501|DELETE FROM tenant_kek",
            "app|42501|UPDATE tenant_kek SET registered_by = 'x'",
            "app|42501|UPDATE document_key SET kek_key_id = 'X-KEK-1'",
            "app|GD140|UPDATE tenant_kek SET status = 'CURRENT', retired_at = NULL",
            "app|GD140|INSERT INTO tenant_kek (tenant_id, kek_id, status, registered_at, registered_by, retired_at) "
                    + "SELECT tenant_id, tenant_id || '-KEK-7', 'RETIRED', now(), 'x', now() FROM tenant_kek",
            "app|23505|INSERT INTO tenant_kek (tenant_id, kek_id, status, registered_at, registered_by) "
                    + "SELECT tenant_id, tenant_id || '-KEK-8', 'CURRENT', now(), 'x' FROM tenant_kek",
            "app|23514|INSERT INTO tenant_kek (tenant_id, kek_id, status, registered_at, registered_by) "
                    + "SELECT tenant_id, 'KEK-LOCAL-1', 'CURRENT', now(), 'x' FROM tenant_kek",
            "app|GD060|UPDATE customer_data_key SET kek_id = 'X-KEK-1'",
            "app|GD121|UPDATE async_job SET report_kek_id = 'X-KEK-1' WHERE report_kek_id IS NOT NULL",
            "app|GD141|SELECT ga_kek_rewrap(current_setting('app.tenant_id'), 'document_key', key_id, kek_key_id, kek_key_id, wrapped_dek) FROM document_key",
            "app|GD141|SELECT ga_kek_rewrap(current_setting('app.tenant_id'), 'document_key', key_id, kek_key_id, tenant_id || '-KEK-5', wrapped_dek) "
                    + "FROM document_key",
            "app|GD141|SELECT ga_kek_rewrap('OTHER', 'document_key', key_id, kek_key_id, 'OTHER-KEK-1', wrapped_dek) FROM document_key",
            "app|GD141|SELECT ga_kek_rewrap(current_setting('app.tenant_id'), 'nope', key_id, 'A', tenant_id || '-KEK-1', wrapped_dek) FROM document_key",
            "app|GD141|SELECT ga_kek_rewrap(current_setting('app.tenant_id'), 'document_key', key_id, 'A', tenant_id || '-KEK-1', '\\x01'::bytea) "
                    + "FROM document_key",
            "owner|GD140|DELETE FROM tenant_kek",
            "owner|GD140|UPDATE tenant_kek SET registered_by = 'x'",
            "owner|GD140|TRUNCATE tenant_kek",
            "owner|GD092|UPDATE document_key SET kek_key_id = 'X-KEK-1'"
    })
    void theRegistryAndTheWrappedKeysAreGuardedByTheDatabase(String caseSpec) {
        String[] parts = caseSpec.split("\\|", 3);
        j.s.sealReasoned();
        report("{}");
        String state = parts[0].equals("app")
                ? TriggerAssertions.sqlStateOf(() -> db.asApp(w.tenant.value(), c -> {
                    try (var st = c.createStatement()) {
                        return st.execute(parts[2]);
                    }
                }))
                : TriggerAssertions.sqlStateOf(() -> db.seed(w.tenant.value(), c -> SeedData.exec(c, parts[2])));
        assertThat(state).isEqualTo(parts[1]);
    }

    /** 정의자 롤도 표식 없이는 재래핑 분기를 열 수 없다(파기 분기와 같은 장치). */
    @Test
    void theDefinerWithoutTheMarkerCannotChangeAWrappedKey() {
        j.s.sealReasoned();
        assertThat(TriggerAssertions.sqlStateOf(() -> db.seed(w.tenant.value(), c -> SeedData.exec(c,
                "SET ROLE disclosure_destroy_definer; UPDATE document_key SET kek_key_id = kek_key_id || 'X'; RESET ROLE")))).isEqualTo("GD092");
    }

    /** 레지스트리는 테넌트 격리(RLS): 다른 테넌트의 KEK 행이 보이지 않는다. */
    @Test
    void theRegistryIsTenantIsolated() {
        JobSetup other = new JobSetup();
        try {
            assertThat(w.tx.inTenant(w.tenant, registry::all)).extracting(TenantKekIT::view)
                    .containsExactly(TestKeks.firstKekId(w.tenant.value()) + ":CURRENT");
        } finally {
            other.close();
        }
    }

    private static void copy(TestKeks from, TestKeks to, String kekId) {
        String tenant = kekId.substring(0, kekId.indexOf("-KEK-"));
        try {
            FileSecretSource.create(to.dir(), SecretName.of("kek/" + tenant + "/" + kekId), Files.readAllBytes(from.dir().resolve("kek/" + tenant + "/" + kekId)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 레지스트리 행의 시험용 표기 "ID:상태". */
    private static String view(TenantKekStore.Registered r) {
        return r.kekId() + ":" + (r.current() ? "CURRENT" : "RETIRED");
    }
}
