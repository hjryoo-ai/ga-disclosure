package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import static com.ga.disclosure.infra.TriggerAssertions.assertAllowed;
import static com.ga.disclosure.infra.TriggerAssertions.assertRejected;
import static com.ga.disclosure.infra.TriggerAssertions.sqlStateOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V14 가드(6B 계획 §2·§A): 계약 연결 이력(GD130 — 활성 1건/확인서·증권, 정정은 이전 행에 한 번, 같은 확인서의 연결만 — 지연 외래키), 미매칭 보고 행
 * (GD131), 청약번호는 작성 때만(GD132), {@code ABANDONED}는 폐기 함수만·종단(GD133), 증권 현재값 = 활성 연결(GD136, 승인 §3), 준법 플래그(GD134 — 유형
 * 닫힌 목록·해소 한 번·룰 복사 값 고정), 징구율 스냅샷 append-only·유일 키에 룰 버전(GD135, 승인 §3), 폐기 롤은 함수 하나만, 고객 파기의 live 확인서에서
 * 폐기된 초안은 빠진다.
 */
class V14GuardIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("V14");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-10T09:00:00+09:00");
    private static final String WHERE = " WHERE tenant_id = ? AND disclosure_id = ?";
    private static final String ABANDON = "SELECT ga_draft_abandon(?, ?, ?, ?)";

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

    private static <R> R asSuperuser(String tenant, PostgresHarness.SqlFunction<R> fn) {
        try (Connection c = DB.superuserDataSource().getConnection()) {
            c.setAutoCommit(false);
            try {
                PostgresHarness.setTenant(c, tenant);
                return fn.apply(c);
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            throw new PostgresHarness.UncheckedSqlException(e);
        }
    }

    /** 소유 롤(마이그레이터)로 실행해 SQLSTATE를 돌려준다 — 앱 롤에 없는 권한 너머의 트리거를 본다. */
    private static String ownerState(String sql, Object... params) {
        return sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, sql, params)));
    }

    private static void commit(String sql, Object... params) {
        DB.asAppCommitting(T, c -> SeedData.exec(c, sql, params));
    }

    // ------------------------------------------------------------------ contract_link (GD130) · 현재값 (GD136)

    @Test
    void aContractLinkIsHistoryWithOneActiveRowPerDisclosureAndPolicy() {
        UUID d = disclosure("COMPLETED");
        UUID other = disclosure("COMPLETED");
        UUID[] first = new UUID[1];
        DB.asAppCommitting(T, c -> first[0] = SeedData.contractLink(c, T, d, "POL-A", "2026-10-01"));
        // 활성 1건/확인서, 활성 1건/증권
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> SeedData.contractLink(c, T, d, "POL-B", "2026-10-02")))).isEqualTo("23505");
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> SeedData.contractLink(c, T, other, "POL-A", "2026-10-02")))).isEqualTo("23505");
        // 값은 바뀌지 않는다, 지우지 않는다, 대체되지 않은 채 INSERT만
        String where = " WHERE tenant_id = ? AND link_id = ?";
        assertRejected(DB, T, "GD130", "UPDATE contract_link SET contract_date = DATE '2026-10-05'" + where, T, first[0]);
        assertRejected(DB, T, "GD130", "UPDATE contract_link SET policy_no = NULL" + where, T, first[0]);
        assertRejected(DB, T, "42501", "DELETE FROM contract_link" + where, T, first[0]);                     // 앱 롤에 DELETE 권한 없음
        assertThat(ownerState("DELETE FROM contract_link" + where, T, first[0])).isEqualTo("GD130");          // 소유자도 트리거가 막는다
        assertRejected(DB, T, "GD130", """
                INSERT INTO contract_link (tenant_id, link_id, disclosure_id, policy_no, contract_date, insurer_code, source, source_ref, received_at,
                                           linked_by, superseded_by, superseded_at)
                VALUES (?, gen_random_uuid(), ?, 'POL-Z', DATE '2026-10-01', 'INS-A', 'SEED', 'x-1', now(), 'seed', gen_random_uuid(), now())
                """, T, other);
        // 정정: 이전 행에 대체를 먼저 쓰고 새 활성 행 — 같은 확인서의 연결만 가리킨다(지연 외래키는 커밋 때)
        UUID next = UUID.randomUUID();
        DB.asAppCommitting(T, c -> {
            SeedData.exec(c, "UPDATE contract_link SET superseded_by = ?, superseded_at = now()" + where, next, T, first[0]);
            insertLink(c, next, d, "POL-A", "2026-10-03");
            SeedData.exec(c, "UPDATE disclosure SET contract_date = DATE '2026-10-03'" + WHERE, T, d);   // V15: 현재값도 같은 트랜잭션에서
            return null;
        });
        assertRejected(DB, T, "GD130", "UPDATE contract_link SET superseded_by = gen_random_uuid(), superseded_at = now()" + where, T, first[0]);
        String foreign = sqlStateOf(() -> DB.asAppCommitting(T, c -> {
            UUID otherLink = SeedData.contractLink(c, T, other, "POL-O", "2026-10-01");
            // 외래키만 본다 — 지연 정합 검사(V15 GD136, 활성 0건)보다 먼저 이 문장 끝에서 확인
            SeedData.exec(c, "SET CONSTRAINTS fk_contract_link_superseded_by IMMEDIATE");
            SeedData.exec(c, "UPDATE contract_link SET superseded_by = ?, superseded_at = now()" + where, next, T, otherLink);
            return null;
        }));
        assertThat(foreign).as("a link is superseded only by a link of the same disclosure").isEqualTo("23503");
    }

    private static void insertLink(Connection c, UUID id, UUID disclosure, String policy, String date) throws SQLException {
        SeedData.exec(c, """
                INSERT INTO contract_link (tenant_id, link_id, disclosure_id, policy_no, contract_date, insurer_code, source, source_ref, received_at, linked_by)
                VALUES (?, ?, ?, ?, CAST(? AS date), 'INS-A', 'SEED', ?, now(), 'seed')
                """, T, id, disclosure, policy, date, id.toString());
    }

    @Test
    void policyNumberAndContractDateMirrorTheActiveLink() {
        UUID d = disclosure("COMPLETED");
        assertRejected(DB, T, "GD136", "UPDATE disclosure SET policy_no = 'POL-M'" + WHERE, T, d);                 // 연결 없음
        DB.asAppCommitting(T, c -> SeedData.contractLink(c, T, d, "POL-M", "2026-10-01"));
        assertRejected(DB, T, "GD136", "UPDATE disclosure SET policy_no = 'POL-OTHER'" + WHERE, T, d);            // 연결과 다름
        assertRejected(DB, T, "GD136", "UPDATE disclosure SET contract_date = DATE '2026-09-30'" + WHERE, T, d);
        assertAllowed(DB, T, "UPDATE disclosure SET policy_no = 'POL-M', contract_date = DATE '2026-10-01'" + WHERE, T, d);
        assertAllowed(DB, T, "UPDATE disclosure SET policy_no = 'POL-M'" + WHERE, T, d);
    }

    @Test
    void unmatchedReportRowsAreNeverChangedButMayBeDeleted() {
        String insert = """
                INSERT INTO contract_link_unmatched (tenant_id, unmatched_id, policy_no, contract_date, insurer_code, reason, source, source_ref, received_at)
                VALUES (?, ?, 'POL-U', DATE '2026-10-01', 'INS-A', ?, 'SEED', ?, now())""";
        UUID id = UUID.randomUUID();
        commit(insert, T, id, "UNMATCHED", "u-" + id);
        assertRejected(DB, T, "23514", insert, T, UUID.randomUUID(), "SOMETHING_ELSE", "u-x");
        String change = "UPDATE contract_link_unmatched SET reason = 'AMBIGUOUS_MATCH' WHERE tenant_id = ? AND unmatched_id = ?";
        assertRejected(DB, T, "42501", change, T, id);                                                          // 앱 롤에 UPDATE 권한 없음
        assertThat(ownerState(change, T, id)).isEqualTo("GD131");
        assertAllowed(DB, T, "DELETE FROM contract_link_unmatched WHERE tenant_id = ? AND unmatched_id = ?", T, id);
    }

    // ------------------------------------------------------------------ application_no (GD132) · ABANDONED (GD133)

    private static UUID draftWithText() {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> {
            id[0] = SeedData.disclosure(c, T, "REASONED", null);
            SeedData.exec(c, "UPDATE disclosure SET application_no = NULL" + WHERE, T, id[0]);   // 변경 없음은 허용
            SeedData.item(c, T, id[0], 1, "0.84");
            SeedData.recommendation(c, T, id[0], 1);
            SeedData.exec(c, "UPDATE recommendation SET reason_text = '고객 요청 메모'" + WHERE, T, id[0]);
            SeedData.review(c, T, id[0], "R-TEMP-PRODUCT");
        });
        return id[0];
    }

    @Test
    void anApplicationNumberIsWrittenOnlyWhenTheDraftIsCreated() {
        UUID d = draftWithText();
        assertRejected(DB, T, "GD132", "UPDATE disclosure SET application_no = 'APP-1'" + WHERE, T, d);
        assertRejected(DB, T, "23514", """
                INSERT INTO disclosure (tenant_id, disclosure_id, org_path, agent_id, customer_ref, group_code, template_id, template_version,
                                        rule_version_id, issuer_mode, status, consult_date, application_no)
                VALUES (?, gen_random_uuid(), '/HQ/B1', 'AGENT-1', 'C-0001', 'PG-HEALTH', 'STANDARD', 1, 'DISC-2026-07', 'SELF', 'DRAFT',
                        DATE '2026-09-23', 'APP WITH SPACE')
                """, T);
    }

    @Test
    void onlyTheAbandonFunctionMakesATombstoneAndItNullsOnlyTheFreeText() {
        UUID d = draftWithText();
        assertRejected(DB, T, "GD133", "UPDATE disclosure SET status = 'ABANDONED', abandoned_at = now()" + WHERE, T, d);
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> SeedData.call(c, ABANDON, T, d, AT, "agent-1")))).as("the app role cannot execute it")
                .isEqualTo("42501");
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_destroyer");
            return SeedData.call(c, ABANDON, T, d, AT, "agent-1");
        }))).as("the destroyer role cannot abandon").isEqualTo("42501");

        DB.asAppCommitting(T, c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_abandoner");
            SeedData.call(c, ABANDON, T, d, AT, "agent-1");
            SeedData.exec(c, "RESET ROLE");
            return null;
        });
        DB.asApp(T, c -> {
            assertThat(SeedData.call(c, "SELECT status || '|' || (abandoned_at IS NOT NULL) || '|' || coalesce(disclosure_no, '-') FROM disclosure"
                    + WHERE, T, d)).isEqualTo("ABANDONED|true|-");
            assertThat(SeedData.call(c, "SELECT count(*) FROM recommendation" + WHERE + " AND reason_text IS NOT NULL", T, d)).isEqualTo("0");
            assertThat(SeedData.call(c, "SELECT count(*) FROM recommendation" + WHERE, T, d)).as("rows stay").isEqualTo("1");
            assertThat(SeedData.call(c, "SELECT count(*) FROM review" + WHERE + " AND reason IS NOT NULL", T, d)).isEqualTo("0");
            assertThat(SeedData.call(c, "SELECT string_agg(field_values::text, ',') FROM disclosure_item" + WHERE, T, d)).isEqualTo("{}");
            return null;
        });
        // 묘비: 어떤 변경도 없다(자식도 — 부모가 가변 상태가 아니다), 다시 폐기할 수 없다, 지우지 않는다
        assertRejected(DB, T, "GD133", "UPDATE disclosure SET group_code = 'PG-OTHER'" + WHERE, T, d);
        assertRejected(DB, T, "GD010", "UPDATE disclosure_item SET product_name = 'x'" + WHERE, T, d);
        assertRejected(DB, T, "GD002", "DELETE FROM disclosure" + WHERE, T, d);
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_abandoner");
            return SeedData.call(c, ABANDON, T, d, AT, "agent-1");
        }))).isEqualTo("GD133");
    }

    @Test
    void aSealedDisclosureIsNeverAbandonedAndNothingIsCreatedAbandoned() {
        UUID sealed = disclosure("SEALED");
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_abandoner");
            return SeedData.call(c, ABANDON, T, sealed, AT, "agent-1");
        }))).isEqualTo("GD133");
        assertRejected(DB, T, "GD133", """
                INSERT INTO disclosure (tenant_id, disclosure_id, org_path, agent_id, customer_ref, group_code, template_id, template_version,
                                        rule_version_id, issuer_mode, status, consult_date, abandoned_at)
                VALUES (?, gen_random_uuid(), '/HQ/B1', 'AGENT-1', 'C-0001', 'PG-HEALTH', 'STANDARD', 1, 'DISC-2026-07', 'SELF', 'ABANDONED',
                        DATE '2026-09-23', now())
                """, T);
    }

    @Test
    void theAbandonerHoldsOnlyTheAbandonFunction() {
        asSuperuser(T, c -> {
            assertThat(SeedData.call(c, "SELECT count(*) FROM information_schema.role_table_grants WHERE grantee = 'disclosure_abandoner'")).isEqualTo("0");
            assertThat(SeedData.call(c, """
                    SELECT string_agg(p.proname, ',' ORDER BY p.proname) FROM pg_proc p
                     WHERE has_function_privilege('disclosure_abandoner', p.oid, 'EXECUTE')
                       AND p.pronamespace = 'public'::regnamespace AND NOT has_function_privilege('public', p.oid, 'EXECUTE')
                    """)).isEqualTo("ga_draft_abandon");
            assertThat(SeedData.call(c, """
                    SELECT pg_get_userbyid(p.proowner) || ':' || p.prosecdef || ':' || array_to_string(p.proconfig, ',')
                      FROM pg_proc p WHERE p.proname = 'ga_draft_abandon'
                    """)).isEqualTo("disclosure_destroy_definer:true:search_path=public, pg_temp");
            return null;
        });
    }

    @Test
    void anAbandonedDraftIsNotALiveDisclosureForCustomerDestruction() {
        String customer = "CR-" + UUID.randomUUID().toString().replace("-", "");
        UUID[] id = new UUID[1];
        DB.seed(T, c -> {
            SeedData.customer(c, T, customer);
            id[0] = SeedData.disclosure(c, T, "DRAFT", null, "DISC-2026-07", customer);
        });
        String destroy = "SELECT ga_customer_ref_destroy(?, ?, ?, ?)";
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_destroyer");
            return SeedData.call(c, destroy, T, customer, AT, "RETENTION:test");
        }))).as("a live draft blocks customer destruction").isEqualTo("GD114");
        DB.asAppCommitting(T, c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_abandoner");
            SeedData.call(c, ABANDON, T, id[0], AT, "agent-1");
            SeedData.exec(c, "RESET ROLE");
            SeedData.exec(c, "SET LOCAL ROLE disclosure_destroyer");
            SeedData.call(c, destroy, T, customer, AT, "RETENTION:test");
            return null;
        });
        assertThat(DB.<String>asApp(T, c -> SeedData.call(c, "SELECT (destroyed_at IS NOT NULL)::text FROM customer_ref WHERE tenant_id = ? AND customer_ref = ?",
                T, customer))).isEqualTo("true");
    }

    // ------------------------------------------------------------------ compliance_flag (GD134)

    private static UUID flag(String type) {
        UUID id = UUID.randomUUID();
        commit("INSERT INTO compliance_flag (tenant_id, flag_id, type, severity, raised_at, due_at) VALUES (?, ?, ?, 'HIGH', now(), now() + INTERVAL '1 hour')",
                T, id, type);
        return id;
    }

    @Test
    void complianceFlagsHaveAClosedTypeListAndResolveOnce() {
        assertRejected(DB, T, "23514", "INSERT INTO compliance_flag (tenant_id, flag_id, type, severity, raised_at) VALUES (?, ?, 'MISSING', 'HIGH', now())",
                T, UUID.randomUUID());
        assertRejected(DB, T, "GD134", """
                INSERT INTO compliance_flag (tenant_id, flag_id, type, severity, raised_at, resolved_at, resolved_by, resolution)
                VALUES (?, ?, 'SIGN_EXPIRED', 'HIGH', now(), now(), 'x', 'APPROVED')""", T, UUID.randomUUID());
        UUID f = flag("CHAIN_BROKEN");
        String where = " WHERE tenant_id = ? AND flag_id = ?";
        assertThat(DB.<String>asApp(T, c -> SeedData.call(c, "SELECT assigned_role || ':' || visible_to_agent FROM compliance_flag" + where, T, f)))
                .as("fail-closed defaults").isEqualTo("COMPLIANCE:false");
        for (String change : new String[] {"type = 'SIGN_EXPIRED'", "raised_at = now() - INTERVAL '1 day'", "assigned_role = 'MANAGER'",
                "visible_to_agent = true", "due_at = now() + INTERVAL '2 hours'"}) {
            assertRejected(DB, T, "GD134", "UPDATE compliance_flag SET " + change + where, T, f);
        }
        assertRejected(DB, T, "GD134", "DELETE FROM compliance_flag" + where, T, f);
        commit("UPDATE compliance_flag SET assignee = 'compliance-1'" + where, T, f);
        assertRejected(DB, T, "23514", "UPDATE compliance_flag SET resolved_at = now()" + where, T, f);           // 셋을 함께
        assertRejected(DB, T, "23514", "UPDATE compliance_flag SET resolution_evidence = '{}'" + where, T, f);    // 코드 없는 근거
        commit("""
                UPDATE compliance_flag SET resolved_at = now(), resolved_by = 'compliance-1', resolution = 'COMPLIANCE_RESOLVED',
                                           resolution_code = 'VERIFIED_MATCH', resolution_evidence = '{"verifyRunJobId": "x"}'
                """ + where, T, f);
        assertRejected(DB, T, "GD134", "UPDATE compliance_flag SET assignee = 'compliance-2'" + where, T, f);
        assertRejected(DB, T, "GD134", "UPDATE compliance_flag SET resolution_code = 'OTHER'" + where, T, f);
    }

    @Test
    void anSlaBreachIsMarkedOnceAndOnlyAfterTheDueTime() {
        UUID f = flag("NOTIFY_FAILED");
        String where = " WHERE tenant_id = ? AND flag_id = ?";
        assertRejected(DB, T, "23514", "UPDATE compliance_flag SET sla_breached_at = raised_at" + where, T, f);
        commit("UPDATE compliance_flag SET sla_breached_at = due_at + INTERVAL '1 minute'" + where, T, f);
        assertRejected(DB, T, "GD134", "UPDATE compliance_flag SET sla_breached_at = due_at + INTERVAL '2 minutes'" + where, T, f);
    }

    // ------------------------------------------------------------------ collection_rate_snapshot (GD135)

    @Test
    void collectionRateSnapshotsAreAppendOnlyAndUniquePerRuleVersion() {
        String insert = """
                INSERT INTO collection_rate_snapshot (tenant_id, snapshot_id, period_month, org_path, formula, denominator, numerator, rate_bp,
                                                      computed_at, rule_version_id, inputs_hash, job_id)
                VALUES (?, gen_random_uuid(), CAST(? AS date), ?, 'LINKED_COMPLETED_BY_CONTRACT_DATE', ?, ?, ?, now(), ?, repeat('a', 64), gen_random_uuid())""";
        commit(insert, T, "2026-09-01", "/", 3, 2, 6666, "DISC-2026-07");
        assertRejected(DB, T, "23505", insert, T, "2026-09-01", "/", 3, 2, 6666, "DISC-2026-07");     // 같은 달·조직·룰 버전
        assertAllowed(DB, T, insert, T, "2026-09-01", "/", 3, 3, 10000, "DISC-2027-01");             // 새 룰 버전은 새 행
        assertRejected(DB, T, "23514", insert, T, "2026-09-02", "/", 3, 2, 6666, "X");                 // 달의 1일
        assertRejected(DB, T, "23514", insert, T, "2026-08-01", "/", 3, 2, 6667, "X");                 // 버림 식
        assertRejected(DB, T, "23514", insert, T, "2026-08-01", "/", 0, 0, 0, "X");                    // 분모 0이면 NULL
        assertAllowed(DB, T, insert, T, "2026-08-01", "/HQ/B1", 0, 0, null, "X");
        assertRejected(DB, T, "23514", insert, T, "2026-08-01", "/", 2, 3, 15000, "X");                // 분자 ≤ 분모
        for (String sql : new String[] {"UPDATE collection_rate_snapshot SET numerator = 3, rate_bp = 10000 WHERE tenant_id = ?",
                "DELETE FROM collection_rate_snapshot WHERE tenant_id = ?"}) {
            assertRejected(DB, T, "42501", sql, T);                                                            // 앱 롤은 SELECT·INSERT만
            assertThat(ownerState(sql, T)).as(sql).isEqualTo("GD135");
        }
    }

    // ------------------------------------------------------------------ roles

    @Test
    void serviceRolesForContractFeedsAndTheGateStandAlone() {
        String link = "INSERT INTO identity_link (tenant_id, subject, agent_id, roles, org_path) VALUES (?, ?, ?, CAST(? AS text[]), ?)";
        assertAllowed(DB, T, link, T, "feed-1", null, "{CONTRACT_FEED}", null);
        assertAllowed(DB, T, link, T, "gate-1", null, "{GATE_CLIENT}", null);
        assertRejected(DB, T, "23514", link, T, "gate-2", null, "{GATE_CLIENT,COMPLIANCE}", null);
        assertRejected(DB, T, "23514", link, T, "feed-2", "A-1", "{CONTRACT_FEED}", null);
        assertRejected(DB, T, "23514", link, T, "x-1", null, "{OPERATOR}", null);
    }
}
