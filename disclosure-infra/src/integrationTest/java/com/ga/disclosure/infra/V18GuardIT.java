package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.ga.disclosure.infra.TriggerAssertions.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V18(6B 중간 회신 ①②④⑤): ① 계약 연결 배치 원장 — 참조마다 내용 하나, 요약은 한 번, 지우지 않는다(GD138). ② 계약 피드 주체는 출처 목록을 가진다
 * (다른 주체는 없다). ④ 이미 지워진 대상(파기된 확인서·폐기된 초안·문서 키가 파기된 확인서·파기된 고객)에는 보류를 걸지 않는다(GD139). ⑤ 감사 대상 인덱스.
 * 보류와 폐기·파기의 동시 실행은 {@code LegalHoldRaceIT}가 본다.
 */
class V18GuardIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("V18");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-10T09:00:00+09:00");
    private static final String H1 = "1".repeat(64);
    private static final String H2 = "2".repeat(64);
    private static final String GONE = "CR-" + "0".repeat(31) + "9";

    @BeforeAll
    static void seed() {
        DB.seed(T, c -> {
            SeedData.tenant(c, T);
            SeedData.dataKey(c, T, SeedData.SEED_KEY_ID);
            SeedData.customer(c, T, SeedData.SEED_CUSTOMER_REF);
            SeedData.customer(c, T, GONE);
        });
    }

    interface Sql {
        void run(Connection c) throws SQLException;
    }

    /** 트리거를 끄고 고친다 — 지워진 대상의 모양만 만든다(지우는 경로는 각 함수의 시험이 본다). */
    static void bypass(Sql sql) {
        try (Connection c = DB.superuserDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("SET LOCAL session_replication_role = replica");
            }
            sql.run(c);
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID disclosure(String status) {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> id[0] = SeedData.disclosure(c, T, status, SeedData.hash('a')));
        return id[0];
    }

    @Test
    void theBatchLedgerKeepsOneContentPerReferenceAndItsSummaryOnce() {
        String insert = "INSERT INTO contract_link_batch (tenant_id, source, batch_id, content_sha256, items, received_at, received_by, summary, completed_at) "
                + "VALUES (?, ?, ?, ?, 1, now(), 'feed-1', CAST(? AS jsonb), CAST(? AS timestamptz))";
        assertRejected(DB, T, "GD138", insert, T, "INS_FEED_A", "B-SUMMARY", H1, "{}", "2026-10-09T00:00:00Z");
        DB.asAppCommitting(T, c -> SeedData.exec(c, insert, T, "INS_FEED_A", "B-1", H1, null, null));
        // 같은 참조는 한 행(다른 내용도 같은 참조로는 들어오지 못한다)
        assertRejected(DB, T, "23505", insert, T, "INS_FEED_A", "B-1", H2, null, null);
        String where = " WHERE tenant_id = ? AND source = 'INS_FEED_A' AND batch_id = 'B-1'";
        // 앱 롤은 요약·완료 시각만 고칠 수 있다 — 내용 해시는 권한부터 없다
        assertRejected(DB, T, "42501", "UPDATE contract_link_batch SET content_sha256 = ?" + where, H2, T);
        DB.asAppCommitting(T, c -> SeedData.exec(c, "UPDATE contract_link_batch SET summary = '{\"LINKED\": 1}', completed_at = now()" + where, T));
        assertRejected(DB, T, "GD138", "UPDATE contract_link_batch SET summary = '{\"LINKED\": 2}', completed_at = now()" + where, T);
        assertRejected(DB, T, "42501", "DELETE FROM contract_link_batch" + where, T);
        // 소유자(마이그레이터)도 지우지 못한다
        String ownerDelete;
        try (Connection c = DB.migratorDataSource().getConnection(); var st = c.createStatement()) {
            st.execute("SELECT set_config('app.tenant_id', '" + T + "', false)");
            st.execute("DELETE FROM contract_link_batch WHERE tenant_id = '" + T + "'");
            ownerDelete = "deleted";
        } catch (SQLException e) {
            ownerDelete = e.getSQLState();
        }
        assertThat(ownerDelete).isEqualTo("GD138");
    }

    @Test
    void aContractFeedIdentityNamesItsSourcesAndNoOtherIdentityHasAny() {
        String link = "INSERT INTO identity_link (tenant_id, subject, agent_id, roles, org_path, feed_sources) "
                + "VALUES (?, ?, NULL, CAST(? AS text[]), NULL, CAST(? AS text[]))";
        assertRejected(DB, T, "23514", link, T, "feed-none", "{CONTRACT_FEED}", null);
        assertRejected(DB, T, "23514", link, T, "feed-empty", "{CONTRACT_FEED}", "{}");
        assertRejected(DB, T, "23514", link, T, "feed-lower", "{CONTRACT_FEED}", "{ins_feed_a}");
        assertRejected(DB, T, "23514", link, T, "feed-dup", "{CONTRACT_FEED}", "{INS_FEED_A,INS_FEED_A}");
        assertRejected(DB, T, "23514", link, T, "sched-src", "{SCHEDULER}", "{INS_FEED_A}");
        TriggerAssertions.assertAllowed(DB, T, link, T, "feed-two", "{CONTRACT_FEED}", "{INS_FEED_A,INS_FEED_B}");
        TriggerAssertions.assertAllowed(DB, T, link, T, "sched-1", "{SCHEDULER}", null);
    }

    @Test
    void aHoldIsNeverPlacedOnAnErasedTarget() {
        String hold = "INSERT INTO legal_hold (tenant_id, hold_id, disclosure_id, reason_code, placed_by, placed_at) VALUES (?, gen_random_uuid(), ?, 'LITIGATION', 'ops', now())";
        String customerHold = "INSERT INTO legal_hold (tenant_id, hold_id, customer_ref, reason_code, placed_by, placed_at) VALUES (?, gen_random_uuid(), ?, 'LITIGATION', 'ops', now())";

        UUID destroyed = disclosure("COMPLETED");
        bypass(c -> SeedData.exec(c, "UPDATE disclosure SET destroyed_at = now(), destroyed_by = 'seed' WHERE tenant_id = ? AND disclosure_id = ?", T, destroyed));
        UUID abandoned = disclosure("DRAFT");
        DB.asAppCommitting(T, c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_abandoner");
            return SeedData.call(c, "SELECT ga_draft_abandon(?, ?, ?, ?)", T, abandoned, AT, "agent-1");
        });
        UUID shredded = disclosure("COMPLETED");
        DB.seed(T, c -> SeedData.documentKey(c, T, shredded));
        bypass(c -> SeedData.exec(c, "UPDATE document_key SET wrapped_dek = NULL, shredded_at = now(), shredded_by = 'seed' WHERE tenant_id = ? AND disclosure_id = ?",
                T, shredded));
        bypass(c -> SeedData.exec(c, "UPDATE customer_ref SET name_enc = NULL, phone_enc = NULL, birth_date_enc = NULL, crm_customer_id = NULL, destroyed_at = now(), destroyed_by = 'seed' "
                + "WHERE tenant_id = ? AND customer_ref = ?", T, GONE));

        for (UUID gone : new UUID[]{destroyed, abandoned, shredded}) {
            assertRejected(DB, T, "GD139", hold, T, gone);
        }
        assertRejected(DB, T, "GD139", customerHold, T, GONE);
        // 살아 있는 대상은 그대로 보류된다
        TriggerAssertions.assertAllowed(DB, T, hold, T, disclosure("COMPLETED"));
        TriggerAssertions.assertAllowed(DB, T, hold, T, disclosure("DRAFT"));
        TriggerAssertions.assertAllowed(DB, T, customerHold, T, SeedData.SEED_CUSTOMER_REF);
    }

    @Test
    void theAuditLogIsIndexedByTarget() {
        assertThat(DB.<String>asApp(T, c -> SeedData.call(c, "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_audit_log_target'")))
                .contains("(tenant_id, target_id, seq DESC)");
    }
}
