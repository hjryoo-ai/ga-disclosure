package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static com.ga.disclosure.infra.TriggerAssertions.assertAllowed;
import static com.ga.disclosure.infra.TriggerAssertions.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V12 가드(6A 계획 §2): {@code identity_link} 주체 CHECK(승인 Q2), {@code disclosure.org_path} 작성 시 1회(GD124, 승인 Q1), 원격 링크 토큰은 발송 때
 * 1회(GD123, 승인 Q3), {@code idempotency_key}(GD120), {@code async_job} 전이·활성 유일(GD121, 승인 Q11), {@code notification_outbox}(GD122),
 * 작업 잠금 롤의 권한 0(승인 Q8). R1 앵커 CHECK는 {@code AnchorGuardIT}, 4-eyes CHECK는 {@code LegalHoldIT}가 본다.
 */
class V12GuardIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("V12");

    @BeforeAll
    static void seed() {
        DB.seed(T, c -> {
            SeedData.tenant(c, T);
            SeedData.dataKey(c, T, SeedData.SEED_KEY_ID);
            SeedData.customer(c, T, SeedData.SEED_CUSTOMER_REF);
        });
    }

    private static UUID sealed() {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> id[0] = SeedData.disclosure(c, T, "SEALED", SeedData.hash('a')));
        return id[0];
    }

    private static UUID remote() {
        UUID[] s = new UUID[1];
        UUID d = sealed();
        DB.seed(T, c -> s[0] = SeedData.remoteSession(c, T, d));
        return s[0];
    }

    /** 소유 롤(마이그레이터)로 실행해 SQLSTATE를 돌려준다 — 앱 롤에 없는 권한 너머의 트리거를 본다. */
    private static String ownerState(String sql, Object... params) {
        return TriggerAssertions.sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, sql, params)));
    }

    private static void commit(String sql, Object... params) {
        DB.asAppCommitting(T, c -> SeedData.exec(c, sql, params));
    }

    // ------------------------------------------------------------------ identity_link

    private static final String LINK = "INSERT INTO identity_link (tenant_id, subject, agent_id, roles, org_path) VALUES (?, ?, ?, CAST(? AS text[]), ?)";

    @Test
    void identityLinksOfNonAgentPrincipalsAreConstrainedByRole() {
        assertAllowed(DB, T, LINK, T, "c-1", null, "{COMPLIANCE}", null);
        assertAllowed(DB, T, LINK, T, "c-2", null, "{COMPLIANCE}", "/HQ");
        assertAllowed(DB, T, LINK, T, "s-1", null, "{SCHEDULER}", null);
        assertAllowed(DB, T, LINK, T, "f-1", null, "{FEED_CONSUMER}", null);
        assertAllowed(DB, T, LINK, T, "a-1", "AGENT-9", "{AGENT,MANAGER}", "/HQ/B_1-x");
        assertAllowed(DB, T, LINK, T, "m-1", null, "{MANAGER}", "/HQ");

        assertRejected(DB, T, "23514", LINK, T, "x", null, "{ADMIN}", null);                       // 닫힌 역할 집합
        assertRejected(DB, T, "23514", LINK, T, "x", null, "{OPERATOR}", null);                    // CLI 채널 역할은 연결이 아니다
        assertRejected(DB, T, "23514", LINK, T, "x", null, "{}", null);
        assertRejected(DB, T, "23514", LINK, T, "x", null, "{AGENT}", "/HQ");                      // AGENT ⇒ agent_id
        assertRejected(DB, T, "23514", LINK, T, "x", "AGENT-9", "{AGENT}", null);                  // AGENT ⇒ 조직
        assertRejected(DB, T, "23514", LINK, T, "x", null, "{MANAGER}", null);                     // MANAGER ⇒ 조직
        assertRejected(DB, T, "23514", LINK, T, "x", null, "{MANAGER}", "/HQ/");                   // 경로 형식
        assertRejected(DB, T, "23514", LINK, T, "x", null, "{MANAGER}", "HQ");
        assertRejected(DB, T, "23514", LINK, T, "x", null, "{SCHEDULER,COMPLIANCE}", null);         // 서비스 주체는 단독
        assertRejected(DB, T, "23514", LINK, T, "x", null, "{SCHEDULER}", "/HQ");
        assertRejected(DB, T, "23514", LINK, T, "x", "AGENT-9", "{FEED_CONSUMER}", null);
    }

    // ------------------------------------------------------------------ disclosure.org_path (GD124)

    @Test
    void theOrganisationPathIsRecordedAtCreationAndNeverChanges() {
        String insert = """
                INSERT INTO disclosure (tenant_id, disclosure_id, org_path, agent_id, customer_ref, group_code, template_id, template_version,
                                        rule_version_id, issuer_mode, status, consult_date)
                VALUES (?, gen_random_uuid(), CAST(? AS text), 'AGENT-1', 'C-1', 'PG-HEALTH', 'STANDARD', 1, 'DISC-2026-07', 'SELF', 'DRAFT',
                        DATE '2026-09-23')""";
        assertRejected(DB, T, "GD124", insert, T, null);
        assertRejected(DB, T, "23514", insert, T, "/HQ//B1");
        assertAllowed(DB, T, insert, T, "/HQ/B1");

        UUID draft = UUID.randomUUID();
        DB.seed(T, c -> SeedData.exec(c, insert.replace("gen_random_uuid()", "?"), T, draft, "/HQ/B1"));
        assertRejected(DB, T, "GD124", "UPDATE disclosure SET org_path = '/HQ/B2' WHERE tenant_id = ? AND disclosure_id = ?", T, draft);
        assertRejected(DB, T, "GD124", "UPDATE disclosure SET org_path = NULL WHERE tenant_id = ? AND disclosure_id = ?", T, draft);
        assertAllowed(DB, T, "UPDATE disclosure SET org_path = org_path, status = 'COMPARED' WHERE tenant_id = ? AND disclosure_id = ?", T, draft);
    }

    // ------------------------------------------------------------------ sign_session 발송 시 토큰 (GD123)

    @Test
    void aRemoteLinkTokenIsRecordedOnceTogetherWithTheSendTime() {
        UUID s = remote();
        String where = " WHERE tenant_id = ? AND session_id = ?";
        assertRejected(DB, T, "GD123", "UPDATE sign_session SET token_hash = repeat('e', 64)" + where, T, s);                 // 발송 시각 없이
        assertRejected(DB, T, "23514", "UPDATE sign_session SET sent_at = TIMESTAMPTZ '2026-09-23 10:02:00+09'" + where, T, s); // 토큰 없이 발송
        commit("UPDATE sign_session SET token_hash = repeat('e', 64), sent_at = TIMESTAMPTZ '2026-09-23 10:02:00+09'" + where, T, s);
        assertRejected(DB, T, "GD101", "UPDATE sign_session SET token_hash = repeat('f', 64)" + where, T, s);                 // 값이 있으면 고정
        assertRejected(DB, T, "GD101", "UPDATE sign_session SET token_hash = NULL" + where, T, s);
    }

    @Test
    void aClosedOrNonRemoteSessionNeverGetsALinkToken() {
        UUID revoked = remote();
        String where = " WHERE tenant_id = ? AND session_id = ?";
        commit("UPDATE sign_session SET status = 'REVOKED', revoked_at = TIMESTAMPTZ '2026-09-23 10:05:00+09', revoke_reason = 'DOCUMENT_VOIDED'"
                + where, T, revoked);
        assertRejected(DB, T, "GD123", "UPDATE sign_session SET token_hash = repeat('e', 64), sent_at = TIMESTAMPTZ '2026-09-23 10:06:00+09'"
                + where, T, revoked);

        UUID d = sealed();
        assertRejected(DB, T, "23514", """
                INSERT INTO sign_session (tenant_id, session_id, disclosure_id, signer_role, channel, token_hash, expires_at, status,
                                          issued_by, issued_at, signed_doc_hash, signed_pdf_hash)
                SELECT d.tenant_id, gen_random_uuid(), d.disclosure_id, 'CUSTOMER', 'TOUCH_PAD', NULL, TIMESTAMPTZ '2026-09-23 10:30:00+09',
                       'OPEN', 'agent@seed', TIMESTAMPTZ '2026-09-23 10:01:00+09', d.canonical_hash, d.pdf_hash
                  FROM disclosure d WHERE d.tenant_id = ? AND d.disclosure_id = ?""", T, d);              // 현장 채널은 발급 때 토큰
    }

    // ------------------------------------------------------------------ idempotency_key (GD120)

    private static final String KEY_WHERE = " WHERE tenant_id = ? AND actor_subject = 'sub-1' AND idem_key = ?";

    private static String key() {
        String k = "k-" + UUID.randomUUID();
        DB.seed(T, c -> SeedData.idempotencyKey(c, T, "sub-1", k, "2999-01-01 00:00:00+09"));
        return k;
    }

    @Test
    void anIdempotencyKeyIsTakenOverOrCompletedOnceAndThenNeverChanges() {
        String k = key();
        assertRejected(DB, T, "GD120", "UPDATE idempotency_key SET claim_seq = 3, claimed_at = claimed_at + INTERVAL '1 minute'" + KEY_WHERE, T, k);
        assertRejected(DB, T, "GD120", "UPDATE idempotency_key SET claim_seq = 2" + KEY_WHERE, T, k);           // 청구 시각이 전진하지 않음
        assertRejected(DB, T, "GD120", "UPDATE idempotency_key SET request_hash = repeat('1', 64)" + KEY_WHERE, T, k);
        assertRejected(DB, T, "GD120", "UPDATE idempotency_key SET expires_at = expires_at + INTERVAL '1 day'" + KEY_WHERE, T, k);
        commit("UPDATE idempotency_key SET claim_seq = 2, claimed_at = claimed_at + INTERVAL '3 minutes'" + KEY_WHERE, T, k);
        assertRejected(DB, T, "23514", "UPDATE idempotency_key SET response_status = 201" + KEY_WHERE, T, k);   // 셋을 함께
        assertRejected(DB, T, "23514", "UPDATE idempotency_key SET response_status = 500, response_ref = '{}', response_hash = repeat('2', 64)"
                + KEY_WHERE, T, k);                                                                             // 5xx는 저장하지 않는다
        assertRejected(DB, T, "GD120", "UPDATE idempotency_key SET response_status = 201, response_ref = '{}', response_hash = repeat('2', 64),"
                + " claim_seq = 3, claimed_at = claimed_at + INTERVAL '1 minute'" + KEY_WHERE, T, k);
        commit("UPDATE idempotency_key SET response_status = 201, response_ref = '{\"disclosureId\": \"x\"}', response_hash = repeat('2', 64)"
                + KEY_WHERE, T, k);
        assertRejected(DB, T, "GD120", "UPDATE idempotency_key SET response_hash = repeat('3', 64)" + KEY_WHERE, T, k);
        assertRejected(DB, T, "GD120", "DELETE FROM idempotency_key" + KEY_WHERE, T, k);                         // 만료 전
    }

    /** V13(6A 수용심사 §2 ②): 진행 중 행은 만료 전에도 지울 수 있다(해제), 완료 행은 여전히 만료 뒤에만. */
    @Test
    void aLiveKeyIsReleasedOnlyWhileInProgress() {
        String inProgress = key();
        assertAllowed(DB, T, "DELETE FROM idempotency_key" + KEY_WHERE, T, inProgress);
        String completed = key();
        commit("UPDATE idempotency_key SET response_status = 422, response_ref = '{}', response_hash = repeat('2', 64)" + KEY_WHERE, T, completed);
        assertRejected(DB, T, "GD120", "DELETE FROM idempotency_key" + KEY_WHERE, T, completed);
    }

    @Test
    void idempotencyKeysStartInProgressAndAreDeletedOnlyAfterExpiry() {
        String insert = """
                INSERT INTO idempotency_key (tenant_id, actor_subject, idem_key, request_hash, claim_seq, claimed_at, response_status, response_ref,
                                             response_hash, created_at, expires_at)
                VALUES (?, 'sub-1', ?, repeat('0', 64), ?, now(), CAST(? AS smallint), CAST(? AS jsonb), ?, now(), now() + INTERVAL '1 day')""";
        assertRejected(DB, T, "GD120", insert, T, "k-done-000000000001", 1, 201, "{}", "2".repeat(64));
        assertRejected(DB, T, "GD120", insert, T, "k-claim-00000000002", 2, null, null, null);
        assertRejected(DB, T, "23514", insert, T, "short", 1, null, null, null);                                     // 키 형식
        assertRejected(DB, T, "23514", insert, T, "k with space 000001", 1, null, null, null);
        assertAllowed(DB, T, insert, T, "k-ok-0000000000003", 1, null, null, null);

        String expired = "k-expired-" + UUID.randomUUID();
        DB.seed(T, c -> SeedData.idempotencyKey(c, T, "sub-1", expired, "2020-01-01 00:00:00+09"));
        assertAllowed(DB, T, "DELETE FROM idempotency_key" + KEY_WHERE, T, expired);
        assertThat(TriggerAssertions.sqlStateOf(() -> DB.asApp(T, c -> {
            try (Statement st = c.createStatement()) {
                st.execute("TRUNCATE idempotency_key");
            }
            return null;
        }))).as("앱 롤은 TRUNCATE 권한도 없다").isIn("42501", "GD120");
    }

    // ------------------------------------------------------------------ async_job (GD121)

    private static UUID job(String kind) {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> id[0] = SeedData.asyncJob(c, T, kind));
        return id[0];
    }

    private static final String JOB_WHERE = " WHERE tenant_id = ? AND job_id = ?";
    private static final String START = "UPDATE async_job SET status = 'RUNNING', started_at = TIMESTAMPTZ '2026-10-01 00:00:01+09'" + JOB_WHERE;
    private static final String SUCCEED = """
            UPDATE async_job SET status = 'SUCCEEDED', finished_at = TIMESTAMPTZ '2026-10-01 00:00:02+09', result_ref = tenant_id || '/reports/' || job_id,
                   report_sha256 = repeat('a', 64), report_key_wrapped = decode('00', 'hex'), report_kek_id = 'KEK-1'""" + JOB_WHERE;
    private static final String FAIL = "UPDATE async_job SET status = 'FAILED', finished_at = TIMESTAMPTZ '2026-10-01 00:00:02+09', error_code = 'X'"
            + JOB_WHERE;

    @Test
    void jobsFollowTheStateTableAndTerminalJobsNeverChange() {
        UUID queued = job("EXPIRE");
        assertRejected(DB, T, "GD121", SUCCEED, T, queued);                                                       // QUEUED → SUCCEEDED 없음
        assertAllowed(DB, T, FAIL, T, queued);                                                                     // QUEUED → FAILED(승인 Q11)
        assertRejected(DB, T, "GD121", "UPDATE async_job SET kind = 'RECONCILE'" + JOB_WHERE, T, queued);
        assertRejected(DB, T, "GD121", "UPDATE async_job SET params = '{\"x\": 1}'" + JOB_WHERE, T, queued);
        commit(START, T, queued);
        assertRejected(DB, T, "GD121", "UPDATE async_job SET status = 'QUEUED', started_at = NULL" + JOB_WHERE, T, queued);
        assertRejected(DB, T, "GD121", "UPDATE async_job SET started_at = started_at + INTERVAL '1 second'" + JOB_WHERE, T, queued);
        assertAllowed(DB, T, FAIL, T, queued);
        commit(SUCCEED, T, queued);
        for (String change : List.of(FAIL, START, "UPDATE async_job SET report_sha256 = repeat('b', 64)" + JOB_WHERE)) {
            assertRejected(DB, T, "GD121", change, T, queued);
        }
        assertRejected(DB, T, "42501", "DELETE FROM async_job" + JOB_WHERE, T, queued);                    // 앱 롤은 DELETE 권한이 없다
        assertThat(ownerState("DELETE FROM async_job" + JOB_WHERE, T, queued)).as("소유 롤도 트리거가 막는다").isEqualTo("GD121");

        UUID failed = job("EXPIRE");
        commit(FAIL, T, failed);
        assertRejected(DB, T, "GD121", START, T, failed);
        assertRejected(DB, T, "GD121", "UPDATE async_job SET error_code = 'Y'" + JOB_WHERE, T, failed);
    }

    @Test
    void jobRowsAreBoundToTheirStatus() {
        String insert = "INSERT INTO async_job (tenant_id, job_id, kind, status, requested_by, channel, requested_at) VALUES (?, gen_random_uuid(), ?, ?, 'x', ?, now())";
        assertRejected(DB, T, "GD121", insert, T, "EXPIRE", "RUNNING", "HTTP");
        assertRejected(DB, T, "23514", insert, T, "PURGE_EVERYTHING", "QUEUED", "HTTP");
        assertRejected(DB, T, "23514", insert, T, "EXPIRE", "QUEUED", "SIGN_TOKEN");
        UUID running = job("RECONCILE");
        commit(START, T, running);
        assertRejected(DB, T, "23514", SUCCEED.replace("tenant_id || '/reports/' || job_id", "'elsewhere'"), T, running);   // 보고서 위치
        assertRejected(DB, T, "23514", SUCCEED.replace(", report_kek_id = 'KEK-1'", ""), T, running);                       // 결과 컬럼 함께
        assertRejected(DB, T, "23514", FAIL.replace("'X'", "'lower case'"), T, running);
    }

    /** 활성(QUEUED·RUNNING) 작업은 테넌트·종류당 1건 — advisory lock의 벨트. DESTROY와 DESTROY_DRY_RUN은 같은 키다. */
    @Test
    void oneActiveJobPerTenantAndKindWithDestructionKindsSharingTheKey() {
        String t = SeedData.uniqueTenant("V12J");
        DB.seed(t, c -> SeedData.tenant(c, t));
        DB.seed(t, c -> SeedData.asyncJob(c, t, "DESTROY"));
        String insert = "INSERT INTO async_job (tenant_id, job_id, kind, status, requested_by, channel, requested_at) VALUES (?, gen_random_uuid(), ?, 'QUEUED', 'x', 'CLI', now())";
        assertRejected(DB, t, "23505", insert, t, "DESTROY");
        assertRejected(DB, t, "23505", insert, t, "DESTROY_DRY_RUN");
        assertAllowed(DB, t, insert, t, "VERIFY_TENANT");
        assertAllowed(DB, T, insert, T, "DESTROY_DRY_RUN");                                                      // 다른 테넌트
    }

    // ------------------------------------------------------------------ notification_outbox (GD122)

    private static UUID notification() {
        UUID s = remote();
        UUID[] id = new UUID[1];
        DB.seed(T, c -> id[0] = SeedData.notification(c, T, SeedData.SEED_CUSTOMER_REF, s));
        return id[0];
    }

    private static final String N_WHERE = " WHERE tenant_id = ? AND notification_id = ?";
    private static final String RETRY = "UPDATE notification_outbox SET attempts = attempts + 1, next_attempt_at = next_attempt_at + INTERVAL '1 minute',"
            + " last_error_code = 'TIMEOUT'" + N_WHERE;

    @Test
    void aPendingNotificationRecordsOneFailureAtATimeAndClosesOnce() {
        UUID n = notification();
        assertRejected(DB, T, "GD122", "UPDATE notification_outbox SET attempts = attempts + 2, next_attempt_at = next_attempt_at + INTERVAL '1 minute',"
                + " last_error_code = 'TIMEOUT'" + N_WHERE, T, n);
        assertRejected(DB, T, "GD122", "UPDATE notification_outbox SET attempts = attempts + 1, last_error_code = 'TIMEOUT'" + N_WHERE, T, n);
        assertRejected(DB, T, "GD122", "UPDATE notification_outbox SET customer_ref = customer_ref || 'x'" + N_WHERE, T, n);
        commit(RETRY, T, n);
        assertRejected(DB, T, "23514", "UPDATE notification_outbox SET status = 'SENT', closed_at = now()" + N_WHERE, T, n);   // 발송 시각
        assertRejected(DB, T, "GD122", "UPDATE notification_outbox SET status = 'SENT', sent_at = now(), closed_at = now(), attempts = attempts + 1"
                + N_WHERE, T, n);
        commit("UPDATE notification_outbox SET status = 'SENT', sent_at = now(), closed_at = now()" + N_WHERE, T, n);
        assertRejected(DB, T, "GD122", RETRY, T, n);
        assertRejected(DB, T, "GD122", "UPDATE notification_outbox SET status = 'DEAD', sent_at = NULL, last_error_code = 'X'" + N_WHERE, T, n);
        assertRejected(DB, T, "42501", "DELETE FROM notification_outbox" + N_WHERE, T, n);
        assertThat(ownerState("DELETE FROM notification_outbox" + N_WHERE, T, n)).isEqualTo("GD122");

        UUID dead = notification();
        assertRejected(DB, T, "23514", "UPDATE notification_outbox SET status = 'DEAD', closed_at = now()" + N_WHERE, T, dead);   // 오류 코드
        assertAllowed(DB, T, "UPDATE notification_outbox SET status = 'DEAD', closed_at = now(), attempts = attempts + 1, last_error_code = 'NO_PHONE'"
                + N_WHERE, T, dead);
        assertAllowed(DB, T, "UPDATE notification_outbox SET status = 'CANCELLED', closed_at = now(), last_error_code = 'SESSION_CLOSED'" + N_WHERE,
                T, dead);
    }

    @Test
    void notificationsStartPendingAndOnePerSession() {
        UUID s = remote();
        String insert = """
                INSERT INTO notification_outbox (tenant_id, notification_id, kind, customer_ref, session_id, status, attempts, next_attempt_at, created_at)
                VALUES (?, gen_random_uuid(), 'SIGN_LINK', ?, ?, ?, ?, now(), now())""";
        assertRejected(DB, T, "GD122", insert, T, SeedData.SEED_CUSTOMER_REF, s, "SENT", 0);
        assertRejected(DB, T, "GD122", insert, T, SeedData.SEED_CUSTOMER_REF, s, "PENDING", 1);
        assertRejected(DB, T, "23503", insert, T, "CR-UNKNOWN", s, "PENDING", 0);                                 // 실재하는 가명만
        DB.asAppCommitting(T, c -> SeedData.exec(c, insert, T, SeedData.SEED_CUSTOMER_REF, s, "PENDING", 0));
        assertRejected(DB, T, "23505", insert, T, SeedData.SEED_CUSTOMER_REF, s, "PENDING", 0);
    }

    // ------------------------------------------------------------------ 작업 잠금 롤(승인 Q8)

    @ParameterizedTest
    @ValueSource(strings = {"SELECT count(*) FROM public.tenant", "SELECT count(*) FROM public.async_job", "SELECT count(*) FROM public.audit_log"})
    void theJobLockRoleReadsNoTableButTakesAdvisoryLocks(String sql) throws SQLException {
        try (Connection c = DB.jobLockDataSource().getConnection(); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeQuery(sql)).isInstanceOf(SQLException.class)
                    .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
        }
        try (Connection c = DB.jobLockDataSource().getConnection(); Statement st = c.createStatement();
             var rs = st.executeQuery("SELECT pg_try_advisory_lock(hashtextextended('ga.job|" + T + "|PROBE', 0)), current_user")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean(1)).isTrue();
            assertThat(rs.getString(2)).isEqualTo(PostgresHarness.JOB_LOCK);
        }
    }
}
