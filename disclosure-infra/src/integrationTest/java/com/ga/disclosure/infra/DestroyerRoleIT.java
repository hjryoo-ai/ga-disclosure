package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.ga.disclosure.infra.TriggerAssertions.sqlStateOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G7 파기 롤(V9, 5 계획 §1.5, 승인 Q2): 파기자 롤의 권한은 세 함수의 EXECUTE뿐이고, 앱 롤은 그 롤로 SET ROLE만 할 수 있다(권한을 물려받지
 * 않는다). 함수의 정의자는 테이블 소유자가 아닌 전용 롤이며, 불변 트리거는 "정의자 롤 + 함수 표식"일 때만 지정 컬럼을 NULL로 바꾸게 한다.
 * 함수는 봉인된 종료 상태·보존기한 경과·활성 보류 없음·미파기·키 파기 선행·바인딩된 테넌트를 확인한다(GD114).
 */
class DestroyerRoleIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("DST");
    private static final String OTHER = SeedData.uniqueTenant("DSO");
    private static final LocalDate RETAINED_UNTIL = LocalDate.parse(SeedData.RETENTION_UNTIL);
    private static final LocalDate AFTER = RETAINED_UNTIL.plusDays(1);
    private static final OffsetDateTime AT = OffsetDateTime.parse("2031-09-24T09:00:00+09:00");
    private static final String SHRED = "SELECT ga_document_key_shred(?, ?, ?, ?, ?)";
    private static final String DESTROY = "SELECT ga_disclosure_destroy(?, ?, ?, ?, ?)";
    private static final String DESTROY_CUSTOMER = "SELECT ga_customer_ref_destroy(?, ?, ?, ?)";
    private static final String WHERE = " WHERE tenant_id = ? AND disclosure_id = ?";

    /** 파기 대상 컬럼(5 계획 §5.6 — 확인서 함수). */
    private static final Map<String, List<String>> ERASED = Map.of(
            "disclosure", List.of("void_reason_text", "supersede_reason_text", "policy_no", "destroyed_at", "destroyed_by"),
            "recommendation", List.of("reason_text"),
            "review", List.of("reason"),
            "signature", List.of("device", "ip", "view_evidence"),
            "sign_session", List.of("view_evidence"),
            "compliance_flag", List.of("policy_no"),
            "document_key", List.of("wrapped_dek", "shredded_at", "shredded_by"));

    @BeforeAll
    static void seedTenants() {
        for (String t : List.of(T, OTHER)) {
            DB.seed(t, c -> {
                SeedData.tenant(c, t);
                SeedData.dataKey(c, t, SeedData.SEED_KEY_ID);
            });
        }
    }

    private record Doc(UUID id, String customer) {
    }

    private static String newCustomer() {
        return "CR-" + UUID.randomUUID().toString().replace("-", "");
    }

    /** 파기 대상 컬럼을 전부 가진 완료(또는 지정 종료 상태) 확인서: 추천사유 텍스트·예외 승인 사유·서명 기기/IP/열람·세션 열람·증권번호·플래그. */
    private static Doc terminal(String status) {
        String customer = newCustomer();
        UUID[] id = new UUID[1];
        DB.seed(T, c -> {
            SeedData.customer(c, T, customer);
            id[0] = SeedData.disclosure(c, T, "REASONED", null, "DISC-2026-07", customer);
            SeedData.item(c, T, id[0], 1, "0.84");
            SeedData.recommendation(c, T, id[0], 1);
            SeedData.exec(c, "UPDATE recommendation SET reason_text = '고객 요청: 보장 범위 우선'" + WHERE, T, id[0]);
            SeedData.review(c, T, id[0], "R-TEMP-PRODUCT");
            SeedData.seal(c, T, id[0], SeedData.hash('d'), "SEALED");
            SeedData.documentKey(c, T, id[0]);
            SeedData.customerSignatureWithEvidence(c, T, id[0], SeedData.hash('d'));
            switch (status) {
                case "COMPLETED" -> SeedData.exec(c, "UPDATE disclosure SET status = 'COMPLETED', completed_at = TIMESTAMPTZ '2026-09-24 10:00:00+09'"
                        + WHERE, T, id[0]);
                case "VOID" -> SeedData.exec(c, """
                        UPDATE disclosure SET status = 'VOID', voided_at = TIMESTAMPTZ '2026-09-24 09:00:00+09',
                                              void_reason_code = 'CUSTOMER_CANCELLED', void_reason_text = '고객 변심'
                        """ + WHERE, T, id[0]);
                case "SUPERSEDED" -> SeedData.exec(c, """
                        UPDATE disclosure SET status = 'SUPERSEDED', superseded_by_id = gen_random_uuid(),
                                              supersede_reason_code = 'CONTENT_ERROR', supersede_reason_text = '오기 정정'
                        """ + WHERE, T, id[0]);
                case "SEALED" -> {
                }
                default -> SeedData.exec(c, "UPDATE disclosure SET status = ?" + WHERE, status, T, id[0]);
            }
            SeedData.exec(c, "UPDATE disclosure SET policy_no = 'POL-77'" + WHERE, T, id[0]);
            SeedData.exec(c, """
                    INSERT INTO compliance_flag (tenant_id, flag_id, type, severity, raised_at, disclosure_id, policy_no)
                    VALUES (?, ?, 'MISSING', 'HIGH', TIMESTAMPTZ '2026-10-01 00:00:00+09', ?, 'POL-77')
                    """, T, UUID.randomUUID(), id[0]);
        });
        return new Doc(id[0], customer);
    }

    /** 앱 롤 트랜잭션 하나: SET LOCAL ROLE disclosure_destroyer → 함수들 → RESET ROLE → 커밋(승인 Q2). */
    private static void destroyAsApp(String tenant, UUID id, LocalDate asOf, boolean shredFirst) {
        DB.asAppCommitting(tenant, c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_destroyer");
            if (shredFirst) {
                SeedData.call(c, SHRED, T, id, asOf, AT, "RETENTION:test");
            }
            SeedData.call(c, DESTROY, T, id, asOf, AT, "RETENTION:test");
            SeedData.exec(c, "RESET ROLE");
            return null;
        });
    }

    private static String shredState(UUID id, LocalDate asOf) {
        return sqlStateOf(() -> DB.asApp(T, c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_destroyer");
            return SeedData.call(c, SHRED, T, id, asOf, AT, "RETENTION:test");
        }));
    }

    private static String destroyState(String bound, String tenantArg, UUID id, LocalDate asOf) {
        return sqlStateOf(() -> DB.asApp(bound, c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_destroyer");
            return SeedData.call(c, DESTROY, tenantArg, id, asOf, AT, "RETENTION:test");
        }));
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

    private static List<String> strings(Connection c, String sql, Object... params) throws SQLException {
        List<String> out = new ArrayList<>();
        try (var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    StringBuilder row = new StringBuilder();
                    for (int col = 1; col <= rs.getMetaData().getColumnCount(); col++) {
                        row.append(col == 1 ? "" : "|").append(rs.getString(col));
                    }
                    out.add(row.toString());
                }
            }
        }
        return out;
    }

    /** 대상 확인서에 속한 행들을 표마다 to_jsonb로 읽는다(키 정렬은 jsonb가 정한다 — 비교는 같은 표현끼리). */
    private static Map<String, List<String>> rows(UUID id) {
        return DB.asApp(T, c -> {
            Map<String, List<String>> out = new java.util.TreeMap<>();
            for (String table : ERASED.keySet()) {
                out.put(table, strings(c, "SELECT to_jsonb(x)::text FROM " + table + " x WHERE x.tenant_id = ? AND x.disclosure_id = ? ORDER BY 1",
                        T, id));
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ 카탈로그: 권한은 함수뿐

    @Test
    void theDestroyerHoldsOnlyTheThreeFunctionsAndTheAppMayOnlySwitchToIt() {
        asSuperuser(T, c -> {
            assertThat(strings(c, "SELECT table_name || ':' || privilege_type FROM information_schema.role_table_grants WHERE grantee = 'disclosure_destroyer'"))
                    .isEmpty();
            assertThat(strings(c, "SELECT table_name || '.' || column_name FROM information_schema.role_column_grants WHERE grantee = 'disclosure_destroyer'"))
                    .isEmpty();
            assertThat(strings(c, """
                    SELECT p.proname FROM pg_proc p
                     WHERE has_function_privilege('disclosure_destroyer', p.oid, 'EXECUTE')
                       AND p.pronamespace = 'public'::regnamespace AND NOT has_function_privilege('public', p.oid, 'EXECUTE')
                     ORDER BY 1
                    """)).containsExactly("ga_customer_ref_destroy", "ga_disclosure_destroy", "ga_document_key_shred");
            // 멤버십: 앱 → 파기자(SET만, 물려받지 않음, 관리 권한 없음), 마이그레이터 → 정의자(소유권 이전용). 그 밖에 없다.
            assertThat(strings(c, """
                    SELECT m.rolname || '>' || r.rolname || ':' || a.set_option || a.inherit_option || a.admin_option
                      FROM pg_auth_members a JOIN pg_roles r ON r.oid = a.roleid JOIN pg_roles m ON m.oid = a.member
                     WHERE r.rolname LIKE 'disclosure%' OR m.rolname LIKE 'disclosure%'
                     ORDER BY 1
                    """)).containsExactly("disclosure_app>disclosure_destroyer:truefalsefalse",
                    "disclosure_migrator>disclosure_destroy_definer:truefalsefalse");
            // 세 함수: 정의자 소유, SECURITY DEFINER, search_path 고정
            assertThat(strings(c, """
                    SELECT p.proname || ':' || pg_get_userbyid(p.proowner) || ':' || p.prosecdef || ':' || array_to_string(p.proconfig, ',')
                      FROM pg_proc p WHERE p.proname IN ('ga_document_key_shred', 'ga_disclosure_destroy', 'ga_customer_ref_destroy') ORDER BY 1
                    """)).allSatisfy(row -> assertThat(row).contains(":disclosure_destroy_definer:true:search_path=public, pg_temp"))
                    .hasSize(3);
            // 정의자: 표 전체 갱신·삽입·삭제 권한 없음(지정 컬럼만), 테이블 소유자 아님
            assertThat(strings(c, """
                    SELECT table_name || ':' || privilege_type FROM information_schema.role_table_grants
                     WHERE grantee = 'disclosure_destroy_definer' AND privilege_type <> 'SELECT'
                    """)).isEmpty();
            assertThat(strings(c, "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tableowner = 'disclosure_destroy_definer'"))
                    .isEmpty();
            assertThat(strings(c, """
                    SELECT has_column_privilege('disclosure_destroy_definer', 'disclosure', 'policy_no', 'UPDATE')
                        || ':' || has_column_privilege('disclosure_destroy_definer', 'disclosure', 'status', 'UPDATE')
                        || ':' || has_column_privilege('disclosure_destroy_definer', 'disclosure', 'chain_hash', 'UPDATE')
                        || ':' || has_column_privilege('disclosure_destroy_definer', 'signature', 'signed_doc_hash', 'UPDATE')
                    """)).containsExactly("true:false:false:false");
            return null;
        });
    }

    // ------------------------------------------------------------------ 경로 밖은 전부 거부

    @ParameterizedTest
    @ValueSource(strings = {SHRED, DESTROY})
    void theAppRoleCannotCallTheFunctionsWithoutSetRole(String sql) {
        Doc d = terminal("COMPLETED");
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> SeedData.call(c, sql, T, d.id(), AFTER, AT, "app")))).isEqualTo("42501");
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> SeedData.call(c, DESTROY_CUSTOMER, T, d.customer(), AT, "app")))).isEqualTo("42501");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE disclosure SET policy_no = NULL" + WHERE,
            "UPDATE recommendation SET reason_text = NULL" + WHERE,
            "UPDATE signature SET ip = NULL" + WHERE,
            "UPDATE document_key SET wrapped_dek = NULL, shredded_at = now(), shredded_by = 'x'" + WHERE,
            "SELECT 1 FROM disclosure" + WHERE})
    void theDestroyerRoleCannotTouchTablesDirectly(String sql) {
        Doc d = terminal("COMPLETED");
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_destroyer");
            return sql.startsWith("SELECT") ? SeedData.call(c, sql, T, d.id()) : SeedData.exec(c, sql, T, d.id());
        }))).isEqualTo("42501");
    }

    @Test
    void theMarkerAloneOpensNothingForTheAppRole() {
        Doc d = terminal("COMPLETED");
        String[] states = new String[2];
        states[0] = sqlStateOf(() -> DB.asApp(T, c -> {
            SeedData.call(c, "SELECT set_config('ga.destroy', 'disclosure:' || ?, true)", d.id().toString());
            return SeedData.exec(c, "UPDATE disclosure SET destroyed_at = now(), destroyed_by = 'app'" + WHERE, T, d.id());
        }));
        states[1] = sqlStateOf(() -> DB.asApp(T, c -> {
            SeedData.call(c, "SELECT set_config('ga.destroy', 'disclosure:' || ?, true)", d.id().toString());
            return SeedData.exec(c, "UPDATE recommendation SET reason_text = NULL" + WHERE, T, d.id());
        }));
        assertThat(states).containsExactly("GD113", "GD010");
    }

    @Test
    void theDefinerWithoutTheMarkerIsRejectedByTheTriggers() {
        Doc d = terminal("COMPLETED");
        List<String> states = new ArrayList<>();
        for (String sql : List.of(
                "UPDATE disclosure SET policy_no = NULL, destroyed_at = now(), destroyed_by = 'definer'" + WHERE,
                "UPDATE signature SET ip = NULL" + WHERE,
                "UPDATE review SET reason = NULL" + WHERE,
                "UPDATE document_key SET wrapped_dek = NULL, shredded_at = now(), shredded_by = 'definer'" + WHERE)) {
            states.add(sqlStateOf(() -> DB.seed(T, c -> {
                SeedData.exec(c, "SET LOCAL ROLE disclosure_destroy_definer");
                SeedData.exec(c, sql, T, d.id());
            })));
        }
        assertThat(states).containsExactly("GD113", "GD030", "GD030", "GD092");
    }

    /** 정의자가 표식을 갖고도 지정 외 컬럼을 바꾸면 트리거가 거부한다(권한은 이 테스트에서만 잠시 준다 — 롤백). */
    @Test
    void insideTheBranchOnlyTheDesignatedColumnsMayChange() {
        Doc d = terminal("COMPLETED");
        List<String> states = new ArrayList<>();
        String[][] cases = {
                {"GRANT UPDATE (reason_codes) ON recommendation TO disclosure_destroy_definer",
                        "UPDATE recommendation SET reason_codes = ARRAY['X'], reason_text = NULL" + WHERE},
                {"GRANT UPDATE (agent_id) ON disclosure TO disclosure_destroy_definer",
                        "UPDATE disclosure SET agent_id = 'X', policy_no = NULL, destroyed_at = now(), destroyed_by = 'definer'" + WHERE},
                {null, "UPDATE disclosure SET policy_no = 'POL-NEW', destroyed_at = now(), destroyed_by = 'definer'" + WHERE},
                {"GRANT UPDATE (channel) ON signature TO disclosure_destroy_definer",
                        "UPDATE signature SET channel = 'REMOTE_LINK', ip = NULL" + WHERE}};
        for (String[] grantAndSql : cases) {
            states.add(sqlStateOf(() -> asSuperuser(T, c -> {
                if (grantAndSql[0] != null) {
                    SeedData.exec(c, grantAndSql[0]);
                }
                SeedData.exec(c, "SET LOCAL ROLE disclosure_destroy_definer");
                SeedData.call(c, "SELECT set_config('ga.destroy', 'disclosure:' || ?, true)", d.id().toString());
                return SeedData.exec(c, grantAndSql[1], T, d.id());
            })));
        }
        assertThat(states).containsExactly("GD113", "GD113", "GD113", "GD030");
    }

    /** V11: 보류 사유 텍스트는 표식이 가리키는 확인서의 해제된 보류에서만, reason_text만 NULL이 된다. */
    @Test
    void aHoldReasonTextIsErasedOnlyOnAReleasedHoldInsideTheBranch() {
        Doc d = terminal("COMPLETED");
        UUID released = UUID.randomUUID();
        UUID active = UUID.randomUUID();
        String place = "INSERT INTO legal_hold (tenant_id, hold_id, disclosure_id, reason_code, reason_text, placed_by, placed_at)"
                + " VALUES (?, ?, ?, 'OTHER', '허구 메모', 'compliance@x', now())";
        DB.seed(T, c -> {
            SeedData.exec(c, place, T, released, d.id());
            SeedData.exec(c, "UPDATE legal_hold SET released_at = now(), released_by = 'compliance-2@x', release_reason_code = 'CASE_CLOSED'"
                    + " WHERE tenant_id = ? AND hold_id = ?", T, released);
            SeedData.exec(c, place, T, active, d.id());
        });
        String erase = "UPDATE legal_hold SET reason_text = NULL WHERE tenant_id = ? AND hold_id = ?";
        List<String> states = new ArrayList<>();
        String[][] cases = {
                {null, "", erase},                                                     // 표식 없음
                {null, "disclosure:" + UUID.randomUUID(), erase},                    // 다른 확인서의 표식
                {null, "disclosure:" + d.id(), "ACTIVE"},                             // 활성 보류
                {"GRANT UPDATE (reason_code) ON legal_hold TO disclosure_destroy_definer", "disclosure:" + d.id(),
                        "UPDATE legal_hold SET reason_code = 'LITIGATION', reason_text = NULL WHERE tenant_id = ? AND hold_id = ?"}};
        for (String[] c3 : cases) {
            UUID target = "ACTIVE".equals(c3[2]) ? active : released;
            String sql = "ACTIVE".equals(c3[2]) ? erase : c3[2];
            states.add(sqlStateOf(() -> asSuperuser(T, c -> {
                if (c3[0] != null) {
                    SeedData.exec(c, c3[0]);
                }
                SeedData.exec(c, "SET LOCAL ROLE disclosure_destroy_definer");
                SeedData.call(c, "SELECT set_config('ga.destroy', ?, true)", c3[1]);
                return SeedData.exec(c, sql, T, target);
            })));
        }
        assertThat(states).containsExactly("GD112", "GD112", "GD113", "GD113");
        int changed = asSuperuser(T, c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_destroy_definer");
            SeedData.call(c, "SELECT set_config('ga.destroy', ?, true)", "disclosure:" + d.id());
            return SeedData.exec(c, erase, T, released);
        });
        assertThat(changed).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 함수의 판정(GD114)

    @Test
    void theFunctionsRefuseWhatIsNotYetDestroyable() {
        Doc sealed = terminal("SEALED");
        Doc retained = terminal("COMPLETED");
        Doc heldDoc = terminal("COMPLETED");
        Doc heldCustomer = terminal("EXPIRED");
        Doc keyAlive = terminal("VOID");
        DB.seed(T, c -> {
            SeedData.exec(c, "INSERT INTO legal_hold (tenant_id, hold_id, disclosure_id, reason_code, placed_by, placed_at) VALUES (?, ?, ?, 'LITIGATION', 'c@x', now())",
                    T, UUID.randomUUID(), heldDoc.id());
            SeedData.exec(c, "INSERT INTO legal_hold (tenant_id, hold_id, customer_ref, reason_code, placed_by, placed_at) VALUES (?, ?, ?, 'LITIGATION', 'c@x', now())",
                    T, UUID.randomUUID(), heldCustomer.customer());
        });
        assertThat(shredState(sealed.id(), AFTER)).as("not a terminal state").isEqualTo("GD114");
        assertThat(shredState(retained.id(), RETAINED_UNTIL)).as("retention day not over").isEqualTo("GD114");
        assertThat(shredState(heldDoc.id(), AFTER)).as("disclosure hold").isEqualTo("GD114");
        assertThat(shredState(heldCustomer.id(), AFTER)).as("customer hold").isEqualTo("GD114");
        assertThat(destroyState(T, T, keyAlive.id(), AFTER)).as("key must be shredded first").isEqualTo("GD114");
        assertThat(destroyState(T, OTHER, keyAlive.id(), AFTER)).as("tenant argument must be the bound tenant").isEqualTo("GD114");
        assertThat(destroyState(OTHER, OTHER, keyAlive.id(), AFTER)).as("another tenant cannot see it").isEqualTo("GD114");
        destroyAsApp(T, keyAlive.id(), AFTER, true);
        assertThat(destroyState(T, T, keyAlive.id(), AFTER)).as("already destroyed").isEqualTo("GD114");
        assertThat(shredState(keyAlive.id(), AFTER)).as("already destroyed — no live key either").isEqualTo("GD114");
    }

    // ------------------------------------------------------------------ 정상 경로: 지정 컬럼만 NULL, 묘비

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "VOID", "EXPIRED", "SUPERSEDED"})
    void destructionNullsOnlyTheDesignatedColumnsAndLeavesATombstone(String status) {
        Doc d = terminal(status);
        Map<String, List<String>> before = rows(d.id());
        destroyAsApp(T, d.id(), AFTER, true);
        Map<String, List<String>> after = rows(d.id());
        assertThat(after.keySet()).isEqualTo(before.keySet());
        for (String table : ERASED.keySet()) {
            assertThat(after.get(table)).as(table).hasSameSizeAs(before.get(table)).isNotEmpty();
            for (int i = 0; i < before.get(table).size(); i++) {
                Map<String, Object> was = json(before.get(table).get(i));
                Map<String, Object> now = json(after.get(table).get(i));
                for (String col : ERASED.get(table)) {
                    if (col.equals("destroyed_at") || col.equals("destroyed_by") || col.equals("shredded_at") || col.equals("shredded_by")) {
                        assertThat(now.get(col)).as(table + "." + col).isNotNull();
                    } else {
                        assertThat(now.get(col)).as(table + "." + col).isNull();
                    }
                    was.remove(col);
                    now.remove(col);
                }
                assertThat(now).as(table + " tombstone keeps everything else").isEqualTo(was);
            }
        }
        // 묘비는 더 바뀌지 않는다(증권번호 연결도)
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> SeedData.exec(c, "UPDATE disclosure SET policy_no = 'POL-AGAIN'" + WHERE, T, d.id()))))
                .isEqualTo("GD113");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String text) {
        return new java.util.HashMap<String, Object>(tools.jackson.databind.json.JsonMapper.builder().build().readValue(text, Map.class));
    }

    // ------------------------------------------------------------------ 고객

    @Test
    void aCustomerIsDestroyedOnlyWithoutLiveDisclosuresOrHoldAndThenStaysTombstoned() {
        Doc d = terminal("COMPLETED");
        String customerState = sqlStateOf(() -> DB.asApp(T, c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_destroyer");
            return SeedData.call(c, DESTROY_CUSTOMER, T, d.customer(), AT, "RETENTION:test");
        }));
        assertThat(customerState).as("a live disclosure remains").isEqualTo("GD114");
        destroyAsApp(T, d.id(), AFTER, true);
        UUID hold = UUID.randomUUID();
        DB.seed(T, c -> SeedData.exec(c, "INSERT INTO legal_hold (tenant_id, hold_id, customer_ref, reason_code, placed_by, placed_at) VALUES (?, ?, ?, 'LITIGATION', 'c@x', now())",
                T, hold, d.customer()));
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_destroyer");
            return SeedData.call(c, DESTROY_CUSTOMER, T, d.customer(), AT, "RETENTION:test");
        }))).as("customer hold").isEqualTo("GD114");
        DB.seed(T, c -> SeedData.exec(c, "UPDATE legal_hold SET released_at = now(), released_by = 'c2@x', release_reason_code = 'CLOSED' WHERE tenant_id = ? AND hold_id = ?",
                T, hold));
        DB.asAppCommitting(T, c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_destroyer");
            SeedData.call(c, DESTROY_CUSTOMER, T, d.customer(), AT, "RETENTION:test");
            return SeedData.exec(c, "RESET ROLE");
        });
        String row = DB.asApp(T, c -> SeedData.call(c, """
                SELECT (name_enc IS NULL) || ':' || (phone_enc IS NULL) || ':' || (birth_date_enc IS NULL) || ':' || (crm_customer_id IS NULL)
                       || ':' || (destroyed_at IS NOT NULL) || ':' || enc_key_id || ':' || (created_at AT TIME ZONE 'Asia/Seoul')::date
                  FROM customer_ref WHERE tenant_id = ? AND customer_ref = ?
                """, T, d.customer()));
        assertThat(row).isEqualTo("true:true:true:true:true:" + SeedData.SEED_KEY_ID + ":2026-09-01");
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> SeedData.exec(c,
                "UPDATE customer_ref SET name_enc = decode('01' || repeat('44', 28), 'hex') WHERE tenant_id = ? AND customer_ref = ?", T, d.customer()))))
                .as("a destroyed customer is not refilled").isEqualTo("GD113");
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> SeedData.exec(c,
                "DELETE FROM customer_ref WHERE tenant_id = ? AND customer_ref = ?", T, d.customer()))))
                .as("rows are still never deleted").isEqualTo("GD064");
    }

    // ------------------------------------------------------------------ SET LOCAL ROLE은 트랜잭션과 함께 끝난다

    @Test
    void theRoleSwitchEndsWithTheTransactionEvenAfterAFailure() throws SQLException {
        Doc d = terminal("SEALED");
        try (Connection c = DB.appDataSource().getConnection()) {
            c.setAutoCommit(false);
            PostgresHarness.setTenant(c, T);
            SeedData.exec(c, "SET LOCAL ROLE disclosure_destroyer");
            assertThat(SeedData.call(c, "SELECT current_user")).isEqualTo("disclosure_destroyer");
            assertThat(sqlStateOf(() -> {
                try {
                    SeedData.call(c, SHRED, T, d.id(), AFTER, AT, "x");
                } catch (SQLException e) {
                    throw new PostgresHarness.UncheckedSqlException(e);
                }
            })).isEqualTo("GD114");
            c.rollback();
            assertThat(SeedData.call(c, "SELECT current_user")).isEqualTo("disclosure_app");
            c.rollback();
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            seen.add(DB.asApp(T, c -> SeedData.call(c, "SELECT current_user")));
        }
        assertThat(seen).containsExactly("disclosure_app");
    }
}
