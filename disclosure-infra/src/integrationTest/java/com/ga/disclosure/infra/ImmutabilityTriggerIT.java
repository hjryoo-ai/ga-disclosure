package com.ga.disclosure.infra;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.ga.disclosure.infra.TriggerAssertions.assertAllowed;
import static com.ga.disclosure.infra.TriggerAssertions.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * C7: 봉인 이후 상태(SEALED·PARTIALLY_SIGNED·COMPLETED·VOID·SUPERSEDED·EXPIRED)의 disclosure 본문 컬럼 UPDATE·행 DELETE 거부,
 * 메타 컬럼 UPDATE 허용, DRAFT~REASONED는 UPDATE 허용; 봉인된 부모의 disclosure_item·recommendation INSERT·UPDATE·DELETE 거부;
 * 봉인 이후 → 가변 상태 status UPDATE 거부(6×4 전수), 봉인 이후 상태 간 UPDATE 허용; superseded_by_id 두 번째 쓰기 거부.
 * 모든 시도는 disclosure_app으로, 트랜잭션은 시도마다 롤백한다(시드는 disclosure_migrator로 커밋).
 *
 * <p>V7(3B S4): 메타 컬럼 변경은 본문 가드(GD001)에 걸리지 않지만 이제 봉인 결속 CHECK의 판정을 받는다 — 결과 행이 규칙에 맞으면 허용,
 * 아니면 23514. 기대값은 DDL이 아니라 설계서 §5 v1.8의 규칙 문장에서 계산한다({@link #expectedMeta}): 봉인 컬럼 7개(보존기한 포함)는
 * 가변 상태에서 없고, VOID ⇔ 무효 시각 ⇔ 무효 사유, SUPERSEDED ⇔ 후속 ID, 후속 ID는 한 번만 쓴다(GD004가 CHECK보다 먼저), 보존기한은
 * 연장만(GD094). 가변 상태 행에 봉인 컬럼을 쓰는 본문 UPDATE도 같은 이유로 23514다.
 */
class ImmutabilityTriggerIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("IMM");

    static final java.util.List<String> STATUSES = SeedData.ALL_STATUSES;

    /** 본문 컬럼 → 값을 바꾸는 SET 식. tenant_id는 RLS WITH CHECK가 먼저 막으므로 별도로 다루지 않는다. */
    static final Map<String, String> BODY = new LinkedHashMap<>();
    /** 메타 컬럼 → SET 식(status는 상태별로 따로 만든다). */
    static final Map<String, String> META = new LinkedHashMap<>();

    static {
        BODY.put("disclosure_id", "gen_random_uuid()");
        BODY.put("agent_id", "'AGENT-X'");
        BODY.put("customer_ref", "'C-X'");
        BODY.put("group_code", "'PG-X'");
        BODY.put("template_id", "'TPL-X'");
        BODY.put("template_version", "template_version + 1");
        BODY.put("rule_version_id", "'DISC-X'");
        BODY.put("consult_date", "consult_date + 1");
        // 스냅샷 헤더 6개는 함께 있거나 함께 없다(V6). 값이 있으면 바꾸고 없으면 없는 채로 두는 식 — 산출 전(DRAFT·COMPARED) 시드는
        // 헤더가 없으므로 이 컬럼들의 UPDATE가 무변경이 되고, 산출 이후·봉인 이후 시드에서는 실제로 값이 바뀐다.
        BODY.put("grade_snapshot_id", "grade_snapshot_id || '-X'");
        BODY.put("issuer_mode", "'ASSOC'");
        BODY.put("version", "version + 1");
        BODY.put("supersedes_id", "gen_random_uuid()");
        BODY.put("disclosure_no", "'X-2099-' || substr(md5(random()::text), 1, 6)");
        BODY.put("sealed_at", "TIMESTAMPTZ '2030-01-01 00:00:00+00'");
        BODY.put("canonical_hash", "repeat('9', 64)");
        BODY.put("pdf_hash", "repeat('8', 64)");
        BODY.put("chain_hash", "repeat('7', 64)");
        BODY.put("chain_seq", "coalesce(chain_seq, 0) + 1");
        BODY.put("tenant_rule_version_id", "'HOUSE-X'");                       // V4: 메타 목록에 없으므로 자동으로 본문(Phase 1 C13)
        BODY.put("grading_policy_version_id", "grading_policy_version_id || '-X'");   // V6: 전부 본문(Phase 3A W8)
        BODY.put("ranking_policy_version_id", "ranking_policy_version_id || '-X'");
        BODY.put("tie_break", "CASE tie_break WHEN 'STRICT' THEN 'SHARED_RANK' WHEN 'SHARED_RANK' THEN 'STRICT' END");
        BODY.put("grade_basis", "grade_basis || '{\"period\": \"2099Q1\"}'::jsonb");
        BODY.put("snapshot_generated_at", "snapshot_generated_at + INTERVAL '1 second'");

        META.put("superseded_by_id", "gen_random_uuid()");
        META.put("completed_at", "TIMESTAMPTZ '2026-09-24 00:00:00+09'");
        META.put("voided_at", "TIMESTAMPTZ '2026-09-24 00:00:00+09'");
        META.put("void_reason_code", "'CUSTOMER_CANCELLED'");                 // V8: 무효·정정 사유 코드·텍스트(메타, 한 번만 쓴다 — GD100)
        META.put("void_reason_text", "'상담 취소'");
        META.put("supersede_reason_code", "'CONTENT_ERROR'");
        META.put("supersede_reason_text", "'오기 정정'");
        // V14: 증권번호·계약일은 그 확인서의 활성 계약 연결 값으로만 바뀐다(GD136) — 값은 연결에서 읽는다(시드가 확인서마다 연결을 심는다)
        META.put("policy_no", "(SELECT l.policy_no FROM contract_link l WHERE l.tenant_id = disclosure.tenant_id"
                + " AND l.disclosure_id = disclosure.disclosure_id AND l.superseded_by IS NULL)");
        META.put("contract_date", "(SELECT l.contract_date FROM contract_link l WHERE l.tenant_id = disclosure.tenant_id"
                + " AND l.disclosure_id = disclosure.disclosure_id AND l.superseded_by IS NULL)");
        META.put("retention_until", "DATE '2031-10-01'");
        META.put("destroyed_at", "TIMESTAMPTZ '2036-10-02 00:00:00+09'");     // V9: 파기 함수만(정의자 롤 + 표식) — 그 밖은 GD113
        META.put("destroyed_by", "'someone'");
    }

    @BeforeAll
    static void seedTenant() {
        DB.seed(T, c -> SeedData.tenant(c, T));
    }

    /**
     * disclosure의 모든 컬럼이 본문(BODY) 또는 메타(META·status)로 분류돼 있다. 마이그레이션이 컬럼을 추가하면(V4 이후) 이 테스트가
     * 분류를 강제한다 — 트리거는 메타 허용 목록 방식이라 새 컬럼이 기본 불변이지만, 매트릭스가 그 컬럼을 실제로 검사하게 한다.
     */
    @Test
    void everyDisclosureColumnIsCoveredByTheMatrix() {
        java.util.List<String> columns = DB.asApp(T, c -> {
            java.util.List<String> names = new java.util.ArrayList<>();
            try (var ps = c.prepareStatement("""
                    SELECT column_name FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name = 'disclosure' ORDER BY ordinal_position
                    """); var rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
            return names;
        });
        java.util.Set<String> classified = new java.util.HashSet<>(BODY.keySet());
        classified.addAll(META.keySet());
        classified.addAll(java.util.List.of("tenant_id", "status", "org_path",
                "application_no", "abandoned_at"));   // V14: 작성 때만·폐기 함수만(GD132·GD133) — V14GuardIT
        assertThat(columns).contains("tenant_rule_version_id", "grading_policy_version_id", "ranking_policy_version_id", "tie_break",
                "grade_basis", "snapshot_generated_at").allSatisfy(col -> assertThat(classified).contains(col));
    }

    /**
     * V12 {@code org_path}: 작성 시점 조직 스냅샷 — 상태와 무관하게 한 번 쓰고 끝이다(GD124, 6A 승인 Q1). 봉인 이후에는 본문 가드(GD001)가 먼저
     * 거부한다(트리거 이름 순서). 가변 상태에서도 거부되므로 본문(BODY)이 아니라 따로 분류한다.
     */
    @ParameterizedTest
    @org.junit.jupiter.params.provider.FieldSource("STATUSES")
    void theOrganisationPathNeverChangesInAnyStatus(String status) {
        java.util.UUID[] id = new java.util.UUID[1];
        DB.seed(T, c -> id[0] = SeedData.disclosure(c, T, status, SeedData.hash('a')));
        assertRejected(DB, T, SeedData.MUTABLE_STATUSES.contains(status) ? "GD124" : "GD001",
                "UPDATE disclosure SET org_path = '/HQ/X' WHERE tenant_id = ? AND disclosure_id = ?", T, id[0]);
    }

    static Stream<Arguments> statusTimesBody() {
        return STATUSES.stream().flatMap(s -> BODY.keySet().stream().map(col -> Arguments.of(s, col)));
    }

    static Stream<Arguments> statusTimesMeta() {
        return STATUSES.stream().flatMap(s -> META.keySet().stream().map(col -> Arguments.of(s, col)));
    }

    static Stream<Arguments> sealedTimesMutable() {
        return SeedData.SEALED_STATUSES.stream().flatMap(s -> SeedData.MUTABLE_STATUSES.stream().map(m -> Arguments.of(s, m)));
    }

    static Stream<Arguments> sealedTimesOtherSealed() {
        return SeedData.SEALED_STATUSES.stream().flatMap(s -> SeedData.SEALED_STATUSES.stream()
                .filter(o -> !o.equals(s)).map(o -> Arguments.of(s, o)));
    }

    static Stream<Arguments> statusTimesChildOperation() {
        return STATUSES.stream().flatMap(s -> Stream.of("disclosure_item", "recommendation")
                .flatMap(t -> Stream.of("INSERT", "UPDATE", "UPDATE_V6", "DELETE").map(op -> Arguments.of(s, t, op))));
    }

    /** 봉인 이후 행은 계약 연결과 함께(메타 값의 출처), 봉인 전 행은 연결 없이 — V15 GD130: 연결은 봉인 이후 확인서에만 생긴다. */
    private static UUID seedDisclosure(String status) {
        return seedDisclosure(status, !SeedData.MUTABLE_STATUSES.contains(status));
    }

    private static UUID seedDisclosure(String status, boolean withContractLink) {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> {
            id[0] = SeedData.disclosure(c, T, status, SeedData.hash('a'));
            if (withContractLink) {
                // V14: policy_no·contract_date는 활성 계약 연결의 현재값(GD136) — 메타 값(META)은 이 연결에서 읽는다
                SeedData.contractLink(c, T, id[0], "POL-" + id[0], "2026-10-01");
            }
        });
        return id[0];
    }

    // ------------------------------------------------------------------ 헤더 본문 / 메타

    /** 봉인 컬럼(V7 결속 대상). 가변 상태 행에 하나만 쓰면 전부-또는-없음 CHECK가 거부한다. */
    static final java.util.Set<String> SEAL_COLUMNS = java.util.Set.of("disclosure_no", "sealed_at", "canonical_hash", "pdf_hash",
            "chain_hash", "chain_seq");

    @ParameterizedTest(name = "{0} × body {1}")
    @MethodSource("statusTimesBody")
    void bodyColumnUpdateAllowedOnlyWhileMutable(String status, String column) {
        UUID id = seedDisclosure(status, false);       // 본문(식별 컬럼 포함) 변경 — 계약 연결이 그 ID를 참조하지 않게
        String sql = "UPDATE disclosure SET " + column + " = " + BODY.get(column) + " WHERE tenant_id = ? AND disclosure_id = ?";
        if (!SeedData.MUTABLE_STATUSES.contains(status)) {
            assertRejected(DB, T, "GD001", sql, T, id);
        } else if (SEAL_COLUMNS.contains(column)) {
            assertRejected(DB, T, "23514", sql, T, id);      // 봉인 컬럼은 봉인 경로에서 한꺼번에만(V7)
        } else {
            assertAllowed(DB, T, sql, T, id);
        }
    }

    /**
     * 메타 컬럼 하나를 바꾼 결과 행의 판정(설계서 §5 v1.8 규칙 문장): {@code null} = 허용, 아니면 기대 SQLSTATE. 시드 행은 상태와 일관된다
     * (봉인 이후 상태만 봉인 컬럼·보존기한, VOID만 무효 시각·사유 코드·텍스트, SUPERSEDED만 후속 ID·사유 코드, COMPLETED만 완료 시각).
     * V8: 사유·무효 시각·완료 시각은 한 번 쓰면 끝(GD100) — 트리거가 CHECK보다 먼저다.
     */
    static String expectedMeta(String status, String column) {
        boolean sealed = !SeedData.MUTABLE_STATUSES.contains(status);
        return switch (column) {
            case "superseded_by_id" -> status.equals("SUPERSEDED") ? "GD004" : "23514";   // 한 번만 쓰고, SUPERSEDED에만 있다(사유 코드와 함께)
            case "voided_at", "void_reason_code", "void_reason_text" -> status.equals("VOID") ? "GD100" : "23514";   // VOID ⇔ 시각 ⇔ 코드, 한 번만
            case "supersede_reason_code", "supersede_reason_text" -> status.equals("SUPERSEDED") ? "GD100" : "23514";
            case "destroyed_at", "destroyed_by" -> "GD113";                                // V9: 파기 함수 밖에서는 어떤 상태에서도
            case "completed_at" -> switch (status) {
                case "COMPLETED" -> "GD100";                                               // 한 번만
                case "VOID", "SUPERSEDED" -> null;                                         // 완료 뒤 무효·정정은 완료 시각을 유지할 수 있다
                default -> "23514";                                                        // COMPLETED·VOID·SUPERSEDED에만
            };
            case "retention_until" -> sealed ? null : "23514";                             // 봉인 컬럼과 함께만, 연장은 허용
            case "policy_no", "contract_date" -> null;
            default -> throw new IllegalArgumentException(column);
        };
    }

    @ParameterizedTest(name = "{0} × meta {1}")
    @MethodSource("statusTimesMeta")
    void metaColumnUpdateNeverHitsTheBodyGuard(String status, String column) {
        UUID id = seedDisclosure(status);
        String sql = "UPDATE disclosure SET " + column + " = " + META.get(column) + " WHERE tenant_id = ? AND disclosure_id = ?";
        String expected = expectedMeta(status, column);
        if (expected == null) {
            assertAllowed(DB, T, sql, T, id);
        } else {
            assertRejected(DB, T, expected, sql, T, id);
        }
    }

    @ParameterizedTest
    @FieldSource("STATUSES")
    void metaStatusChangeWithinSameClassAllowedAndAllMetaAtOnce(String status) {
        UUID id = seedDisclosure(status);
        // 가변 상태끼리: 산출 전(DRAFT↔COMPARED)과 산출 이후(GRADED↔REASONED) 안에서 바꾼다 — 스냅샷이 있는 행을 산출 전 상태로
        // 되돌리는 것은 V6 ck_disclosure_snapshot_state가 막는다(스냅샷을 버리는 것은 애그리게이트의 항목 변경뿐).
        String next = switch (status) {
            case "DRAFT" -> "COMPARED";
            case "COMPARED" -> "DRAFT";
            case "GRADED" -> "REASONED";
            case "REASONED" -> "GRADED";
            case "VOID" -> "EXPIRED";
            default -> "VOID";
        };
        String move = "UPDATE disclosure SET status = ?, " + companions(next) + " WHERE tenant_id = ? AND disclosure_id = ?";
        if (status.equals("SUPERSEDED")) {
            // 후속 ID는 한 번만 쓰고(GD004) SUPERSEDED에만 있으므로(V7) SUPERSEDED는 어느 상태로도 옮길 수 없다 — 상태표에서도 끝 상태다.
            assertRejected(DB, T, "23514", move, next, T, id);
            return;
        }
        if (status.equals("VOID")) {
            // V8: 무효 시각·사유는 한 번 쓰면 끝(GD100)이라 VOID도 다른 상태로 옮길 수 없다 — 상태표에서도 끝 상태다.
            assertRejected(DB, T, "GD100", move, next, T, id);
            return;
        }
        assertAllowed(DB, T, move, next, T, id);

        // 메타 전부를 상태와 함께 한 문장으로 바꿔도 본문 가드에 걸리지 않는다(값은 다음 상태와 일관되게)
        StringBuilder all = new StringBuilder("UPDATE disclosure SET status = ?, ").append(companions(next));
        if (java.util.List.of("COMPLETED", "VOID", "SUPERSEDED").contains(next)) {
            all.append(", completed_at = coalesce(completed_at, ").append(META.get("completed_at")).append(")");   // V8: 완료 시각은 한 번만
        }
        all.append(", policy_no = ").append(META.get("policy_no"))
                .append(", contract_date = ").append(META.get("contract_date"));
        if (!SeedData.MUTABLE_STATUSES.contains(next)) {
            all.append(", retention_until = ").append(META.get("retention_until"));
        }
        all.append(" WHERE tenant_id = ? AND disclosure_id = ?");
        assertAllowed(DB, T, all.toString(), next, T, id);
    }

    /** 다음 상태와 일관된 VOID 동반 컬럼(SUPERSEDED로 옮기지는 않는다 — 후속 ID 쓰기는 {@link #supersededByIdIsWriteOnce}). */
    private static String companions(String next) {
        return next.equals("VOID")
                ? "voided_at = " + META.get("voided_at") + ", void_reason_code = " + META.get("void_reason_code")
                        + ", void_reason_text = " + META.get("void_reason_text")
                : "voided_at = NULL, void_reason_code = NULL, void_reason_text = NULL";
    }

    /** 보존기한(메타)은 연장만: 같은 값·더 늦은 값 허용, 앞당기기·NULL 거부(V7 GD094, 승인 Q6). */
    @ParameterizedTest
    @FieldSource("STATUSES")
    void retentionUntilOnlyExtends(String status) {
        UUID id = seedDisclosure(status);
        String sql = "UPDATE disclosure SET retention_until = CAST(? AS date) WHERE tenant_id = ? AND disclosure_id = ?";
        if (SeedData.MUTABLE_STATUSES.contains(status)) {
            assertRejected(DB, T, "23514", sql, SeedData.RETENTION_UNTIL, T, id);    // 봉인 전에는 보존기한이 없다
            return;
        }
        assertAllowed(DB, T, sql, SeedData.RETENTION_UNTIL, T, id);
        assertAllowed(DB, T, sql, "2036-09-23", T, id);
        assertRejected(DB, T, "GD094", sql, "2031-09-22", T, id);
        assertRejected(DB, T, "GD094", "UPDATE disclosure SET retention_until = NULL WHERE tenant_id = ? AND disclosure_id = ?", T, id);
    }

    @ParameterizedTest
    @FieldSource("STATUSES")
    void bodyAndMetaTogetherRejectedAfterSealing(String status) {
        UUID id = seedDisclosure(status);
        String sql = "UPDATE disclosure SET policy_no = " + META.get("policy_no") + ", agent_id = 'AGENT-Y' WHERE tenant_id = ? AND disclosure_id = ?";
        if (SeedData.MUTABLE_STATUSES.contains(status)) {
            assertAllowed(DB, T, sql, T, id);
        } else {
            assertRejected(DB, T, "GD001", sql, T, id);
        }
    }

    @ParameterizedTest
    @FieldSource("STATUSES")
    void deleteAlwaysRejected(String status) {
        UUID id = seedDisclosure(status);
        assertRejected(DB, T, "GD002", "DELETE FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", T, id);
    }

    // ------------------------------------------------------------------ 상태 회귀 / 봉인 이후 상태 간 전이

    @ParameterizedTest(name = "{0} → {1} rejected")
    @MethodSource("sealedTimesMutable")
    void sealedCannotRegressToMutable(String from, String to) {
        UUID id = seedDisclosure(from);
        assertRejected(DB, T, "GD003", "UPDATE disclosure SET status = ? WHERE tenant_id = ? AND disclosure_id = ?", to, T, id);
    }

    /**
     * 봉인 이후 상태 간 전이는 본문 가드가 막지 않는다(전이 규칙은 상태표·애그리게이트). 동반 컬럼은 다음 상태와 일관되게 쓴다 — 무효 사유,
     * 정정 사유 코드(V8), 완료 시각(V8, 이미 있으면 그대로). 예외는 끝 상태에서 나가는 전이로, DB가 따로 막는다:
     * SUPERSEDED는 후속 ID가 남아 CHECK(23514), VOID는 무효 시각·사유를 지울 수 없고(V8 GD100), COMPLETED는 완료 시각이 남아
     * COMPLETED·VOID·SUPERSEDED 외 상태가 될 수 없다(V8 CHECK 23514).
     */
    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("sealedTimesOtherSealed")
    void sealedToSealedNotBlockedByTheBodyGuard(String from, String to) {
        UUID id = seedDisclosure(from);
        String sql = "UPDATE disclosure SET status = ?, " + companions(to)
                + (to.equals("SUPERSEDED") ? ", superseded_by_id = gen_random_uuid(), supersede_reason_code = 'CONTENT_ERROR'" : "")
                + (to.equals("COMPLETED") ? ", completed_at = coalesce(completed_at, TIMESTAMPTZ '2026-09-24 10:00:00+09')" : "")
                + " WHERE tenant_id = ? AND disclosure_id = ?";
        if (from.equals("SUPERSEDED")) {
            assertRejected(DB, T, "23514", sql, to, T, id);
        } else if (from.equals("VOID")) {
            assertRejected(DB, T, "GD100", sql, to, T, id);
        } else if (from.equals("COMPLETED") && !List.of("VOID", "SUPERSEDED").contains(to)) {
            assertRejected(DB, T, "23514", sql, to, T, id);
        } else {
            assertAllowed(DB, T, sql, to, T, id);
        }
    }

    /** 후속 ID는 SUPERSEDED에만 있고(V7) 한 번만 쓴다(GD004 — CHECK보다 먼저 트리거가 거부한다). */
    @ParameterizedTest
    @FieldSource("STATUSES")
    void supersededByIdIsWriteOnce(String status) {
        UUID id = seedDisclosure(status);
        String set = "UPDATE disclosure SET superseded_by_id = gen_random_uuid() WHERE tenant_id = ? AND disclosure_id = ?";
        if (!status.equals("SUPERSEDED")) {
            assertRejected(DB, T, "23514", set, T, id);
            return;
        }
        assertRejected(DB, T, "GD004", set, T, id);                     // 시드 봉인 경로가 이미 썼다
        assertRejected(DB, T, "GD004",
                "UPDATE disclosure SET superseded_by_id = NULL WHERE tenant_id = ? AND disclosure_id = ?", T, id);
        assertAllowed(DB, T, "UPDATE disclosure SET superseded_by_id = superseded_by_id, policy_no = " + META.get("policy_no") + " WHERE tenant_id = ? AND disclosure_id = ?", T, id);
    }

    // ------------------------------------------------------------------ 비교 항목 / 추천사유

    @ParameterizedTest(name = "{0} × {1} {2}")
    @MethodSource("statusTimesChildOperation")
    void childRowsChangeOnlyWhileParentMutable(String status, String table, String op) {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> {
            id[0] = SeedData.disclosure(c, T, "DRAFT", null);
            SeedData.item(c, T, id[0], 1, "0.84");
            SeedData.recommendation(c, T, id[0], 1);
            if (SeedData.MUTABLE_STATUSES.contains(status)) {
                SeedData.exec(c, "UPDATE disclosure SET status = ? WHERE tenant_id = ? AND disclosure_id = ?", status, T, id[0]);
            } else {
                SeedData.seal(c, T, id[0], SeedData.hash('a'), status);      // 봉인 경로 그대로(V7 결속·정합)
            }
        });
        String sql = switch (table + ":" + op) {
            case "disclosure_item:INSERT" -> """
                    INSERT INTO disclosure_item (tenant_id, disclosure_id, item_no, product_key, insurer_code, group_code, product_name,
                                                 is_recommended, field_values)
                    VALUES (?, ?, 2, 'INS-B:PRD-2', 'INS-B', 'PG-HEALTH', '끼워넣기', true, '{}'::jsonb)""";
            case "disclosure_item:UPDATE" -> "UPDATE disclosure_item SET field_values = '{\"PREMIUM\": 1}'::jsonb WHERE tenant_id = ? AND disclosure_id = ?";
            case "disclosure_item:DELETE" -> "DELETE FROM disclosure_item WHERE tenant_id = ? AND disclosure_id = ?";
            // V6 컬럼(동점·출처)도 자식 트리거가 컬럼과 무관하게 막는다(3A W8)
            case "disclosure_item:UPDATE_V6" -> "UPDATE disclosure_item SET tie = NOT tie, group_code = group_code WHERE tenant_id = ? AND disclosure_id = ?";
            case "recommendation:UPDATE_V6" -> "UPDATE recommendation SET reason_codes = reason_codes || ARRAY['CUSTOMER_REQUEST'] WHERE tenant_id = ? AND disclosure_id = ?";
            case "recommendation:INSERT" -> "INSERT INTO recommendation (tenant_id, disclosure_id, item_no, reason_codes) VALUES (?, ?, 2, ARRAY['OTHER'])";
            case "recommendation:UPDATE" -> "UPDATE recommendation SET reason_text = '변경' WHERE tenant_id = ? AND disclosure_id = ?";
            case "recommendation:DELETE" -> "DELETE FROM recommendation WHERE tenant_id = ? AND disclosure_id = ?";
            default -> throw new IllegalArgumentException(table + op);
        };
        if (SeedData.MUTABLE_STATUSES.contains(status)) {
            assertAllowed(DB, T, sql, T, id[0]);
        } else {
            assertRejected(DB, T, "GD010", sql, T, id[0]);
        }
    }

    @Test
    void childRowForMissingParentRejected() {
        assertRejected(DB, T, "GD011",
                "INSERT INTO recommendation (tenant_id, disclosure_id, item_no, reason_codes) VALUES (?, ?, 1, ARRAY['OTHER'])",
                T, UUID.randomUUID());
    }

    @Test
    void childRowCannotBeMovedFromSealedParentToDraftParent() {
        UUID[] ids = new UUID[2];
        DB.seed(T, c -> {
            ids[0] = SeedData.disclosure(c, T, "DRAFT", null);
            SeedData.item(c, T, ids[0], 1, "0.84");
            SeedData.seal(c, T, ids[0], SeedData.hash('a'), "SEALED");
            ids[1] = SeedData.disclosure(c, T, "DRAFT", null);
        });
        assertRejected(DB, T, "GD010", "UPDATE disclosure_item SET disclosure_id = ? WHERE tenant_id = ? AND disclosure_id = ?",
                ids[1], T, ids[0]);
    }

    // ------------------------------------------------------------------ 소유자도 예외 없음 / 도메인 정의와 대조

    @Test
    void ownerRoleIsAlsoBoundByTriggers() {
        UUID id = seedDisclosure("COMPLETED");
        assertThat(TriggerAssertions.sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c,
                "UPDATE disclosure SET agent_id = 'AGENT-Z' WHERE tenant_id = ? AND disclosure_id = ?", T, id))))
                .isEqualTo("GD001");
        assertThat(TriggerAssertions.sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c,
                "DELETE FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", T, id))))
                .isEqualTo("GD002");
    }

    @ParameterizedTest
    @EnumSource(DisclosureStatus.class)
    void databaseMutableSetMatchesDomainEnum(DisclosureStatus status) {
        boolean dbMutable = DB.asApp(T, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT ga_is_mutable_status(?)")) {
                ps.setString(1, status.name());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            }
        });
        assertThat(dbMutable).isEqualTo(status.isMutable());
        assertThat(SeedData.MUTABLE_STATUSES.contains(status.name())).isEqualTo(status.isMutable());
    }
}
