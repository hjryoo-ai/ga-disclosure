package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20(6B §9 별도 승인 R2): 고객 등록 한도의 집계(주체·행위·시각)가 인덱스로 닿는다 — 인덱스 정의와, 실제에 가까운 분포(통계 수집 뒤)에서 한도 문장의
 * 계획이 그 인덱스를 쓰는지. Phase 8(6B 이월 ②, 8 계획 승인 "V20 인덱스 계열이 게이트 판정 감사 행위를 덮는지 실측"): 게이트 한도의 집계 문장
 * ({@code GATE_DECISION})도 같은 인덱스로 닿는다.
 */
class V20AuditIndexIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("V20");

    @Test
    void theRegistrationLimitCountIsServedByTheSubjectActionIndex() {
        DB.seed(T, c -> SeedData.tenant(c, T));
        // 실제에 가까운 분포: 한 테넌트에 주체 50·행위 4종·시각이 퍼진 감사 행 5,000(트리거를 끈 superuser — 체인은 이 시험의 관심이 아니다)
        try (java.sql.Connection c = DB.superuserDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL session_replication_role = replica");
            }
            SeedData.exec(c, """
                    INSERT INTO audit_log (tenant_id, seq, at, actor_subject, actor_role, action, target_kind, target_id, detail, prev_hash, entry_hash)
                    SELECT ?, g, now() - (g || ' minutes')::interval, 'agent-' || (g % 50),
                           'AGENT', (ARRAY['CUSTOMER_REGISTER','DISCLOSURE_CREATE','ITEMS_REPLACE','DISCLOSURE_VIEW','GATE_DECISION'])[1 + g % 5],
                           'CUSTOMER_REF', 'CR-' || lpad(g::text, 32, '0'), '{}'::jsonb, repeat('0', 64), repeat('0', 64)
                      FROM generate_series(1, 5000) g
                    """, T);
            c.commit();
            try (Statement st = c.createStatement()) {
                c.setAutoCommit(true);
                st.execute("ANALYZE audit_log");
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        assertThat(DB.<String>asApp(T, c -> SeedData.call(c, "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_audit_log_subject_action_at'")))
                .contains("(tenant_id, actor_subject, action, at)");
        // CustomerRegistrationLimitRepository·GateLimitRepository(Phase 8)의 집계 문장과 같은 모양
        for (String action : java.util.List.of("CUSTOMER_REGISTER", "GATE_DECISION")) {
            assertThat(plan(action)).as(action).contains("ix_audit_log_subject_action_at");
        }
    }

    private static String plan(String action) {
        return DB.asApp(T, c -> {
            try (Statement s = c.createStatement()) {
                StringBuilder out = new StringBuilder();
                try (ResultSet rs = s.executeQuery("EXPLAIN SELECT count(*) AS n FROM audit_log WHERE tenant_id = '" + T
                        + "' AND action = '" + action + "' AND actor_subject = 'agent-1' AND at > now() - interval '60 seconds'")) {
                    while (rs.next()) {
                        out.append(rs.getString(1)).append('\n');
                    }
                }
                return out.toString();
            }
        });
    }
}
