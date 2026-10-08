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
import static com.ga.disclosure.infra.TriggerAssertions.sqlStateOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V15(V14 보안 점검): ① 증권 현재값 정합은 연결 쪽에서도 커밋 때 본다 — 연결을 넣거나 대체한 트랜잭션은 그 확인서에 활성 연결 정확히 1건과 같은 현재값을
 * 남겨야 한다(GD136, 지연 제약 트리거). ② 연결은 봉인 이후·폐기·파기되지 않은 확인서에만 생긴다(GD130) — 폐기된 초안(파기 대상 밖)에 증권번호가 남을 길이
 * 없다. ③ 확인서는 증권 현재값 없이 생긴다(GD136).
 */
class V15GuardIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("V15");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-10T09:00:00+09:00");
    private static final String BY_LINK = " WHERE tenant_id = ? AND link_id = ?";

    @BeforeAll
    static void seed() {
        DB.seed(T, c -> {
            SeedData.tenant(c, T);
            SeedData.dataKey(c, T, SeedData.SEED_KEY_ID);
            SeedData.customer(c, T, SeedData.SEED_CUSTOMER_REF);
        });
    }

    private static UUID disclosure(String status) {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> id[0] = SeedData.disclosure(c, T, status, SeedData.hash('a')));
        return id[0];
    }

    /** 연결 행만 넣는다(확인서 현재값은 그대로) — 유스케이스 밖의 쓰기. */
    private static void bareLink(Connection c, UUID id, UUID disclosure, String policy, String date) throws SQLException {
        SeedData.exec(c, """
                INSERT INTO contract_link (tenant_id, link_id, disclosure_id, policy_no, contract_date, insurer_code, source, source_ref, received_at, linked_by)
                VALUES (?, ?, ?, ?, CAST(? AS date), 'INS-A', 'SEED', ?, now(), 'seed')
                """, T, id, disclosure, policy, date, id.toString());
    }

    @Test
    void aLinkIsMadeOnlyForASealedDisclosureThatIsNeitherAbandonedNorDestroyed() {
        for (String status : new String[]{"DRAFT", "COMPARED", "GRADED", "REASONED"}) {
            UUID d = disclosure(status);
            assertThat(sqlStateOf(() -> DB.asApp(T, c -> SeedData.contractLink(c, T, d, "POL-" + status, "2026-10-01")))).as(status).isEqualTo("GD130");
        }
        UUID abandoned = disclosure("DRAFT");
        DB.asAppCommitting(T, c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_abandoner");
            return SeedData.call(c, "SELECT ga_draft_abandon(?, ?, ?, ?)", T, abandoned, AT, "agent-1");
        });
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> SeedData.contractLink(c, T, abandoned, "POL-AB", "2026-10-01")))).isEqualTo("GD130");
        for (String status : new String[]{"SEALED", "PARTIALLY_SIGNED", "COMPLETED", "VOID", "EXPIRED"}) {
            UUID d = disclosure(status);
            DB.asApp(T, c -> SeedData.contractLink(c, T, d, "POL-OK-" + status, "2026-10-01"));
        }
    }

    @Test
    void aLinkWhoseDisclosureDoesNotMirrorItFailsAtCommit() {
        UUID d = disclosure("COMPLETED");
        String state = sqlStateOf(() -> DB.asAppCommitting(T, c -> {
            bareLink(c, UUID.randomUUID(), d, "POL-BARE", "2026-10-01");
            return null;
        }));
        assertThat(state).isEqualTo("GD136");
        // 같은 트랜잭션에서 현재값을 맞추면 커밋된다(연결 유스케이스의 모양)
        DB.asAppCommitting(T, c -> SeedData.contractLink(c, T, d, "POL-BARE", "2026-10-01"));
    }

    @Test
    void supersedingLeavesExactlyOneMirroredActiveLink() {
        UUID d = disclosure("COMPLETED");
        UUID first = DB.asAppCommitting(T, c -> SeedData.contractLink(c, T, d, "POL-S1", "2026-10-01"));
        UUID second = UUID.randomUUID();
        // 정정이 새 값을 확인서에 옮기지 않으면 커밋 때 거부
        assertThat(sqlStateOf(() -> DB.asAppCommitting(T, c -> {
            SeedData.exec(c, "UPDATE contract_link SET superseded_by = ?, superseded_at = now()" + BY_LINK, second, T, first);
            bareLink(c, second, d, "POL-S2", "2026-10-02");
            return null;
        }))).isEqualTo("GD136");
        DB.asAppCommitting(T, c -> {
            SeedData.exec(c, "UPDATE contract_link SET superseded_by = ?, superseded_at = now()" + BY_LINK, second, T, first);
            bareLink(c, second, d, "POL-S2", "2026-10-02");
            SeedData.exec(c, "UPDATE disclosure SET policy_no = 'POL-S2', contract_date = DATE '2026-10-02' WHERE tenant_id = ? AND disclosure_id = ?", T, d);
            return null;
        });
        // 활성 행을 예전 행으로 "대체"하면 활성 0건 — 행 가드·외래키는 통과하지만 커밋 때 거부
        assertThat(sqlStateOf(() -> DB.asAppCommitting(T, c ->
                SeedData.exec(c, "UPDATE contract_link SET superseded_by = ?, superseded_at = now()" + BY_LINK, first, T, second)))).isEqualTo("GD136");
        assertThat(DB.<String>asApp(T, c -> SeedData.call(c, """
                SELECT d.policy_no || '|' || l.policy_no FROM disclosure d JOIN contract_link l
                    ON l.tenant_id = d.tenant_id AND l.disclosure_id = d.disclosure_id AND l.superseded_by IS NULL
                 WHERE d.tenant_id = ? AND d.disclosure_id = ?""", T, d))).isEqualTo("POL-S2|POL-S2");
    }

    @Test
    void aDisclosureIsCreatedWithoutCurrentPolicyValues() {
        UUID d = disclosure("DRAFT");
        String copy = """
                INSERT INTO disclosure
                SELECT (jsonb_populate_record(NULL::disclosure, to_jsonb(x) || jsonb_build_object('disclosure_id', gen_random_uuid()) || ?::jsonb)).*
                  FROM disclosure x WHERE x.tenant_id = ? AND x.disclosure_id = ?""";
        assertRejected(DB, T, "GD136", copy, "{\"policy_no\": \"POL-NEW\"}", T, d);
        assertRejected(DB, T, "GD136", copy, "{\"contract_date\": \"2026-10-01\"}", T, d);
        DB.asApp(T, c -> SeedData.exec(c, copy, "{}", T, d));                                    // 같은 복사가 현재값 없이는 들어간다
    }
}
