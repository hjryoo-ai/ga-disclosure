package com.ga.disclosure.infra.testing;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 통합 테스트 시드 SQL. 호출자가 연 트랜잭션({@code app.tenant_id} 설정됨) 안에서 쓴다.
 * 고객 PII 컬럼에는 평문이 아닌 표식 바이트만 넣는다(CLAUDE.md 절대 규칙 6).
 */
public final class SeedData {

    /** RLS 대상 테넌트 테이블 전부(18개). V2__rls.sql의 목록과 같다. */
    public static final List<String> TENANT_TABLES = List.of(
            "tenant", "identity_link", "rule_version", "form_template",
            "product_group", "product_catalog", "insurer_panel", "customer_ref",
            "disclosure", "disclosure_item", "recommendation", "document_artifact",
            "sign_session", "signature", "audit_log", "audit_anchor",
            "subject_policy", "compliance_flag");

    /** SHA-256(JCS({})) = SHA-256("{}"). */
    public static final String EMPTY_OBJECT_HASH = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";

    public static final List<String> MUTABLE_STATUSES = List.of("DRAFT", "COMPARED", "GRADED", "REASONED");
    public static final List<String> SEALED_STATUSES = List.of("SEALED", "PARTIALLY_SIGNED", "COMPLETED", "VOID", "SUPERSEDED", "EXPIRED");
    public static final List<String> ALL_STATUSES = List.of(
            "DRAFT", "COMPARED", "GRADED", "REASONED", "SEALED", "PARTIALLY_SIGNED", "COMPLETED", "VOID", "SUPERSEDED", "EXPIRED");

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private SeedData() {
    }

    public static String hash(char c) {
        return String.valueOf(c).repeat(64);
    }

    public static String uniqueTenant(String prefix) {
        return (prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)).toUpperCase();
    }

    public static void tenant(Connection c, String tenant) throws SQLException {
        exec(c, "INSERT INTO tenant (tenant_id, name, engine_base_url, status, large_ga) VALUES (?, ?, ?, 'ACTIVE', true)",
                tenant, "GA " + tenant, "http://engine.invalid/" + tenant);
    }

    /** 확인서 헤더 1건(봉인 이후 상태면 봉인 컬럼도 채운다). */
    public static UUID disclosure(Connection c, String tenant, String status, String canonicalHash) throws SQLException {
        UUID id = UUID.randomUUID();
        boolean sealed = !MUTABLE_STATUSES.contains(status);
        exec(c, """
                INSERT INTO disclosure (tenant_id, disclosure_id, disclosure_no, agent_id, customer_ref, group_code,
                                        template_id, template_version, rule_version_id, issuer_mode, status, consult_date,
                                        grade_snapshot_id, sealed_at, canonical_hash, pdf_hash, chain_hash, chain_seq)
                VALUES (?, ?, ?, 'AGENT-1', 'C-0001', 'PG-HEALTH', 'STANDARD', 1, ?, 'SELF', ?, DATE '2026-09-23',
                        'GRD-1', CASE WHEN ? THEN TIMESTAMPTZ '2026-09-23 10:00:00+09' END, ?, ?, ?, ?)
                """,
                tenant, id, sealed ? tenant + "-2026-" + String.format("%06d", SEQUENCE.incrementAndGet()) : null,
                sealed ? "DISC-2026-07" : null, status, sealed,
                sealed ? canonicalHash : null, sealed ? hash('b') : null, sealed ? hash('c') : null, sealed ? 1L : null);
        return id;
    }

    public static void item(Connection c, String tenant, UUID disclosure, int itemNo, String ratioToAvg) throws SQLException {
        exec(c, """
                INSERT INTO disclosure_item (tenant_id, disclosure_id, item_no, product_key, insurer_code, product_name,
                                             is_recommended, field_values, grade, grade_label, grade_ordinal, rank_in_set,
                                             ratio_to_avg, grade_status)
                VALUES (?, ?, ?, ?, 'INS-A', '상품', true, '{}'::jsonb, 'LOW', '낮음', 2, ?, ?, 'OK')
                """, tenant, disclosure, (short) itemNo, "INS-A:PRD-" + itemNo, (short) itemNo, ratioToAvg);
    }

    public static void recommendation(Connection c, String tenant, UUID disclosure, int itemNo) throws SQLException {
        exec(c, "INSERT INTO recommendation (tenant_id, disclosure_id, item_no, reason_codes) VALUES (?, ?, ?, ARRAY['PREMIUM'])",
                tenant, disclosure, (short) itemNo);
    }

    public static void signature(Connection c, String tenant, UUID disclosure, String role, String signedDocHash) throws SQLException {
        exec(c, """
                INSERT INTO signature (tenant_id, signature_id, disclosure_id, signer_role, channel, method, signed_doc_hash,
                                       evidence_key, evidence_hash, signed_at)
                VALUES (?, ?, ?, ?, 'TOUCH_PAD', 'DRAWN', ?, 'evidence/key', ?, TIMESTAMPTZ '2026-09-23 10:05:00+09')
                """, tenant, UUID.randomUUID(), disclosure, role, signedDocHash, hash('e'));
    }

    public static void artifact(Connection c, String tenant, UUID disclosure, String kind) throws SQLException {
        exec(c, """
                INSERT INTO document_artifact (tenant_id, disclosure_id, kind, storage_key, sha256, bytes, created_at)
                VALUES (?, ?, ?, 'store/key', ?, 10, TIMESTAMPTZ '2026-09-23 10:00:00+09')
                """, tenant, disclosure, kind, hash('f'));
    }

    public static void auditLog(Connection c, String tenant, long seq) throws SQLException {
        exec(c, """
                INSERT INTO audit_log (tenant_id, seq, at, action, prev_hash, entry_hash)
                VALUES (?, ?, TIMESTAMPTZ '2026-09-23 10:00:00+09', 'CREATE', ?, ?)
                """, tenant, seq, hash('0'), hash('1'));
    }

    public static void anchor(Connection c, String tenant) throws SQLException {
        exec(c, """
                INSERT INTO audit_anchor (tenant_id, anchored_at, head_seq, head_hash)
                VALUES (?, TIMESTAMPTZ '2026-09-24 00:00:00+09', 1, ?)
                """, tenant, hash('1'));
    }

    /**
     * 룰 버전 1건을 정상 경로로 만든다(V4 트리거가 허용하는 순서): GLOBAL은 APPROVED로 삽입(번들 복제본), TENANT는 DRAFT로
     * 삽입 후 승인. 그 뒤 ACTIVE·RETIRED로 한 단계씩 전진한다. GLOBAL·DRAFT는 존재할 수 없다.
     */
    public static void ruleVersion(Connection c, String tenant, String id, String scope, String status,
                                   String applyFrom, String applyToOrNull) throws SQLException {
        List<String> order = List.of("DRAFT", "APPROVED", "ACTIVE", "RETIRED");
        if (scope.equals("GLOBAL") && status.equals("DRAFT")) {
            throw new IllegalArgumentException("a GLOBAL rule is never DRAFT");
        }
        boolean global = scope.equals("GLOBAL");
        exec(c, """
                INSERT INTO rule_version (tenant_id, rule_version_id, scope, apply_from, apply_to, status, approved_by, approved_at,
                                          body, source_bundle_id, bundle_hash)
                VALUES (?, ?, ?, CAST(? AS date), CAST(? AS date), ?, ?, CASE WHEN ? THEN TIMESTAMPTZ '2026-06-30 09:00:00+09' END,
                        '{}'::jsonb, ?, ?)
                """, tenant, id, scope, applyFrom, applyToOrNull, global ? "APPROVED" : "DRAFT",
                global ? "OPERATOR:seed" : null, global, global ? id + "@44136fa355b3" : null, global ? EMPTY_OBJECT_HASH : null);
        for (int i = order.indexOf(global ? "APPROVED" : "DRAFT") + 1; i <= order.indexOf(status); i++) {
            String next = order.get(i);
            if (next.equals("APPROVED")) {
                exec(c, """
                        UPDATE rule_version SET status = 'APPROVED', approved_by = 'COMPLIANCE:seed',
                                                approved_at = TIMESTAMPTZ '2026-06-30 10:00:00+09'
                         WHERE tenant_id = ? AND rule_version_id = ?
                        """, tenant, id);
            } else {
                exec(c, "UPDATE rule_version SET status = ? WHERE tenant_id = ? AND rule_version_id = ?", next, tenant, id);
            }
        }
    }

    /** 서식 템플릿 1건. {@code bundleIdOrNull}이 있으면 번들 출처(해시는 빈 객체 해시 자리값). */
    public static void formTemplate(Connection c, String tenant, String templateId, int version, String applyFrom,
                                    String applyToOrNull, String bundleIdOrNull) throws SQLException {
        exec(c, """
                INSERT INTO form_template (tenant_id, template_id, template_type, version, apply_from, apply_to, fields, layout,
                                           source_bundle_id, bundle_hash)
                VALUES (?, ?, 'STANDARD', ?, CAST(? AS date), CAST(? AS date), '[]'::jsonb, '{}'::jsonb, ?, ?)
                """, tenant, templateId, version, applyFrom, applyToOrNull, bundleIdOrNull,
                bundleIdOrNull == null ? null : EMPTY_OBJECT_HASH);
    }

    /** 18개 테넌트 테이블 전부에 한 행 이상을 넣는다(RLS 격리 검증용). */
    public static void everyTable(Connection c, String tenant) throws SQLException {
        tenant(c, tenant);
        exec(c, "INSERT INTO identity_link (tenant_id, subject, agent_id, roles, org_path) VALUES (?, 'sub-1', 'AGENT-1', ARRAY['AGENT'], '/HQ/B1')", tenant);
        exec(c, """
                INSERT INTO rule_version (tenant_id, rule_version_id, scope, apply_from, status, approved_by, approved_at, body,
                                          source_bundle_id, bundle_hash)
                VALUES (?, 'DISC-2026-07', 'GLOBAL', DATE '2026-07-01', 'APPROVED', 'OPERATOR:seed', TIMESTAMPTZ '2026-06-30 09:00:00+09',
                        '{}'::jsonb, 'DISC-2026-07@44136fa355b3', ?)
                """, tenant, EMPTY_OBJECT_HASH);
        // GLOBAL 복제본은 APPROVED로만 들어가고(V4 GD040) 활성화는 한 단계 전진이다.
        exec(c, "UPDATE rule_version SET status = 'ACTIVE' WHERE tenant_id = ? AND rule_version_id = 'DISC-2026-07'", tenant);
        exec(c, """
                INSERT INTO form_template (tenant_id, template_id, template_type, version, apply_from, fields, layout)
                VALUES (?, 'STANDARD', 'STANDARD', 1, DATE '2026-07-01', '[]'::jsonb, '{}'::jsonb)
                """, tenant);
        exec(c, "INSERT INTO product_group (tenant_id, group_code, name, line, apply_from, source) VALUES (?, 'PG-HEALTH', '건강', 'LIFE', DATE '2026-01-01', 'SEED')", tenant);
        exec(c, """
                INSERT INTO product_catalog (tenant_id, product_key, insurer_code, group_code, product_name, defaults, source, synced_at)
                VALUES (?, 'INS-A:PRD-1', 'INS-A', 'PG-HEALTH', '상품', '{}'::jsonb, 'SEED', TIMESTAMPTZ '2026-09-01 00:00:00+09')
                """, tenant);
        exec(c, "INSERT INTO insurer_panel (tenant_id, insurer_code, insurer_name, line, active_from) VALUES (?, 'INS-A', '보험사A', 'LIFE', DATE '2026-01-01')", tenant);
        exec(c, "INSERT INTO customer_ref (tenant_id, customer_ref, name_enc, created_at) VALUES (?, 'C-0001', '\\x00'::bytea, TIMESTAMPTZ '2026-09-01 00:00:00+09')", tenant);
        UUID draft = disclosure(c, tenant, "DRAFT", null);
        item(c, tenant, draft, 1, "0.84");
        recommendation(c, tenant, draft, 1);
        UUID sealed = disclosure(c, tenant, "SEALED", hash('a'));
        artifact(c, tenant, sealed, "PDF");
        exec(c, """
                INSERT INTO sign_session (tenant_id, session_id, disclosure_id, signer_role, channel, expires_at, status)
                VALUES (?, ?, ?, 'CUSTOMER', 'REMOTE_LINK', TIMESTAMPTZ '2026-09-26 00:00:00+09', 'OPEN')
                """, tenant, UUID.randomUUID(), sealed);
        signature(c, tenant, sealed, "CUSTOMER", hash('a'));
        auditLog(c, tenant, 1);
        anchor(c, tenant);
        exec(c, """
                INSERT INTO subject_policy (tenant_id, policy_no, agent_id, contract_date, required, recon_status)
                VALUES (?, 'POL-1', 'AGENT-1', DATE '2026-09-30', true, 'MISSING')
                """, tenant);
        exec(c, """
                INSERT INTO compliance_flag (tenant_id, flag_id, type, severity, raised_at)
                VALUES (?, ?, 'MISSING', 'HIGH', TIMESTAMPTZ '2026-10-01 00:00:00+09')
                """, tenant, UUID.randomUUID());
    }

    public static int exec(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            return ps.executeUpdate();
        }
    }
}
