package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Stream;

import static com.ga.disclosure.infra.TriggerAssertions.assertAllowed;
import static com.ga.disclosure.infra.TriggerAssertions.assertRejected;

/**
 * G1(V8, DB 층): 서명은 두 해시에 귀속된다 — 상태 10종 × 문서 해시 일치·불일치 × PDF 해시 일치·불일치 전수(GD021·GD022·GD102).
 * 고객 서명은 같은 확인서·역할의 OPEN 세션을 거치고 세션이 고정한 해시와 같아야 한다(GD103). 역할은 고정 GLOBAL 룰의 signerSet에
 * 있어야 한다(GD104, OPTIONAL의 MANAGER는 허용). 열 조합(누가·어떤 경로·어떤 행위)은 CHECK(23514).
 */
class SignatureBindingIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("SIG");
    private static final String CANONICAL = SeedData.hash('a');
    private static final String OTHER = SeedData.hash('d');

    @BeforeAll
    static void seed() {
        DB.seed(T, c -> {
            SeedData.tenant(c, T);
            SeedData.signerRule(c, T);
            rule(c, "DISC-OFF", "{\"managerConfirmMode\":\"OFF\",\"signerSet\":[\"CUSTOMER\",\"AGENT\"]}");
            rule(c, "DISC-OPTIONAL", "{\"managerConfirmMode\":\"OPTIONAL\",\"signerSet\":[\"CUSTOMER\",\"AGENT\"]}");
        });
    }

    private static void rule(Connection c, String id, String body) throws SQLException {
        SeedData.exec(c, """
                INSERT INTO rule_version (tenant_id, rule_version_id, scope, apply_from, status, approved_by, approved_at, body,
                                          source_bundle_id, bundle_hash)
                VALUES (?, ?, 'GLOBAL', DATE '2026-07-01', 'APPROVED', 'OPERATOR:seed', TIMESTAMPTZ '2026-06-30 09:00:00+09', CAST(? AS jsonb),
                        ? || '@' || substr(?, 1, 12), ?)
                """, T, id, body, id, SeedData.hashOf(body), SeedData.hashOf(body));
    }

    private static UUID disclosure(String status) {
        return disclosure(status, "DISC-2026-07");
    }

    private static UUID disclosure(String status, String rule) {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> id[0] = SeedData.disclosure(c, T, status, CANONICAL, rule));
        return id[0];
    }

    private static UUID session(UUID disclosure) {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> id[0] = SeedData.openSession(c, T, disclosure));
        return id[0];
    }

    private static final String AGENT = """
            INSERT INTO signature (tenant_id, signature_id, disclosure_id, signer_role, signer_subject, channel, method, signed_doc_hash,
                                   signed_pdf_hash, identity_check, signed_at)
            VALUES (?, gen_random_uuid(), ?, ?, 'sub@seed', 'SSO', 'SSO_APPROVAL', ?, ?, '[]'::jsonb, TIMESTAMPTZ '2026-09-23 10:06:00+09')
            """;

    private static final String CUSTOMER = """
            INSERT INTO signature (tenant_id, signature_id, disclosure_id, signer_role, channel, method, signed_doc_hash, signed_pdf_hash,
                                   session_id, identity_check, signed_at)
            VALUES (?, gen_random_uuid(), ?, 'CUSTOMER', 'TOUCH_PAD', 'DRAWN', ?, ?, ?, '[]'::jsonb, TIMESTAMPTZ '2026-09-23 10:06:00+09')
            """;

    static Stream<Arguments> bindingCombinations() {
        return SeedData.ALL_STATUSES.stream().flatMap(s -> Stream.of(true, false).flatMap(doc ->
                Stream.of(true, false).map(pdf -> Arguments.of(s, doc, pdf))));
    }

    @ParameterizedTest(name = "{0} doc={1} pdf={2}")
    @MethodSource("bindingCombinations")
    void signatureBindsBothHashesOnlyOnSignableStatuses(String status, boolean docMatches, boolean pdfMatches) {
        UUID id = disclosure(status);
        Object[] params = {T, id, "AGENT", docMatches ? CANONICAL : OTHER, pdfMatches ? SeedData.PDF_HASH : OTHER};
        boolean signable = status.equals("SEALED") || status.equals("PARTIALLY_SIGNED");
        if (!signable) {
            assertRejected(DB, T, "GD021", AGENT, params);
        } else if (!docMatches) {
            assertRejected(DB, T, "GD022", AGENT, params);
        } else if (!pdfMatches) {
            assertRejected(DB, T, "GD102", AGENT, params);
        } else {
            assertAllowed(DB, T, AGENT, params);
        }
    }

    // ------------------------------------------------------------------ 세션(GD103)

    @ParameterizedTest
    @ValueSource(strings = {"SEALED", "PARTIALLY_SIGNED"})
    void customerSignsThroughItsOpenSession(String status) {
        UUID id = disclosure(status);
        assertAllowed(DB, T, CUSTOMER, T, id, CANONICAL, SeedData.PDF_HASH, session(id));
    }

    @Test
    void customerWithoutSessionViolatesTheWhoCheck() {
        UUID id = disclosure("SEALED");
        assertRejected(DB, T, "23514", CUSTOMER, T, id, CANONICAL, SeedData.PDF_HASH, null);
    }

    @Test
    void sessionOfAnotherDisclosureRejected() {
        UUID id = disclosure("SEALED");
        UUID other = disclosure("SEALED");
        assertRejected(DB, T, "GD103", CUSTOMER, T, id, CANONICAL, SeedData.PDF_HASH, session(other));
    }

    @Test
    void unknownSessionRejected() {
        UUID id = disclosure("SEALED");
        assertRejected(DB, T, "GD103", CUSTOMER, T, id, CANONICAL, SeedData.PDF_HASH, UUID.randomUUID());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "status = 'USED', used_at = TIMESTAMPTZ '2026-09-23 10:05:00+09'",
            "status = 'EXPIRED'",
            "status = 'REVOKED', revoked_at = TIMESTAMPTZ '2026-09-23 10:05:00+09', revoke_reason = 'REISSUED'"})
    void closedSessionRejected(String close) {
        UUID id = disclosure("SEALED");
        UUID s = session(id);
        DB.seed(T, c -> SeedData.exec(c, "UPDATE sign_session SET " + close + " WHERE tenant_id = ? AND session_id = ?", T, s));
        assertRejected(DB, T, "GD103", CUSTOMER, T, id, CANONICAL, SeedData.PDF_HASH, s);
    }

    @Test
    void agentCannotUseACustomerSession() {
        UUID id = disclosure("SEALED");
        UUID s = session(id);
        assertRejected(DB, T, "GD103", """
                INSERT INTO signature (tenant_id, signature_id, disclosure_id, signer_role, signer_subject, channel, method, signed_doc_hash,
                                       signed_pdf_hash, session_id, identity_check, signed_at)
                VALUES (?, gen_random_uuid(), ?, 'AGENT', 'sub@seed', 'SSO', 'DRAWN', ?, ?, ?, '[]'::jsonb, TIMESTAMPTZ '2026-09-23 10:06:00+09')
                """, T, id, CANONICAL, SeedData.PDF_HASH, s);
    }

    /**
     * 세션 고정 해시 ≠ 서명 해시: 정상 경로로는 만들 수 없다(세션 발급이 부모 해시를 고정하고 GD101, 서명은 부모 해시와 같아야 한다).
     * 가드를 우회해 위조된 세션 행을 소유 롤이 넣었다고 가정하고 트리거의 마지막 방어선을 확인한다.
     */
    @Test
    void sessionPinnedToOtherHashesRejected() {
        UUID id = disclosure("SEALED");
        UUID forged = UUID.randomUUID();
        DB.seed(T, c -> {
            SeedData.exec(c, "ALTER TABLE sign_session DISABLE TRIGGER trg_sign_session_guard");
            SeedData.exec(c, """
                    INSERT INTO sign_session (tenant_id, session_id, disclosure_id, signer_role, channel, token_hash, expires_at, status,
                                              issued_by, issued_at, signed_doc_hash, signed_pdf_hash)
                    VALUES (?, ?, ?, 'CUSTOMER', 'TOUCH_PAD', ?, TIMESTAMPTZ '2026-09-23 10:30:00+09', 'OPEN', 'agent@seed',
                            TIMESTAMPTZ '2026-09-23 10:01:00+09', ?, ?)
                    """, T, forged, id, SeedData.hash('7'), CANONICAL, OTHER);
            SeedData.exec(c, "ALTER TABLE sign_session ENABLE TRIGGER trg_sign_session_guard");
        });
        assertRejected(DB, T, "GD103", CUSTOMER, T, id, CANONICAL, SeedData.PDF_HASH, forged);
    }

    // ------------------------------------------------------------------ 서명자 집합(GD104)

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER_AGENT_MANAGER", "OFF", "OPTIONAL", "MISSING"})
    void managerOnlyWhenTheRuleHasIt(String rule) {
        String ruleId = switch (rule) {
            case "OFF" -> "DISC-OFF";
            case "OPTIONAL" -> "DISC-OPTIONAL";
            case "MISSING" -> "DISC-NOT-LOADED";
            default -> "DISC-2026-07";
        };
        UUID manager = disclosure("SEALED", ruleId);
        UUID agent = disclosure("SEALED", ruleId);
        Object[] asManager = {T, manager, "MANAGER", CANONICAL, SeedData.PDF_HASH};
        Object[] asAgent = {T, agent, "AGENT", CANONICAL, SeedData.PDF_HASH};
        if (rule.equals("OFF") || rule.equals("MISSING")) {
            assertRejected(DB, T, "GD104", AGENT, asManager);
        } else {
            assertAllowed(DB, T, AGENT, asManager);
        }
        if (rule.equals("MISSING")) {
            assertRejected(DB, T, "GD104", AGENT, asAgent);
        } else {
            assertAllowed(DB, T, AGENT, asAgent);
        }
    }

    @Test
    void oneSignaturePerRole() {
        UUID id = disclosure("SEALED");
        DB.seed(T, c -> SeedData.exec(c, AGENT, T, id, "AGENT", CANONICAL, SeedData.PDF_HASH));
        assertRejected(DB, T, "23505", AGENT, T, id, "AGENT", CANONICAL, SeedData.PDF_HASH);
    }

    // ------------------------------------------------------------------ 열 조합(CHECK)

    static Stream<Arguments> columnCombinations() {
        // role | subject | channel | method | session? | scan_match | ack | identity | 기대
        return Stream.of(
                Arguments.of("AGENT", "'sub@seed'", "'SSO'", "'DRAWN'", false, "NULL", "'{}'", "'[]'", null),
                Arguments.of("MANAGER", "'sub@seed'", "'SSO'", "'SSO_APPROVAL'", false, "NULL", "ARRAY[gen_random_uuid()]", "'[]'", null),
                Arguments.of("AGENT", "NULL", "'SSO'", "'DRAWN'", false, "NULL", "'{}'", "'[]'", "23514"),            // 직원은 subject
                Arguments.of("AGENT", "'sub@seed'", "'TOUCH_PAD'", "'DRAWN'", false, "NULL", "'{}'", "'[]'", "23514"), // 직원은 SSO 경로
                Arguments.of("AGENT", "'sub@seed'", "'SSO'", "'UPLOADED_SCAN'", false, "NULL", "'{}'", "'[]'", "23514"),
                Arguments.of("AGENT", "'sub@seed'", "'SSO'", "'DRAWN'", false, "NULL", "ARRAY[gen_random_uuid()]", "'[]'", "23514"), // 확인은 관리자만
                Arguments.of("AGENT", "'sub@seed'", "'SSO'", "'DRAWN'", false, "NULL", "'{}'", "'{}'", "23514"),       // 본인확인은 배열
                Arguments.of("AGENT", "'sub@seed'", "'SSO'", "'DRAWN'", false, "NULL", "'{}'", "NULL", "23502"),
                Arguments.of("AGENT", "'sub@seed'", "'SSO'", "'STAMP'", false, "NULL", "'{}'", "'[]'", "23514"),
                Arguments.of("CUSTOMER", "NULL", "'TOUCH_PAD'", "'DRAWN'", true, "NULL", "'{}'", "'[]'", null),
                Arguments.of("CUSTOMER", "'sub@seed'", "'TOUCH_PAD'", "'DRAWN'", true, "NULL", "'{}'", "'[]'", "23514"), // 고객은 subject 없음
                Arguments.of("CUSTOMER", "NULL", "'TOUCH_PAD'", "'SSO_APPROVAL'", true, "NULL", "'{}'", "'[]'", "23514"),
                Arguments.of("CUSTOMER", "NULL", "'TOUCH_PAD'", "'DRAWN'", true, "'{\"pages\": 1}'", "'{}'", "'[]'", "23514")); // 대조는 종이만
    }

    @ParameterizedTest(name = "{0} {1} {2} {3} session={4} scan={5} ack={6} identity={7} → {8}")
    @MethodSource("columnCombinations")
    void whoRouteAndActAreConsistent(String role, String subject, String channel, String method, boolean session, String scan, String ack,
                                     String identity, String expected) {
        UUID id = disclosure("SEALED");
        UUID s = session ? session(id) : null;
        String sql = """
                INSERT INTO signature (tenant_id, signature_id, disclosure_id, signer_role, signer_subject, channel, method, signed_doc_hash,
                                       signed_pdf_hash, session_id, scan_match, acknowledged_flags, identity_check, signed_at)
                VALUES (?, gen_random_uuid(), ?, '%s', %s, %s, %s, ?, ?, ?, %s, %s, %s, TIMESTAMPTZ '2026-09-23 10:06:00+09')
                """.formatted(role, subject, channel, method, scan, ack, identity);
        if (expected == null) {
            assertAllowed(DB, T, sql, T, id, CANONICAL, SeedData.PDF_HASH, s);
        } else {
            assertRejected(DB, T, expected, sql, T, id, CANONICAL, SeedData.PDF_HASH, s);
        }
    }

    /** 종이 스캔은 대조 기록과 업로드 행위가 함께 있다(세션 채널 PAPER_SCAN). */
    @Test
    void paperScanCarriesItsMatchRecord() {
        UUID id = disclosure("SEALED");
        UUID s = UUID.randomUUID();
        DB.seed(T, c -> SeedData.exec(c, """
                INSERT INTO sign_session (tenant_id, session_id, disclosure_id, signer_role, channel, token_hash, expires_at, status,
                                          issued_by, issued_at, signed_doc_hash, signed_pdf_hash)
                SELECT tenant_id, ?, disclosure_id, 'CUSTOMER', 'PAPER_SCAN', ?, TIMESTAMPTZ '2026-09-30 10:00:00+09', 'OPEN', 'agent@seed',
                       TIMESTAMPTZ '2026-09-23 10:01:00+09', canonical_hash, pdf_hash
                  FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?
                """, s, SeedData.hash('6'), T, id));
        String sql = """
                INSERT INTO signature (tenant_id, signature_id, disclosure_id, signer_role, channel, method, signed_doc_hash, signed_pdf_hash,
                                       session_id, scan_match, identity_check, signed_at)
                VALUES (?, gen_random_uuid(), ?, 'CUSTOMER', 'PAPER_SCAN', ?, ?, ?, ?, %s, '[]'::jsonb, TIMESTAMPTZ '2026-09-23 10:06:00+09')
                """;
        assertRejected(DB, T, "23514", sql.formatted("'{\"pages\": 1}'::jsonb"), T, id, "DRAWN", CANONICAL, SeedData.PDF_HASH, s);
        assertRejected(DB, T, "23514", sql.formatted("NULL"), T, id, "UPLOADED_SCAN", CANONICAL, SeedData.PDF_HASH, s);
        assertAllowed(DB, T, sql.formatted("'{\"pages\": 1}'::jsonb"), T, id, "UPLOADED_SCAN", CANONICAL, SeedData.PDF_HASH, s);
    }
}
