package com.ga.disclosure.infra.perf;

import java.util.List;

/**
 * 실측 대상 질의(Phase 8 계획 ③-x-2): 화면·API가 실제로 내는 문장과 같은 모양 — 저장소의 SQL을 옮겨 적었다(출처 표기). 매개변수는 생성 데이터에서 고른
 * 값이고 {@code ?}는 테넌트다(앱 롤 + 테넌트 바인딩 + RLS 아래에서 잰다). 쪽 크기는 API 기본 50 + 1(다음 쪽 판정).
 */
final class MeasuredQueries {

    private MeasuredQueries() {
    }

    record Query(String id, String source, String sql) {
    }

    private static final String DISCLOSURE_PAGE = """
            SELECT disclosure_id, disclosure_no, version, status, agent_id, customer_ref, group_code, consult_date, sealed_at, destroyed_at
              FROM disclosure
             WHERE tenant_id = ?%s
             ORDER BY consult_date DESC, disclosure_id DESC
             LIMIT 51""";

    private static final String FLAG_PAGE = """
            SELECT f.flag_id, f.type, f.resolved_at IS NULL AS open, f.raised_at, f.disclosure_id, d.disclosure_no
              FROM compliance_flag f
              LEFT JOIN disclosure d ON d.tenant_id = f.tenant_id AND d.tenant_id = ? AND d.disclosure_id = f.disclosure_id
             WHERE f.tenant_id = ?%s
             ORDER BY f.raised_at DESC, f.flag_id DESC
             LIMIT 51""";

    static final String SOURCE_DISCLOSURES = "DisclosureRepository#page";
    static final String SOURCE_FLAGS = "ComplianceFlagRepository#page";

    /** {@code target} = 생성된 확인서 하나의 ID(감사 대상 조회). */
    static List<Query> all(String target) {
        return List.of(
                new Query("D1 확인서 목록 — 준법(테넌트 전체), 첫 쪽", SOURCE_DISCLOSURES, DISCLOSURE_PAGE.formatted("")),
                new Query("D2 확인서 목록 — 준법, 상태 REASONED", SOURCE_DISCLOSURES, DISCLOSURE_PAGE.formatted(" AND status = 'REASONED'")),
                new Query("D3 확인서 목록 — 설계사 본인", SOURCE_DISCLOSURES, DISCLOSURE_PAGE.formatted(" AND agent_id = 'AG-017'")),
                new Query("D4 확인서 목록 — 관리자 조직 아래", SOURCE_DISCLOSURES,
                        DISCLOSURE_PAGE.formatted(" AND org_path IS NOT NULL AND (org_path = '/HQ/B07' OR starts_with(org_path, '/HQ/B07' || '/'))")),
                new Query("D5 확인서 목록 — 준법, 커서 깊은 쪽", SOURCE_DISCLOSURES,
                        DISCLOSURE_PAGE.formatted(" AND (consult_date, disclosure_id) < (DATE '2025-06-01', 'ffffffff-ffff-ffff-ffff-ffffffffffff'::uuid)")),
                new Query("F1 플래그 큐 — 준법, 열림", SOURCE_FLAGS, FLAG_PAGE.formatted(" AND f.resolved_at IS NULL")),
                new Query("F2 플래그 큐 — 준법, 열림·유형", SOURCE_FLAGS, FLAG_PAGE.formatted(" AND f.resolved_at IS NULL AND f.type = 'IDENTITY_FAILED'")),
                new Query("F3 플래그 큐 — 준법, 전체", SOURCE_FLAGS, FLAG_PAGE.formatted("")),
                new Query("F4 플래그 큐 — 관리자 조직 아래, 열림", SOURCE_FLAGS,
                        FLAG_PAGE.formatted(" AND d.org_path IS NOT NULL AND (d.org_path = '/HQ/B07' OR starts_with(d.org_path, '/HQ/B07' || '/'))"
                                + " AND f.resolved_at IS NULL")),
                new Query("A1 감사 — 대상 조회", "AuditLogRepository#readTarget", """
                        SELECT seq, at, actor_subject, actor_role, action, target_kind, target_id, detail::text AS detail, prev_hash, entry_hash
                          FROM audit_log
                         WHERE tenant_id = ?
                           AND target_kind = 'DISCLOSURE'
                           AND target_id = '%s'
                         ORDER BY seq""".formatted(target)),
                new Query("A2 감사 — 순번 뒤 한 묶음(검증 작업)", "AuditLogRepository#readAfter", """
                        SELECT seq, at, actor_subject, actor_role, action, target_kind, target_id, detail::text AS detail, prev_hash, entry_hash
                          FROM audit_log
                         WHERE tenant_id = ?
                           AND seq > 50000
                         ORDER BY seq
                         LIMIT 500"""),
                new Query("G1 게이트 한도 집계", "GateLimitRepository", """
                        SELECT count(*) AS n FROM audit_log
                         WHERE tenant_id = ? AND action = 'GATE_DECISION' AND actor_subject = 'gate-1' AND at > now() - interval '60 seconds'"""));
    }

    /** 문장의 {@code ?} 개수만큼 테넌트를 묶는다(플래그 목록은 둘). */
    static int tenantParams(Query q) {
        return (int) q.sql().chars().filter(ch -> ch == '?').count();
    }
}
