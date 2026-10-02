package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static com.ga.disclosure.infra.TriggerAssertions.assertAllowed;
import static com.ga.disclosure.infra.TriggerAssertions.assertRejected;
import static com.ga.disclosure.infra.TriggerAssertions.sqlStateOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V8 GD101: 고객 서명 세션은 서명 가능한 확인서에만 발급되고 그때의 두 해시를 고정한다. OPEN으로 깨끗하게 시작하고, OPEN 동안에는
 * 본인확인 실패 +1·통과 수단 추가·열람 증거 1회·발송 시각 1회만 바뀌며, OPEN을 떠나면(USED·EXPIRED·REVOKED) 더는 바뀌지 않는다.
 * 행은 지우지 않는다(애플리케이션 롤은 DELETE 권한이 없고 소유 롤은 트리거가 막는다).
 */
class SignSessionGuardIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("SES");
    private static final String CANONICAL = SeedData.hash('a');

    static final List<String> STATUSES = SeedData.ALL_STATUSES;

    @BeforeAll
    static void seed() {
        DB.seed(T, c -> SeedData.tenant(c, T));
    }

    private static UUID disclosure(String status) {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> id[0] = SeedData.disclosure(c, T, status, CANONICAL));
        return id[0];
    }

    private static UUID open(String status) {
        UUID id = disclosure(status);
        UUID[] s = new UUID[1];
        DB.seed(T, c -> s[0] = SeedData.openSession(c, T, id));
        return s[0];
    }

    /** 발급: 채널·초기값만 바꿔 가며 쓴다(부모 해시는 SELECT로 고정). */
    private static String issue(String channel, String status, String extraColumns, String extraValues) {
        return """
                INSERT INTO sign_session (tenant_id, session_id, disclosure_id, signer_role, channel, token_hash, expires_at, status,
                                          issued_by, issued_at, signed_doc_hash, signed_pdf_hash%s)
                SELECT tenant_id, gen_random_uuid(), disclosure_id, 'CUSTOMER', '%s', encode(sha256(gen_random_uuid()::text::bytea), 'hex'),
                       TIMESTAMPTZ '2026-09-23 10:30:00+09', '%s', 'agent@seed', TIMESTAMPTZ '2026-09-23 10:01:00+09',
                       canonical_hash, pdf_hash%s
                  FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?
                """.formatted(extraColumns, channel, status, extraValues);
    }

    // ------------------------------------------------------------------ 발급

    @ParameterizedTest
    @FieldSource("STATUSES")
    void issuedOnlyForSignableDisclosures(String status) {
        UUID id = disclosure(status);
        String sql = issue("TOUCH_PAD", "OPEN", "", "");
        if (status.equals("SEALED") || status.equals("PARTIALLY_SIGNED")) {
            assertAllowed(DB, T, sql, T, id);
        } else {
            assertRejected(DB, T, "GD101", sql, T, id);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"signed_doc_hash", "signed_pdf_hash"})
    void issuancePinsTheParentHashes(String column) {
        UUID id = disclosure("SEALED");
        String sql = issue("TOUCH_PAD", "OPEN", "", "").replace("canonical_hash, pdf_hash",
                column.equals("signed_doc_hash") ? "repeat('d', 64), pdf_hash" : "canonical_hash, repeat('d', 64)");
        assertRejected(DB, T, "GD101", sql, T, id);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "status|'USED'",
            "identity_failures|1",
            "identity_passed|ARRAY['BIRTH_DATE']",
            "view_evidence|'{\"pages\": 2}'::jsonb",
            "sent_at|TIMESTAMPTZ '2026-09-23 10:02:00+09'"})
    void issuedOpenAndClean(String spec) {
        String[] p = spec.split("\\|");
        UUID id = disclosure("SEALED");
        String sql = p[0].equals("status")
                ? issue("REMOTE_LINK", "USED", ", used_at", ", TIMESTAMPTZ '2026-09-23 10:02:00+09'")
                : issue("REMOTE_LINK", "OPEN", ", " + p[0], ", " + p[1]);
        assertRejected(DB, T, "GD101", sql, T, id);
    }

    @Test
    void oneOpenSessionPerDisclosureAndRole() {
        UUID id = disclosure("SEALED");
        DB.seed(T, c -> SeedData.openSession(c, T, id));
        assertRejected(DB, T, "23505", issue("TOUCH_PAD", "OPEN", "", ""), T, id);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "channel|CERTIFIED_ESIGN",                // v2
            "channel|SSO",                            // 직원은 세션 없이 서명
            "role|AGENT"})
    void closedVocabularies(String spec) {
        String[] p = spec.split("\\|");
        UUID id = disclosure("SEALED");
        String sql = p[0].equals("channel") ? issue(p[1], "OPEN", "", "") : issue("TOUCH_PAD", "OPEN", "", "").replace("'CUSTOMER'", "'" + p[1] + "'");
        assertRejected(DB, T, "23514", sql, T, id);
    }

    // ------------------------------------------------------------------ OPEN 동안의 변경

    static final List<String> FIXED = List.of(
            "session_id = gen_random_uuid()",
            "disclosure_id = gen_random_uuid()",
            "channel = 'REMOTE_LINK'",
            "token_hash = repeat('e', 64)",
            "issued_by = 'other@seed'",
            "issued_at = issued_at - INTERVAL '1 minute'",
            "expires_at = expires_at + INTERVAL '1 day'",
            "signed_doc_hash = repeat('e', 64)",
            "signed_pdf_hash = repeat('e', 64)");

    @ParameterizedTest
    @FieldSource("FIXED")
    void pinnedColumnsNeverChange(String set) {
        UUID s = open("SEALED");
        assertRejected(DB, T, "GD101", "UPDATE sign_session SET " + set + " WHERE tenant_id = ? AND session_id = ?", T, s);
    }

    @Test
    void openSessionCountsFailuresByOneAndOnlyAddsPassedMethods() {
        UUID s = open("SEALED");
        String where = " WHERE tenant_id = ? AND session_id = ?";
        assertRejected(DB, T, "GD101", "UPDATE sign_session SET identity_failures = 2" + where, T, s);
        DB.asAppCommitting(T, c -> SeedData.exec(c, "UPDATE sign_session SET identity_failures = identity_failures + 1" + where, T, s));
        assertRejected(DB, T, "GD101", "UPDATE sign_session SET identity_failures = 0" + where, T, s);
        DB.asAppCommitting(T, c -> SeedData.exec(c, "UPDATE sign_session SET identity_passed = ARRAY['LINK_POSSESSION']" + where, T, s));
        assertRejected(DB, T, "GD101", "UPDATE sign_session SET identity_passed = ARRAY['BIRTH_DATE']" + where, T, s);
        assertRejected(DB, T, "23514", "UPDATE sign_session SET identity_passed = ARRAY['LINK_POSSESSION', 'FACE_ID']" + where, T, s);
        assertAllowed(DB, T, "UPDATE sign_session SET identity_passed = ARRAY['LINK_POSSESSION', 'BIRTH_DATE']" + where, T, s);
    }

    @Test
    void viewEvidenceAndSendTimeAreWrittenOnce() {
        UUID id = disclosure("SEALED");
        UUID s = UUID.randomUUID();
        DB.seed(T, c -> SeedData.exec(c, issue("REMOTE_LINK", "OPEN", "", "").replace("gen_random_uuid(), disclosure_id", "?, disclosure_id"),
                s, T, id));
        String where = " WHERE tenant_id = ? AND session_id = ?";
        DB.asAppCommitting(T, c -> SeedData.exec(c, "UPDATE sign_session SET view_evidence = '{\"pages\": 2}', "
                + "sent_at = TIMESTAMPTZ '2026-09-23 10:02:00+09'" + where, T, s));
        assertRejected(DB, T, "GD101", "UPDATE sign_session SET view_evidence = '{\"pages\": 3}'" + where, T, s);
        assertRejected(DB, T, "GD101", "UPDATE sign_session SET view_evidence = NULL" + where, T, s);
        assertRejected(DB, T, "GD101", "UPDATE sign_session SET sent_at = TIMESTAMPTZ '2026-09-23 10:03:00+09'" + where, T, s);
    }

    @Test
    void sendTimeOnlyForRemoteLinks() {
        UUID s = open("SEALED");     // TOUCH_PAD
        assertRejected(DB, T, "23514", "UPDATE sign_session SET sent_at = TIMESTAMPTZ '2026-09-23 10:02:00+09' WHERE tenant_id = ? AND session_id = ?",
                T, s);
    }

    // ------------------------------------------------------------------ OPEN을 떠나면 끝

    static final List<String> CLOSES = List.of(
            "status = 'USED', used_at = TIMESTAMPTZ '2026-09-23 10:05:00+09'",
            "status = 'EXPIRED'",
            "status = 'REVOKED', revoked_at = TIMESTAMPTZ '2026-09-23 10:05:00+09', revoke_reason = 'DOCUMENT_VOIDED'");

    @ParameterizedTest
    @FieldSource("CLOSES")
    void closedSessionsNeverChangeAgain(String close) {
        UUID s = open("SEALED");
        String where = " WHERE tenant_id = ? AND session_id = ?";
        DB.asAppCommitting(T, c -> SeedData.exec(c, "UPDATE sign_session SET " + close + where, T, s));
        assertRejected(DB, T, "GD101", "UPDATE sign_session SET status = 'OPEN', used_at = NULL, revoked_at = NULL, revoke_reason = NULL" + where, T, s);
        assertRejected(DB, T, "GD101", "UPDATE sign_session SET identity_failures = identity_failures + 1" + where, T, s);
        assertRejected(DB, T, "GD101", "UPDATE sign_session SET " + close + where, T, s);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "status = 'USED'",                                                               // 사용 시각 없음
            "used_at = TIMESTAMPTZ '2026-09-23 10:05:00+09'",                                 // OPEN에 사용 시각
            "status = 'REVOKED', revoked_at = TIMESTAMPTZ '2026-09-23 10:05:00+09'",          // 사유 없음
            "status = 'REVOKED', revoked_at = TIMESTAMPTZ '2026-09-23 10:05:00+09', revoke_reason = 'BORED'",
            "status = 'PAUSED'"})
    void closingColumnsMatchTheStatus(String set) {
        UUID s = open("SEALED");
        assertRejected(DB, T, "23514", "UPDATE sign_session SET " + set + " WHERE tenant_id = ? AND session_id = ?", T, s);
    }

    // ------------------------------------------------------------------ 삭제 없음

    @Test
    void sessionsAreNeverRemoved() {
        UUID s = open("SEALED");
        String delete = "DELETE FROM sign_session WHERE tenant_id = ? AND session_id = ?";
        assertRejected(DB, T, "42501", delete, T, s);
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, delete, T, s)))).isEqualTo("GD101");
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, "TRUNCATE sign_session CASCADE")))).isEqualTo("GD101");
    }
}
