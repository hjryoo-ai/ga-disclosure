package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.retention.DestructionJob;
import com.ga.disclosure.workflow.retention.LegalHoldService;
import com.ga.platform.canonical.Sha256;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import static com.ga.disclosure.infra.TriggerAssertions.sqlStateOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G12(5 계획 §5.4): 고객 참조는 살아 있는 확인서(초안 포함)가 하나라도 있으면 건너뛰고, 0건 + 마지막 파기 뒤 유예가 지나면 암호문·CRM ID가 NULL이 된다.
 * 파기된 고객은 더 바뀌지 않고(GD113), DELETE는 여전히 GD064. 감사의 지운 값 해시는 파기 전 저장 바이트에서 따로 계산한 값과 같다.
 */
class CustomerRefDestructionIT {

    static final String[] ERASED = {"name_enc", "phone_enc", "birth_date_enc", "crm_customer_id"};

    RetentionSetup r = new RetentionSetup(body -> ((tools.jackson.databind.node.ObjectNode) body.get("customerRef")).put("graceDaysAfterLastDestruction", 2));

    @AfterEach
    void close() {
        r.close();
    }

    /** 고객 행의 지울 컬럼(저장 바이트 hex 또는 텍스트, NULL은 null). */
    Map<String, String> columns(CustomerRef ref) {
        try (Connection c = r.x.w.db.superuserDataSource().getConnection();
             var ps = c.prepareStatement("SELECT encode(name_enc, 'hex'), encode(phone_enc, 'hex'), encode(birth_date_enc, 'hex'), crm_customer_id,"
                     + " destroyed_at IS NOT NULL FROM customer_ref WHERE tenant_id = ? AND customer_ref = ?")) {
            ps.setString(1, r.x.w.tenant.value());
            ps.setString(2, ref.value());
            try (var rs = ps.executeQuery()) {
                rs.next();
                Map<String, String> out = new TreeMap<>();
                for (int i = 0; i < ERASED.length; i++) {
                    out.put(ERASED[i], rs.getString(i + 1));
                }
                out.put("destroyed", rs.getString(5));
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    DestructionJob.Report runAt(Instant asOf) {
        return RetentionSetup.conforming(r.job().run(r.x.w.tenant, asOf, false, RetentionSetup.SYSTEM, 100));
    }

    @Test
    void aLiveDisclosureEvenADraftKeepsTheCustomer() {
        DisclosureId done = r.completed();
        DisclosureId draft = r.x.w.reasoned(r.x.signer);         // 봉인 전 초안
        r.reconcileAfterRetention();

        DestructionJob.Report report = runAt(RetentionSetup.AFTER.plus(Duration.ofDays(5)));

        assertThat(report.destroyed()).extracting(DestructionJob.Destroyed::id).containsExactly(done);
        assertThat(report.customersDestroyed()).doesNotContain(r.x.signer);
        assertThat(report.customersSkipped()).containsKey("LIVE_DISCLOSURES");
        assertThat(columns(r.x.signer)).containsEntry("destroyed", "f").doesNotContainEntry("name_enc", null);
        assertThat(draft).isNotNull();
    }

    @Test
    void afterTheGraceTheEncryptedColumnsAreErasedAndTheAuditHoldsTheirHashes() {
        r.completed();
        r.reconcileAfterRetention();
        Map<String, String> before = columns(r.x.signer);

        DestructionJob.Report first = runAt(RetentionSetup.AFTER);           // 확인서 파기 당일 — 유예 2일 안
        assertThat(first.destroyed()).hasSize(1);
        assertThat(first.customersDestroyed()).doesNotContain(r.x.signer);
        assertThat(first.customersSkipped()).containsKey("GRACE_NOT_ELAPSED");
        LegalHoldService holds = r.holdService(RetentionSetup.at(RetentionSetup.AFTER));     // 해제된 고객 보류의 사유 텍스트(V11)
        LegalHoldService.Outcome hold = holds.place(r.x.w.tenant, RetentionSetup.OPERATOR, new LegalHoldService.Target.Customer(r.x.signer), "OTHER",
                "가상 민원 메모 — 허구");
        holds.release(r.x.w.tenant, RetentionSetup.RELEASER, hold.holdId(), "CASE_CLOSED");

        DestructionJob.Report second = runAt(RetentionSetup.AFTER.plus(Duration.ofDays(2)));

        assertThat(second.customersDestroyed()).containsExactly(r.x.signer);
        Map<String, String> after = columns(r.x.signer);
        for (String column : ERASED) {
            assertThat(after.get(column)).as(column).isNull();
        }
        assertThat(after).containsEntry("destroyed", "t");
        Map<String, String> expected = new TreeMap<>();
        for (String column : ERASED) {
            String v = before.get(column);
            if (v != null) {
                expected.put(column, column.endsWith("_enc")
                        ? "sha256-stored:" + Sha256.of(java.util.HexFormat.of().parseHex(v))
                        : "sha256-utf8:" + Sha256.of(v.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
        assertThat(expected).containsKeys("name_enc", "phone_enc", "birth_date_enc");
        expected.put("legal_hold.reason_text", "sha256-utf8:" + Sha256.of("가상 민원 메모 — 허구".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(r.count("SELECT count(*) FROM legal_hold WHERE tenant_id = ? AND customer_ref = ? AND reason_text IS NULL AND reason_code = 'OTHER'",
                r.x.w.tenant.value(), r.x.signer.value())).as("행·코드는 남고 텍스트만 지운다").isEqualTo(1);
        assertThat(erasedInAudit(r.x.signer)).isEqualTo(expected);
    }

    @Test
    void aDestroyedCustomerNoLongerChangesAndIsStillNeverDeleted() {
        r.completed();
        r.reconcileAfterRetention();
        runAt(RetentionSetup.AFTER.plus(Duration.ofDays(2)));
        assertThat(columns(r.x.signer)).containsEntry("destroyed", "t");
        String t = r.x.w.tenant.value();
        String ref = r.x.signer.value();

        assertThat(sqlStateOf(() -> r.x.w.db.seed(t, c -> com.ga.disclosure.infra.testing.SeedData.exec(c,
                "UPDATE customer_ref SET crm_customer_id = 'CRM-REVIVED' WHERE tenant_id = ? AND customer_ref = ?", t, ref)))).isEqualTo("GD113");
        assertThat(sqlStateOf(() -> r.x.w.db.seed(t, c -> com.ga.disclosure.infra.testing.SeedData.exec(c,
                "UPDATE customer_ref SET destroyed_at = NULL, destroyed_by = NULL WHERE tenant_id = ? AND customer_ref = ?", t, ref)))).isEqualTo("GD113");
        assertThat(sqlStateOf(() -> r.x.w.db.seed(t, c -> com.ga.disclosure.infra.testing.SeedData.exec(c,
                "DELETE FROM customer_ref WHERE tenant_id = ? AND customer_ref = ?", t, ref)))).isEqualTo("GD064");
        assertThat(runAt(RetentionSetup.AFTER.plus(Duration.ofDays(3))).customersSkipped()).doesNotContainKey("ALREADY_DESTROYED")
                .as("파기된 고객은 다시 후보가 아니다");
    }

    @Test
    void aCustomerWithoutDisclosuresWaitsForAbandonmentAndAHoldKeepsIt() {
        r.close();
        r = new RetentionSetup(body -> ((tools.jackson.databind.node.ObjectNode) body.get("customerRef")).put("abandonedDays", 4));
        CustomerRef abandoned = r.x.newSigner("가상방치고객");
        CustomerRef held = r.x.newSigner("가상보류고객");
        r.holdService(RetentionSetup.at(RetentionSetup.AFTER)).place(r.x.w.tenant, RetentionSetup.OPERATOR, new LegalHoldService.Target.Customer(held),
                "LITIGATION", null);

        assertThat(runAt(RetentionSetup.AFTER).customersDestroyed()).as("등록 2026-09-23 + 4일 전").doesNotContain(abandoned, held);

        DestructionJob.Report later = runAt(RetentionSetup.AFTER.plus(Duration.ofDays(1)));
        assertThat(later.customersDestroyed()).contains(abandoned).doesNotContain(held);
        assertThat(later.customersSkipped()).containsEntry("HOLD", 1);
    }

    private Map<String, String> erasedInAudit(CustomerRef ref) {
        Map<String, String> out = new TreeMap<>();
        for (AuditRecord a : r.x.w.in(r.x.w.audit::readAll)) {
            if (a.entry().action() == AuditAction.CUSTOMER_REF_DESTROYED && ref.value().equals(a.entry().targetId())) {
                for (JsonNode e : a.entry().detail().get("erased")) {
                    String table = e.get("table").asString();
                    out.put(table.equals("customer_ref") ? e.get("column").asString() : table + "." + e.get("column").asString(), e.get("repr").asString() + ":" + e.get("value").asString());
                }
            }
        }
        return out;
    }
}
