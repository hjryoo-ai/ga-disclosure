package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.ga.disclosure.infra.TriggerAssertions.assertAllowed;
import static com.ga.disclosure.infra.TriggerAssertions.assertRejected;

/**
 * 3A W8: V6 CHECK의 위반 조합 전부 거부, 허용 조합 전부 통과. 기대값은 DDL을 보지 않고 규칙(설계서 §5 v1.7)에서 독립 계산한다.
 * <ul>
 *   <li>항목: 산출 상태 3 × 출처 3 × 임시등록 2 × 등급 필드 유무 2 × 비율 유무 2 × 동점 유무 2 × 사유(없음·TEMP_PRODUCT·엔진 사유) 3 = 432 조합.</li>
 *   <li>헤더: 스냅샷 헤더 6개 컬럼의 NULL 조합 64 × 상태 5 = 320 조합. V7부터 봉인 이후 상태는 봉인 컬럼 없이 넣을 수 없으므로 다섯째 상태는
 *       봉인 전 무효화(VOID, 봉인 컬럼 없음 + 무효 시각·사유)다 — 봉인 컬럼과 상태의 결속은 SealColumnCheckIT.</li>
 *   <li>임시등록 정체성(상품키·발행번호), 상태·tie_break 열거, basis 객체.</li>
 * </ul>
 * 모든 시도는 disclosure_app으로 하고 롤백한다(부모는 가변 상태 DRAFT로 시드).
 */
class SnapshotColumnCheckIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("V6CK");
    private static UUID parent;

    @BeforeAll
    static void seed() {
        DB.seed(T, c -> {
            SeedData.tenant(c, T);
            parent = SeedData.disclosure(c, T, "DRAFT", null);
        });
    }

    // ------------------------------------------------------------------ 항목

    enum Status { NONE, OK, UNAVAILABLE }

    enum Source { NONE, ENGINE, LOCAL }

    enum Reason { NONE, TEMP_PRODUCT, ENGINE_REASON }

    static Stream<Arguments> itemCombinations() {
        List<Arguments> out = new ArrayList<>();
        for (Status st : Status.values()) {
            for (Source src : Source.values()) {
                for (boolean temp : new boolean[] {false, true}) {
                    for (boolean gradeFields : new boolean[] {false, true}) {
                        for (boolean ratio : new boolean[] {false, true}) {
                            for (boolean tie : new boolean[] {false, true}) {
                                for (Reason reason : Reason.values()) {
                                    out.add(Arguments.of(st, src, temp, gradeFields, ratio, tie, reason));
                                }
                            }
                        }
                    }
                }
            }
        }
        return out.stream();
    }

    /** 설계서 §5 v1.7의 세 형태 + 임시등록·로컬 출처 규칙을 그대로 옮긴 기대값. */
    static boolean itemAllowed(Status st, Source src, boolean temp, boolean gradeFields, boolean ratio, boolean tie, Reason reason) {
        boolean shape = switch (st) {
            case NONE -> !gradeFields && !ratio && !tie && reason == Reason.NONE && src == Source.NONE;
            case OK -> gradeFields && ratio && tie && reason == Reason.NONE && src == Source.ENGINE;
            case UNAVAILABLE -> !gradeFields && !ratio && !tie && reason != Reason.NONE && src != Source.NONE;
        };
        boolean tempRule = !temp || st == Status.NONE || (st == Status.UNAVAILABLE && src == Source.LOCAL && reason == Reason.TEMP_PRODUCT);
        boolean localIsTemp = src != Source.LOCAL || temp;
        return shape && tempRule && localIsTemp;
    }

    @ParameterizedTest(name = "{0} {1} temp={2} grade={3} ratio={4} tie={5} reason={6}")
    @MethodSource("itemCombinations")
    void itemGradeShapes(Status st, Source src, boolean temp, boolean gradeFields, boolean ratio, boolean tie, Reason reason) {
        String sql = """
                INSERT INTO disclosure_item (tenant_id, disclosure_id, item_no, product_key, insurer_code, group_code, product_name, temp_product,
                                             quote_doc_no, is_recommended, field_values, grade, grade_label, grade_ordinal, rank_in_set,
                                             ratio_to_avg, grade_status, tie, unavailable_reason, grade_source)
                VALUES (?, ?, 1, ?, 'INS-A', 'PG-HEALTH', '상품', ?, ?, true, '{}'::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";
        Object[] params = {T, parent, temp ? null : "INS-A:PRD-1", temp, temp ? "Q-1" : null,
                gradeFields ? "LOW" : null, gradeFields ? "낮음" : null, gradeFields ? (short) 2 : null, gradeFields ? (short) 1 : null,
                ratio ? "0.84" : null, st == Status.NONE ? null : st.name(), tie ? Boolean.FALSE : null,
                switch (reason) {
                    case NONE -> null;
                    case TEMP_PRODUCT -> "TEMP_PRODUCT";
                    case ENGINE_REASON -> "NO_RATE_DATA";
                },
                src == Source.NONE ? null : src.name()};
        if (itemAllowed(st, src, temp, gradeFields, ratio, tie, reason)) {
            assertAllowed(DB, T, sql, params);
        } else {
            assertRejected(DB, T, "23514", sql, params);
        }
    }

    /** OK 형태에서 필수 값 하나만 빠져도 거부(CHECK의 NULL 통과 함정 — 식 전체가 NULL이 되지 않게 했는지). */
    @ParameterizedTest(name = "OK without {0}")
    @ValueSource(strings = {"grade", "grade_label", "grade_ordinal", "rank_in_set", "ratio_to_avg", "tie", "grade_source"})
    void okMissingAnySingleValueIsRejected(String column) {
        List<String> cols = List.of("grade", "grade_label", "grade_ordinal", "rank_in_set", "ratio_to_avg", "tie", "grade_source");
        List<String> vals = List.of("'LOW'", "'낮음'", "2", "1", "'0.84'", "false", "'ENGINE'");
        StringBuilder c = new StringBuilder();
        StringBuilder v = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            c.append(", ").append(cols.get(i));
            v.append(", ").append(cols.get(i).equals(column) ? "NULL" : vals.get(i));
        }
        String sql = """
                INSERT INTO disclosure_item (tenant_id, disclosure_id, item_no, product_key, insurer_code, group_code, product_name, is_recommended,
                                             field_values, grade_status%s)
                VALUES (?, ?, 1, 'INS-A:PRD-1', 'INS-A', 'PG-HEALTH', '상품', true, '{}'::jsonb, 'OK'%s)""".formatted(c, v);
        assertRejected(DB, T, "23514", sql, T, parent);   // 전부 있는 OK 형태의 통과는 itemGradeShapes가 보인다
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "temp with key|'INS-A:PRD-1', true, 'Q-1'",
            "catalog without key|NULL, false, NULL",
            "temp without quote|NULL, true, NULL",
            "temp with blank quote|NULL, true, '  '",
            "catalog with quote|'INS-A:PRD-1', false, 'Q-1'"})
    void tempIdentityIsEnforced(String caseSpec) {
        String values = caseSpec.substring(caseSpec.indexOf('|') + 1);
        assertRejected(DB, T, "23514", """
                INSERT INTO disclosure_item (tenant_id, disclosure_id, item_no, product_key, temp_product, quote_doc_no, insurer_code, group_code,
                                             product_name, is_recommended, field_values)
                VALUES (?, ?, 1, %s, 'INS-A', 'PG-HEALTH', '상품', true, '{}'::jsonb)""".formatted(values), T, parent);
    }

    @Test
    void fieldValuesMustBeAnObjectAndProductKeysAreUniquePerDisclosure() {
        assertRejected(DB, T, "23514", """
                INSERT INTO disclosure_item (tenant_id, disclosure_id, item_no, product_key, insurer_code, group_code, product_name, is_recommended,
                                             field_values)
                VALUES (?, ?, 1, 'INS-A:PRD-1', 'INS-A', 'PG-HEALTH', '상품', true, '[]'::jsonb)""", T, parent);
        assertRejected(DB, T, "23505", """
                INSERT INTO disclosure_item (tenant_id, disclosure_id, item_no, product_key, insurer_code, group_code, product_name, is_recommended,
                                             field_values)
                VALUES (?, ?, 1, 'INS-A:PRD-1', 'INS-A', 'PG-HEALTH', '상품', true, '{}'::jsonb),
                       (?, ?, 2, 'INS-A:PRD-1', 'INS-A', 'PG-HEALTH', '상품', true, '{}'::jsonb)""", T, parent, T, parent);
    }

    // ------------------------------------------------------------------ 헤더

    static final List<String> HEADER = List.of("grade_snapshot_id", "grading_policy_version_id", "ranking_policy_version_id", "tie_break",
            "grade_basis", "snapshot_generated_at");
    static final List<String> VALUES = List.of("'GRD-1'", "'GRADING-2026-07'", "'RANK-2026-07'", "'SHARED_RANK'",
            "'{\"period\": \"2026Q2\"}'::jsonb", "TIMESTAMPTZ '2026-09-23 10:00:00+09'");

    static Stream<Arguments> headerCombinations() {
        List<Arguments> out = new ArrayList<>();
        for (String status : List.of("DRAFT", "COMPARED", "GRADED", "REASONED", "VOID")) {
            for (int mask = 0; mask < 64; mask++) {
                out.add(Arguments.of(status, mask));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} mask={1}")
    @MethodSource("headerCombinations")
    void snapshotHeaderIsAllOrNothingAndAbsentBeforeGrading(String status, int mask) {
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        for (int i = 0; i < HEADER.size(); i++) {
            cols.append(", ").append(HEADER.get(i));
            vals.append(", ").append((mask & (1 << i)) != 0 ? VALUES.get(i) : "NULL");
        }
        if (status.equals("VOID")) {
            cols.append(", voided_at, void_reason_code");
            vals.append(", TIMESTAMPTZ '2026-09-24 09:00:00+09', 'CUSTOMER_CANCELLED'");
        }
        String sql = """
                INSERT INTO disclosure (tenant_id, disclosure_id, agent_id, customer_ref, group_code, template_id, template_version, issuer_mode,
                                        status, consult_date, rule_version_id%s)
                VALUES (?, gen_random_uuid(), 'AGENT-1', 'C-1', 'PG-HEALTH', 'STANDARD', 1, 'SELF', ?, DATE '2026-09-23', 'DISC-2026-07'%s)"""
                .formatted(cols, vals);
        boolean none = mask == 0;
        boolean all = mask == 63;
        boolean allowed = none || (all && !status.equals("DRAFT") && !status.equals("COMPARED"));
        if (allowed) {
            assertAllowed(DB, T, sql, T, status);
        } else {
            assertRejected(DB, T, "23514", sql, T, status);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "status|'SIGNED'|NULL|NULL",
            "tie_break|'GRADED'|'DENSE_RANK'|'{}'::jsonb",
            "basis|'GRADED'|'STRICT'|'[1]'::jsonb"})
    void enumerationsAndBasisShapeAreChecked(String caseSpec) {
        String[] p = caseSpec.split("\\|");
        boolean snapshot = !p[2].equals("NULL");
        String sql = """
                INSERT INTO disclosure (tenant_id, disclosure_id, agent_id, customer_ref, group_code, template_id, template_version, issuer_mode,
                                        status, consult_date, grade_snapshot_id, grading_policy_version_id, ranking_policy_version_id, tie_break,
                                        grade_basis, snapshot_generated_at, rule_version_id)
                VALUES (?, gen_random_uuid(), 'AGENT-1', 'C-1', 'PG-HEALTH', 'STANDARD', 1, 'SELF', %s, DATE '2026-09-23', %s, %s, %s, %s, %s, %s,
                        'DISC-2026-07')"""
                .formatted(p[1], snapshot ? "'GRD-1'" : "NULL", snapshot ? "'G'" : "NULL", snapshot ? "'R'" : "NULL", p[2], p[3],
                        snapshot ? "now()" : "NULL");
        assertRejected(DB, T, "23514", sql, T);
    }
}
