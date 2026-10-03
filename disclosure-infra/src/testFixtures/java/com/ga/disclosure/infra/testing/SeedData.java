package com.ga.disclosure.infra.testing;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/**
 * 통합 테스트 시드 SQL. 호출자가 연 트랜잭션({@code app.tenant_id} 설정됨) 안에서 쓴다.
 * 고객 PII 컬럼에는 평문이 아닌 표식 바이트만 넣는다(CLAUDE.md 절대 규칙 6).
 */
public final class SeedData {


    public static final String SEED_KEY_ID = "DEK-SEED";
    public static final String SEED_CUSTOMER_REF = "CR-" + "0".repeat(31) + "1";

    /** SHA-256(JCS({})) = SHA-256("{}"). */
    public static final String EMPTY_OBJECT_HASH = "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a";

    public static final List<String> MUTABLE_STATUSES = List.of("DRAFT", "COMPARED", "GRADED", "REASONED");
    public static final List<String> SEALED_STATUSES = List.of("SEALED", "PARTIALLY_SIGNED", "COMPLETED", "VOID", "SUPERSEDED", "EXPIRED");
    public static final List<String> ALL_STATUSES = List.of(
            "DRAFT", "COMPARED", "GRADED", "REASONED", "SEALED", "PARTIALLY_SIGNED", "COMPLETED", "VOID", "SUPERSEDED", "EXPIRED");


    private SeedData() {
    }

    public static String hash(char c) {
        return String.valueOf(c).repeat(64);
    }

    public static String uniqueTenant(String prefix) {
        return (prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)).toUpperCase();
    }

    /** 행위자 subject ↔ 설계사·역할 연결 1건(V1 {@code identity_link}, Phase 4 — 확인서 작성·설계사 서명이 이 연결로 설계사를 정한다). */
    public static void identityLink(Connection c, String tenant, String subject, String agentId, String role) throws SQLException {
        exec(c, "INSERT INTO identity_link (tenant_id, subject, agent_id, roles, org_path) VALUES (?, ?, ?, ARRAY[?], '/HQ/B1')",
                tenant, subject, agentId, role);
    }

    /** 설계사·조직 없는 연결(준법·서비스 주체 — V12 {@code ck_identity_link_service_alone}·{@code _org}). */
    public static void roleLink(Connection c, String tenant, String subject, String role) throws SQLException {
        exec(c, "INSERT INTO identity_link (tenant_id, subject, agent_id, roles, org_path) VALUES (?, ?, NULL, ARRAY[?], NULL)", tenant, subject, role);
    }

    public static void tenant(Connection c, String tenant) throws SQLException {
        exec(c, "INSERT INTO tenant (tenant_id, name, engine_base_url, status, large_ga) VALUES (?, ?, ?, 'ACTIVE', true)",
                tenant, "GA " + tenant, "http://engine.invalid/" + tenant);
    }

    /** 산출 전 상태(스냅샷 헤더가 없어야 한다, V6 {@code ck_disclosure_snapshot_state}). */
    public static final List<String> UNGRADED_STATUSES = List.of("DRAFT", "COMPARED");

    /** 시드 봉인 시각(Asia/Seoul 2026년 — 번호 연도 2026, V7 GD095)과 보존기한(봉인일 + 5년). */
    public static final String SEALED_AT = "2026-09-23 10:00:00+09";
    public static final String RETENTION_UNTIL = "2031-09-23";
    public static final String PDF_HASH = hash('b');

    /**
     * 확인서 헤더 1건. 산출 이후 상태(GRADED~)면 엔진 스냅샷 헤더 6개를 채운다(V6 헤더 전부-또는-없음). 봉인 이후 상태면 REASONED로
     * 넣은 뒤 {@link #seal}로 봉인 경로 그대로 봉인하고 그 상태로 옮긴다 — 봉인 컬럼은 카운터·체인 머리와 맞아야 한다(V7 GD095).
     */
    public static UUID disclosure(Connection c, String tenant, String status, String canonicalHash) throws SQLException {
        return disclosure(c, tenant, status, canonicalHash, "DISC-2026-07");
    }

    /** {@link #disclosure(Connection, String, String, String)}과 같되 고정 GLOBAL 룰 버전을 지정한다(서명자 집합 검사 V8 GD104). */
    public static UUID disclosure(Connection c, String tenant, String status, String canonicalHash, String ruleVersionId) throws SQLException {
        return disclosure(c, tenant, status, canonicalHash, ruleVersionId, "C-0001");
    }

    /** 고객을 지정한다(V9 고객 파기·고객 보류 — {@code customer_ref}에 실재하는 고객이어야 할 때, {@link #customer}). */
    public static UUID disclosure(Connection c, String tenant, String status, String canonicalHash, String ruleVersionId, String customerRef)
            throws SQLException {
        UUID id = UUID.randomUUID();
        boolean sealed = !MUTABLE_STATUSES.contains(status);
        String inserted = sealed ? "REASONED" : status;
        boolean graded = !UNGRADED_STATUSES.contains(inserted);
        exec(c, """
                INSERT INTO disclosure (tenant_id, disclosure_id, org_path, agent_id, customer_ref, group_code,
                                        template_id, template_version, rule_version_id, issuer_mode, status, consult_date,
                                        grade_snapshot_id, grading_policy_version_id, ranking_policy_version_id, tie_break, grade_basis,
                                        snapshot_generated_at)
                VALUES (?, ?, '/HQ/B1', 'AGENT-1', ?, 'PG-HEALTH', 'STANDARD', 1, ?, 'SELF', ?, DATE '2026-09-23',
                        CASE WHEN ? THEN 'GRD-1' END, CASE WHEN ? THEN 'GRADING-2026-07' END, CASE WHEN ? THEN 'RANK-2026-07' END,
                        CASE WHEN ? THEN 'SHARED_RANK' END,
                        CASE WHEN ? THEN '{"groupAvgSource": "ASSOC_DISCLOSURE", "period": "2026Q2", "groupPopulation": 27}'::jsonb END,
                        CASE WHEN ? THEN TIMESTAMPTZ '2026-09-23 09:30:00+09' END)
                """,
                tenant, id, customerRef, ruleVersionId, inserted, graded, graded, graded, graded, graded, graded);
        if (sealed) {
            seal(c, tenant, id, canonicalHash, status);
        }
        return id;
    }

    /**
     * 가변 상태의 확인서를 봉인 경로 그대로 봉인한다: 카운터 채번(테넌트, 2026) → 체인 머리 잠금 → 봉인 컬럼 UPDATE(번호·해시·체인·
     * 보존기한) → 머리 이동. {@code status}가 SEALED가 아니면 그 상태로 옮긴다(VOID는 무효 시각·사유 코드, SUPERSEDED는 후속 ID·사유 코드,
     * COMPLETED는 완료 시각을 함께 — V7·V8 일관성 CHECK). 상태표를 거치지 않는 시드 경로이므로 DB가 허용하는 조합만 만든다.
     */
    public static void seal(Connection c, String tenant, UUID id, String canonicalHash, String status) throws SQLException {
        long seq = longValue(c, """
                INSERT INTO disclosure_counter (tenant_id, year, seq) VALUES (?, 2026, 1)
                ON CONFLICT (tenant_id, year) DO UPDATE SET seq = disclosure_counter.seq + 1
                RETURNING seq
                """, tenant);
        long headSeq = 0;
        String headHash = hash('0');
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT chain_seq, chain_hash FROM disclosure_chain_head WHERE tenant_id = ? FOR UPDATE")) {
            ps.setString(1, tenant);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    headSeq = rs.getLong(1);
                    headHash = rs.getString(2);
                }
            }
        }
        String chainHash = chainHash(headHash, canonicalHash, PDF_HASH);
        exec(c, """
                UPDATE disclosure SET status = 'SEALED', disclosure_no = ?, sealed_at = CAST(? AS timestamptz), canonical_hash = ?,
                                      pdf_hash = ?, chain_hash = ?, chain_seq = ?, retention_until = CAST(? AS date)
                 WHERE tenant_id = ? AND disclosure_id = ?
                """, tenant + "-2026-" + String.format("%06d", seq), SEALED_AT, canonicalHash, PDF_HASH, chainHash, headSeq + 1,
                RETENTION_UNTIL, tenant, id);
        if (headSeq == 0) {
            exec(c, "INSERT INTO disclosure_chain_head (tenant_id, chain_seq, chain_hash) VALUES (?, 1, ?)", tenant, chainHash);
        } else {
            exec(c, "UPDATE disclosure_chain_head SET chain_seq = ?, chain_hash = ? WHERE tenant_id = ?", headSeq + 1, chainHash, tenant);
        }
        switch (status) {
            case "SEALED" -> {
            }
            case "VOID" -> exec(c, """
                    UPDATE disclosure SET status = 'VOID', voided_at = TIMESTAMPTZ '2026-09-24 09:00:00+09',
                                          void_reason_code = 'SEED_VOID', void_reason_text = '시드 무효'
                     WHERE tenant_id = ? AND disclosure_id = ?
                    """, tenant, id);
            case "SUPERSEDED" -> exec(c, """
                    UPDATE disclosure SET status = 'SUPERSEDED', superseded_by_id = gen_random_uuid(), supersede_reason_code = 'SEED_CORRECTION'
                     WHERE tenant_id = ? AND disclosure_id = ?
                    """, tenant, id);
            case "COMPLETED" -> exec(c, """
                    UPDATE disclosure SET status = 'COMPLETED', completed_at = TIMESTAMPTZ '2026-09-24 10:00:00+09'
                     WHERE tenant_id = ? AND disclosure_id = ?
                    """, tenant, id);
            default -> exec(c, "UPDATE disclosure SET status = ? WHERE tenant_id = ? AND disclosure_id = ?", status, tenant, id);
        }
    }

    /** 봉인 체인 식(설계서 §6.4): SHA-256(prev ‖ canonical ‖ pdf), 세 값은 소문자 hex ASCII. */
    public static String chainHash(String prev, String canonicalHash, String pdfHash) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((prev + canonicalHash + pdfHash).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void item(Connection c, String tenant, UUID disclosure, int itemNo, String ratioToAvg) throws SQLException {
        exec(c, """
                INSERT INTO disclosure_item (tenant_id, disclosure_id, item_no, product_key, insurer_code, group_code, product_name,
                                             is_recommended, field_values, grade, grade_label, grade_ordinal, rank_in_set,
                                             ratio_to_avg, grade_status, tie, grade_source)
                VALUES (?, ?, ?, ?, 'INS-A', 'PG-HEALTH', '상품', true, '{}'::jsonb, 'LOW', '낮음', 2, ?, ?, 'OK', false, 'ENGINE')
                """, tenant, disclosure, (short) itemNo, "INS-A:PRD-" + itemNo, (short) itemNo, ratioToAvg);
    }

    public static void recommendation(Connection c, String tenant, UUID disclosure, int itemNo) throws SQLException {
        exec(c, "INSERT INTO recommendation (tenant_id, disclosure_id, item_no, reason_codes) VALUES (?, ?, ?, ARRAY['PREMIUM'])",
                tenant, disclosure, (short) itemNo);
    }

    /**
     * 서명 1건(V8 형식: 두 해시, 결과만의 본인확인). 고객은 부모가 서명 가능 상태면 부모 해시를 고정한 OPEN 세션을 먼저 발급해 그 세션으로
     * 서명하고 세션을 USED로 닫는다(실제 경로와 같은 순서 — 트리거가 OPEN 세션을 본다). 설계사·관리자는 SSO 행위자로 서명한다(승인 Q3).
     * 서명자 집합 검사(GD104)를 위해 고정 룰 {@code DISC-2026-07}에 {@link #SIGNER_RULE_BODY}가 없으면 넣는다.
     */
    public static UUID signature(Connection c, String tenant, UUID disclosure, String role, String signedDocHash) throws SQLException {
        return signature(c, tenant, disclosure, role, signedDocHash, PDF_HASH);
    }

    /** {@link #signature(Connection, String, UUID, String, String)}과 같되 PDF 해시를 지정한다(실제 봉인 경로로 만든 확인서). */
    public static UUID signature(Connection c, String tenant, UUID disclosure, String role, String signedDocHash, String signedPdfHash)
            throws SQLException {
        signerRule(c, tenant);
        UUID signatureId = UUID.randomUUID();
        UUID session = role.equals("CUSTOMER") ? openSession(c, tenant, disclosure) : null;
        exec(c, """
                INSERT INTO signature (tenant_id, signature_id, disclosure_id, signer_role, signer_subject, channel, method, signed_doc_hash,
                                       signed_pdf_hash, session_id, identity_check, signed_at)
                VALUES (?, ?, ?, ?, ?, ?, 'DRAWN', ?, ?, ?, '[]'::jsonb, TIMESTAMPTZ '2026-09-23 10:05:00+09')
                """, tenant, signatureId, disclosure, role, session == null ? role.toLowerCase(java.util.Locale.ROOT) + "@seed" : null,
                session == null ? "SSO" : "TOUCH_PAD", signedDocHash, signedPdfHash, session);
        if (session != null) {
            exec(c, "UPDATE sign_session SET status = 'USED', used_at = TIMESTAMPTZ '2026-09-23 10:05:00+09' WHERE tenant_id = ? AND session_id = ?",
                    tenant, session);
        }
        return signatureId;
    }

    /**
     * 고객 서명 1건에 V9 파기 대상 컬럼(기기·IP·열람 증거)을 채운다. 세션에도 열람 증거를 남긴다(세션은 OPEN일 때만 기록할 수 있다).
     * 부모는 서명 가능 상태여야 한다.
     */
    public static UUID customerSignatureWithEvidence(Connection c, String tenant, UUID disclosure, String signedDocHash) throws SQLException {
        signerRule(c, tenant);
        UUID signatureId = UUID.randomUUID();
        UUID session = openSession(c, tenant, disclosure);
        exec(c, """
                UPDATE sign_session SET view_evidence = '{"scrollComplete": true, "viewSeconds": 61}'::jsonb
                 WHERE tenant_id = ? AND session_id = ?
                """, tenant, session);
        exec(c, """
                INSERT INTO signature (tenant_id, signature_id, disclosure_id, signer_role, signer_subject, channel, method, signed_doc_hash,
                                       signed_pdf_hash, session_id, identity_check, signed_at, device, ip, view_evidence)
                VALUES (?, ?, ?, 'CUSTOMER', NULL, 'TOUCH_PAD', 'DRAWN', ?, ?, ?, '[]'::jsonb, TIMESTAMPTZ '2026-09-23 10:05:00+09',
                        '{"fingerprint": "fp-seed", "userAgent": "SeedTablet/1"}'::jsonb, INET '198.51.100.23',
                        '{"scrollComplete": true, "viewSeconds": 61}'::jsonb)
                """, tenant, signatureId, disclosure, signedDocHash, PDF_HASH, session);
        exec(c, "UPDATE sign_session SET status = 'USED', used_at = TIMESTAMPTZ '2026-09-23 10:05:00+09' WHERE tenant_id = ? AND session_id = ?",
                tenant, session);
        return signatureId;
    }

    /**
     * 고객 1명(V9 파기 대상 컬럼 전부: 성명·전화·생년월일 암호문 자리값, CRM ID). 데이터 키 {@link #SEED_KEY_ID}가 있어야 한다.
     */
    public static void customer(Connection c, String tenant, String customerRef) throws SQLException {
        exec(c, """
                INSERT INTO customer_ref (tenant_id, customer_ref, name_enc, phone_enc, birth_date_enc, crm_customer_id, enc_key_id, created_at)
                VALUES (?, ?, decode('01' || repeat('11', 28), 'hex'), decode('01' || repeat('22', 28), 'hex'),
                        decode('01' || repeat('33', 28), 'hex'), 'CRM-SEED-1', ?, TIMESTAMPTZ '2026-09-01 00:00:00+09')
                """, tenant, customerRef, SEED_KEY_ID);
    }

    /** 부모가 서명 가능 상태(SEALED·PARTIALLY_SIGNED)면 부모의 두 해시를 고정한 고객 OPEN 세션을 발급한다(V8 GD101). 아니면 null. */
    public static UUID openSession(Connection c, String tenant, UUID disclosure) throws SQLException {
        UUID session = UUID.randomUUID();
        int inserted = exec(c, """
                INSERT INTO sign_session (tenant_id, session_id, disclosure_id, signer_role, channel, token_hash, expires_at, status,
                                          issued_by, issued_at, signed_doc_hash, signed_pdf_hash)
                SELECT d.tenant_id, ?, d.disclosure_id, 'CUSTOMER', 'TOUCH_PAD', encode(sha256(convert_to(?::text, 'UTF8')), 'hex'),
                       TIMESTAMPTZ '2026-09-23 10:30:00+09', 'OPEN', 'agent@seed', TIMESTAMPTZ '2026-09-23 10:01:00+09',
                       d.canonical_hash, d.pdf_hash
                  FROM disclosure d
                 WHERE d.tenant_id = ? AND d.disclosure_id = ? AND d.status IN ('SEALED', 'PARTIALLY_SIGNED')
                """, session, tenant + "~seed-token-" + session, tenant, disclosure);
        return inserted == 1 ? session : null;
    }

    /** 원격 링크 OPEN 세션 — 토큰은 발송 때 생기므로 {@code token_hash} NULL(V12, 6A 승인 Q3). 부모가 서명 가능 상태가 아니면 null. */
    public static UUID remoteSession(Connection c, String tenant, UUID disclosure) throws SQLException {
        UUID session = UUID.randomUUID();
        int inserted = exec(c, """
                INSERT INTO sign_session (tenant_id, session_id, disclosure_id, signer_role, channel, token_hash, expires_at, status,
                                          issued_by, issued_at, signed_doc_hash, signed_pdf_hash)
                SELECT d.tenant_id, ?, d.disclosure_id, 'CUSTOMER', 'REMOTE_LINK', NULL,
                       TIMESTAMPTZ '2026-09-26 10:00:00+09', 'OPEN', 'agent@seed', TIMESTAMPTZ '2026-09-23 10:01:00+09',
                       d.canonical_hash, d.pdf_hash
                  FROM disclosure d
                 WHERE d.tenant_id = ? AND d.disclosure_id = ? AND d.status IN ('SEALED', 'PARTIALLY_SIGNED')
                """, session, tenant, disclosure);
        return inserted == 1 ? session : null;
    }

    /** 서명 링크 통지 1건(V12, PENDING). 수신자는 실재하는 고객 가명이어야 한다(FK). */
    public static UUID notification(Connection c, String tenant, String customerRef, UUID session) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, """
                INSERT INTO notification_outbox (tenant_id, notification_id, kind, customer_ref, session_id, status, next_attempt_at, created_at)
                VALUES (?, ?, 'SIGN_LINK', ?, ?, 'PENDING', TIMESTAMPTZ '2026-09-23 10:01:00+09', TIMESTAMPTZ '2026-09-23 10:01:00+09')
                """, tenant, id, customerRef, session);
        return id;
    }

    /** QUEUED 작업 1건(V12 async_job). */
    public static UUID asyncJob(Connection c, String tenant, String kind) throws SQLException {
        UUID id = UUID.randomUUID();
        exec(c, """
                INSERT INTO async_job (tenant_id, job_id, kind, status, requested_by, channel, requested_at)
                VALUES (?, ?, ?, 'QUEUED', 'scheduler@seed', 'HTTP', TIMESTAMPTZ '2026-10-01 00:00:00+09')
                """, tenant, id, kind);
        return id;
    }

    /** 진행 중 Idempotency-Key 1건(V12). 만료는 {@code expiresAt}, 생성·청구는 그 하루 전. */
    public static void idempotencyKey(Connection c, String tenant, String subject, String key, String expiresAt) throws SQLException {
        exec(c, """
                INSERT INTO idempotency_key (tenant_id, actor_subject, idem_key, request_hash, claimed_at, created_at, expires_at)
                SELECT ?, ?, ?, repeat('0', 64), e - INTERVAL '1 day', e - INTERVAL '1 day', e
                  FROM (SELECT CAST(? AS timestamptz) AS e) x
                """, tenant, subject, key, expiresAt);
    }

    /** 서명자 집합 검사(V8 GD104)가 읽는 고정 GLOBAL 룰 본문(JCS 정렬). */
    public static final String SIGNER_RULE_BODY = "{\"managerConfirmMode\":\"REQUIRED\",\"signerSet\":[\"CUSTOMER\",\"AGENT\",\"MANAGER\"]}";

    /**
     * 시드 고정 룰 {@code DISC-2026-07}(GLOBAL·APPROVED, 본문 = {@link #SIGNER_RULE_BODY})이 없으면 넣는다. 활성화하지 않는다 — 같은 테넌트의
     * 다른 ACTIVE GLOBAL 룰과 기간 배타 제약이 부딪히지 않게(서명자 집합 검사는 상태를 보지 않는다).
     */
    public static void signerRule(Connection c, String tenant) throws SQLException {
        exec(c, """
                INSERT INTO rule_version (tenant_id, rule_version_id, scope, apply_from, status, approved_by, approved_at, body,
                                          source_bundle_id, bundle_hash)
                VALUES (?, 'DISC-2026-07', 'GLOBAL', DATE '2026-07-01', 'APPROVED', 'OPERATOR:seed', TIMESTAMPTZ '2026-06-30 09:00:00+09',
                        CAST(? AS jsonb), 'DISC-2026-07@' || substr(?, 1, 12), ?)
                ON CONFLICT (tenant_id, rule_version_id) DO NOTHING
                """, tenant, SIGNER_RULE_BODY, hashOf(SIGNER_RULE_BODY), hashOf(SIGNER_RULE_BODY));
    }

    /** UTF-8 바이트의 SHA-256 소문자 hex. */
    public static String hashOf(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 봉인된 확인서의 문서 키 ID(시드 규약: {@code DOC-} + 확인서 UUID hex). */
    public static String documentKeyId(UUID disclosure) {
        return "DOC-" + disclosure.toString().replace("-", "");
    }

    /** 문서 키 1건(감싼 키 자리값 = 형식 머리 0x01 + 28바이트, V7 — 부모는 봉인돼 있어야 한다, GD092). */
    public static void documentKey(Connection c, String tenant, UUID disclosure) throws SQLException {
        exec(c, """
                INSERT INTO document_key (tenant_id, key_id, disclosure_id, kek_key_id, wrapped_dek, created_at)
                VALUES (?, ?, ?, 'KEK-SEED', decode('01' || repeat('00', 28), 'hex'), TIMESTAMPTZ '2026-09-23 10:00:00+09')
                """, tenant, documentKeyId(disclosure), disclosure);
    }

    /** 산출물 행 1건(V7 형식: 평문·암호문 해시, 길이 + 29, 객체 키 = 테넌트/확인서/종류/암호문 해시). 문서 키가 없으면 만든다. */
    public static void artifact(Connection c, String tenant, UUID disclosure, String kind) throws SQLException {
        if (longValue(c, "SELECT count(*) FROM document_key WHERE tenant_id = ? AND disclosure_id = ?", tenant, disclosure) == 0) {
            documentKey(c, tenant, disclosure);
        }
        String cipher = hash('9');
        exec(c, """
                INSERT INTO document_artifact (tenant_id, disclosure_id, kind, storage_key, sha256, bytes, created_at, cipher_sha256,
                                               cipher_bytes, key_id)
                VALUES (?, ?, ?, ?, ?, 10, TIMESTAMPTZ '2026-09-23 10:00:00+09', ?, 39, ?)
                """, tenant, disclosure, kind, tenant + "/" + disclosure + "/" + kind + "/" + cipher, hash('f'), cipher,
                documentKeyId(disclosure));
    }

    public static void auditLog(Connection c, String tenant, long seq) throws SQLException {
        exec(c, """
                INSERT INTO audit_log (tenant_id, seq, at, action, prev_hash, entry_hash)
                VALUES (?, ?, TIMESTAMPTZ '2026-09-23 10:00:00+09', 'CREATE', ?, ?)
                """, tenant, seq, hash('0'), hash('1'));
    }

    /** 첫 앵커(V9): 봉인 없음(0·ZERO), 감사 seq 1(시드 {@link #auditLog}의 머리 {@code hash('1')}), 잎은 DB 식 그대로. */
    public static void anchor(Connection c, String tenant) throws SQLException {
        exec(c, """
                INSERT INTO anchor (tenant_id, anchor_seq, anchor_date, seal_chain_seq, seal_chain_head, audit_seq, audit_head, leaf_hash, created_at)
                VALUES (?, 1, DATE '2026-09-24', 0, repeat('0', 64), 1, ?,
                        ga_anchor_leaf(?, 1, DATE '2026-09-24', 0, repeat('0', 64), 1, ?), TIMESTAMPTZ '2026-09-24 00:00:05+09')
                """, tenant, hash('1'), tenant, hash('1'));
    }

    /** 첫 앵커의 영수증(깊이 1, 형제 = 64개 0, 루트 = SHA-256(0x01 ‖ 잎 ‖ 형제) — DB GD111이 다시 계산한다). 토큰은 자리값. */
    public static void anchorReceipt(Connection c, String tenant) throws SQLException {
        exec(c, """
                INSERT INTO anchor_receipt (tenant_id, anchor_seq, batch_id, root_hash, tree_depth, leaf_index, merkle_path, tsa_token,
                                            tsa_gen_time, tsa_policy_oid, tsa_serial, created_at)
                SELECT a.tenant_id, a.anchor_seq, gen_random_uuid(),
                       encode(sha256('\\x01'::bytea || decode(a.leaf_hash, 'hex') || decode(repeat('0', 64), 'hex')), 'hex'),
                       1, 0, jsonb_build_array(repeat('0', 64)), decode('3000', 'hex'),
                       TIMESTAMPTZ '2026-09-24 00:00:07+09', '1.2.3.4', '01', TIMESTAMPTZ '2026-09-24 00:00:08+09'
                  FROM anchor a
                 WHERE a.tenant_id = ? AND a.anchor_seq = 1
                """, tenant);
    }

    /**
     * 문서 키 파기의 효과만 만든다(V9 트리거가 허용하는 유일한 경로 — 정의자 롤 + 함수 표식 — 를 마이그레이터 연결이 직접 밟는다).
     * 판정 조건(종료 상태·보존 만료·보류)을 거치는 실제 경로 {@code ga_document_key_shred}는 {@code DestroyerRoleIT}가 본다.
     * 암호화 테스트처럼 "키가 없어진 뒤"만 필요한 곳에서 쓴다.
     */
    public static void shredDocumentKey(Connection c, String tenant, java.util.UUID disclosureId) throws SQLException {
        exec(c, "SET LOCAL ROLE disclosure_destroy_definer");
        call(c, "SELECT set_config('ga.destroy', 'key:' || ?, true)", disclosureId.toString());
        exec(c, """
                UPDATE document_key SET wrapped_dek = NULL, shredded_at = TIMESTAMPTZ '2031-09-24 00:00:00+09', shredded_by = 'RETENTION:test'
                 WHERE tenant_id = ? AND disclosure_id = ? AND wrapped_dek IS NOT NULL
                """, tenant, disclosureId);
        call(c, "SELECT set_config('ga.destroy', '', true)");
        exec(c, "RESET ROLE");
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

    /** 서명 증거 객체 행 1건(V8 형식, 문서 키는 산출물 시드가 만든 것). */
    public static void signatureEvidence(Connection c, String tenant, UUID disclosure, UUID signatureId, String kind) throws SQLException {
        String cipher = hash('c');
        exec(c, """
                INSERT INTO signature_evidence (tenant_id, signature_id, kind, disclosure_id, storage_key, sha256, bytes, cipher_sha256,
                                                cipher_bytes, key_id, created_at)
                VALUES (?, ?, ?, ?, ? || '/' || ? || '/SIG/' || ? || '/' || ? || '/' || ?, ?, 10, ?, 39, ?, TIMESTAMPTZ '2026-09-23 10:05:00+09')
                """, tenant, signatureId, kind, disclosure, tenant, disclosure.toString(), signatureId.toString(), kind, cipher, hash('d'),
                cipher, documentKeyId(disclosure));
    }

    /** 아웃박스 이벤트 1건 + 머리 이동(V8: seq = 머리 + 1, 머리는 실재 이벤트를 가리킨다). */
    public static void outboxEvent(Connection c, String tenant, UUID disclosure) throws SQLException {
        long next = longValue(c, "SELECT coalesce((SELECT seq FROM outbox_head WHERE tenant_id = ?), 0) + 1", tenant);
        exec(c, """
                INSERT INTO outbox_event (tenant_id, seq, event_id, type, version, occurred_at, aggregate_kind, aggregate_id, payload)
                VALUES (?, ?, gen_random_uuid(), 'DisclosureSealed', 1, TIMESTAMPTZ '2026-09-23 10:00:00+09', 'DISCLOSURE', ?, '{}'::jsonb)
                """, tenant, next, disclosure.toString());
        if (next == 1) {
            exec(c, "INSERT INTO outbox_head (tenant_id, seq) VALUES (?, 1)", tenant);
        } else {
            exec(c, "UPDATE outbox_head SET seq = ? WHERE tenant_id = ?", next, tenant);
        }
    }

    /** 테넌트 테이블 전부(V12 기준 32개)에 한 행 이상을 넣는다(RLS 격리 검증용). 봉인 시드가 카운터·체인 머리를, 산출물 시드가 문서 키를 만든다. */
    public static void everyTable(Connection c, String tenant) throws SQLException {
        tenant(c, tenant);
        exec(c, "INSERT INTO identity_link (tenant_id, subject, agent_id, roles, org_path) VALUES (?, 'sub-1', 'AGENT-1', ARRAY['AGENT'], '/HQ/B1')", tenant);
        exec(c, """
                INSERT INTO rule_version (tenant_id, rule_version_id, scope, apply_from, status, approved_by, approved_at, body,
                                          source_bundle_id, bundle_hash)
                VALUES (?, 'DISC-2026-07', 'GLOBAL', DATE '2026-07-01', 'APPROVED', 'OPERATOR:seed', TIMESTAMPTZ '2026-06-30 09:00:00+09',
                        CAST(? AS jsonb), 'DISC-2026-07@' || substr(?, 1, 12), ?)
                """, tenant, SIGNER_RULE_BODY, hashOf(SIGNER_RULE_BODY), hashOf(SIGNER_RULE_BODY));
        // GLOBAL 복제본은 APPROVED로만 들어가고(V4 GD040) 활성화는 한 단계 전진이다.
        exec(c, "UPDATE rule_version SET status = 'ACTIVE' WHERE tenant_id = ? AND rule_version_id = 'DISC-2026-07'", tenant);
        exec(c, """
                INSERT INTO form_template (tenant_id, template_id, template_type, version, apply_from, fields, layout)
                VALUES (?, 'STANDARD', 'STANDARD', 1, DATE '2026-07-01', '[]'::jsonb, '{}'::jsonb)
                """, tenant);
        exec(c, """
                INSERT INTO product_group (tenant_id, group_code, name, line, apply_from, source, source_ref, synced_at)
                VALUES (?, 'PG-HEALTH', '건강', 'LIFE', DATE '2026-01-01', 'SEED', 'seed.json@sha256:' || repeat('0', 64),
                        TIMESTAMPTZ '2026-09-01 00:00:00+09')
                """, tenant);
        exec(c, """
                INSERT INTO product_catalog (tenant_id, product_key, insurer_code, group_code, product_name, sale_from, defaults, source,
                                             source_ref, synced_at)
                VALUES (?, 'INS-A:PRD-1', 'INS-A', 'PG-HEALTH', '상품', DATE '2026-01-01', '{}'::jsonb, 'SEED',
                        'seed.json@sha256:' || repeat('0', 64), TIMESTAMPTZ '2026-09-01 00:00:00+09')
                """, tenant);
        exec(c, """
                INSERT INTO insurer_panel (tenant_id, insurer_code, insurer_name, line, active_from, source, source_ref, synced_at)
                VALUES (?, 'INS-A', '보험사A', 'LIFE', DATE '2026-01-01', 'SEED', 'seed.json@sha256:' || repeat('0', 64),
                        TIMESTAMPTZ '2026-09-01 00:00:00+09')
                """, tenant);
        exec(c, """
                INSERT INTO catalog_import (tenant_id, import_id, kind, file_name, file_sha256, source, as_of, imported_at,
                                            inserted, updated, closed, unchanged)
                VALUES (?, ?, 'PRODUCTS', 'seed.json', repeat('0', 64), 'SEED', DATE '2026-09-01', TIMESTAMPTZ '2026-09-01 00:00:00+09',
                        1, 0, 0, 0)
                """, tenant, UUID.randomUUID());
        dataKey(c, tenant, SEED_KEY_ID);
        // 암호문 자리: 형식 머리(0x01) + 28바이트. 실제 암호화는 infra.crypto가 하고 이 행은 RLS 격리 검증용이다.
        exec(c, """
                INSERT INTO customer_ref (tenant_id, customer_ref, name_enc, enc_key_id, created_at)
                VALUES (?, ?, decode('01' || repeat('00', 28), 'hex'), ?, TIMESTAMPTZ '2026-09-01 00:00:00+09')
                """, tenant, SEED_CUSTOMER_REF, SEED_KEY_ID);
        UUID draft = disclosure(c, tenant, "DRAFT", null);
        item(c, tenant, draft, 1, "0.84");
        recommendation(c, tenant, draft, 1);
        review(c, tenant, draft, "R-TEMP-PRODUCT");
        UUID sealed = disclosure(c, tenant, "SEALED", hash('a'));
        artifact(c, tenant, sealed, "PDF");
        UUID signatureId = signature(c, tenant, sealed, "CUSTOMER", hash('a'));
        signatureEvidence(c, tenant, sealed, signatureId, "STROKES");
        outboxEvent(c, tenant, sealed);
        auditLog(c, tenant, 1);
        anchor(c, tenant);
        anchorReceipt(c, tenant);
        exec(c, """
                INSERT INTO legal_hold (tenant_id, hold_id, disclosure_id, reason_code, placed_by, placed_at)
                VALUES (?, ?, ?, 'LITIGATION', 'compliance@seed', TIMESTAMPTZ '2026-10-01 00:00:00+09')
                """, tenant, UUID.randomUUID(), sealed);
        exec(c, """
                INSERT INTO subject_policy (tenant_id, policy_no, agent_id, contract_date, required, recon_status)
                VALUES (?, 'POL-1', 'AGENT-1', DATE '2026-09-30', true, 'MISSING')
                """, tenant);
        exec(c, """
                INSERT INTO compliance_flag (tenant_id, flag_id, type, severity, raised_at)
                VALUES (?, ?, 'MISSING', 'HIGH', TIMESTAMPTZ '2026-10-01 00:00:00+09')
                """, tenant, UUID.randomUUID());
        // V12
        notification(c, tenant, SEED_CUSTOMER_REF, remoteSession(c, tenant, sealed));      // 고객 서명의 세션은 USED라 OPEN이 비어 있다
        asyncJob(c, tenant, "VERIFY_TENANT");
        idempotencyKey(c, tenant, "sub-1", "seed-idempotency-0001", "2026-09-24 10:00:00+09");
    }

    /** 관리자 예외 승인 1건(V6 review, 부모는 가변 상태여야 한다 — GD080; 부모의 고정 룰 버전 2종을 싣는다 — V7 GD081). */
    public static void review(Connection c, String tenant, UUID disclosure, String ruleId) throws SQLException {
        exec(c, """
                INSERT INTO review (tenant_id, review_id, disclosure_id, rule_id, subject_hash, approved_by, approved_role, approved_at, reason,
                                    rule_version_id, tenant_rule_version_id)
                SELECT ?, ?, d.disclosure_id, ?, repeat('0', 64), 'manager@seed', 'MANAGER', TIMESTAMPTZ '2026-09-23 11:00:00+09', '시드 승인',
                       d.rule_version_id, d.tenant_rule_version_id
                  FROM disclosure d
                 WHERE d.tenant_id = ? AND d.disclosure_id = ?
                """, tenant, UUID.randomUUID(), ruleId, tenant, disclosure);
    }

    /** 감싼 키 자리값(32바이트 0)을 가진 ACTIVE 데이터 키. 암호화 IT는 실제 키 저장소 어댑터로 만든다. */
    public static void dataKey(Connection c, String tenant, String keyId) throws SQLException {
        exec(c, """
                INSERT INTO customer_data_key (tenant_id, key_id, kek_id, wrapped_key, status, created_at)
                VALUES (?, ?, 'KEK-SEED', decode(repeat('00', 32), 'hex'), 'ACTIVE', TIMESTAMPTZ '2026-09-01 00:00:00+09')
                """, tenant, keyId);
    }

    public static long longValue(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("no row: " + sql);
                }
                return rs.getLong(1);
            }
        }
    }

    /** 결과 집합을 돌려주는 문장(SELECT 함수 호출 등)을 실행하고 첫 행 첫 열을 문자열로 돌려준다(없으면 null). */
    public static String call(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
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
