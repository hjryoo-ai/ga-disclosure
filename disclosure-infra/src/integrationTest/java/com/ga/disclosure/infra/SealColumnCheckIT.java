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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3B S4: 봉인 컬럼–상태 결속 CHECK(V7)의 위반 조합 전부 거부, 허용 조합 전부 통과. 기대값은 DDL을 보지 않고 규칙 문장(설계서 §5 v1.8,
 * 3B 계획 승인 Q8)에서 독립 계산한다({@link #allowed}) — 3A W8 방식.
 * <ul>
 *   <li>봉인 컬럼 7개(번호·봉인 시각·canonical·PDF·체인 해시·체인 순번·보존기한)의 NULL 조합 128 × 상태 10 = 1280 조합.</li>
 *   <li>VOID ⇔ 무효 시각 ⇔ 무효 사유(공백 사유 거부), SUPERSEDED ⇔ 후속 ID, 고정 룰 필수, 번호·해시 형식.</li>
 *   <li>봉인 정합 GD095: 번호 = 그 테넌트·연도 카운터의 현재 값, 번호 연도 = 봉인 시각의 Asia/Seoul 연도, 체인 = 머리 + 1·식.</li>
 * </ul>
 * 모든 시도는 disclosure_app으로 하고 롤백한다. 봉인 컬럼이 다 있는 행은 정합 트리거를 통과하도록 같은 트랜잭션에서 카운터를 1로 채번하고
 * 체인은 머리 없음(prev = 0×64, 순번 1)으로 계산한다 — 그래야 CHECK만의 판정이 드러난다.
 */
class SealColumnCheckIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("V7CK");

    /** 결속 대상 컬럼과 "있음" 값. 순서가 비트 위치다. */
    static final List<String> SEAL = List.of("disclosure_no", "sealed_at", "canonical_hash", "pdf_hash", "chain_hash", "chain_seq",
            "retention_until");
    static final String CANONICAL = SeedData.hash('a');
    static final String PDF = SeedData.hash('b');
    static final String CHAIN = SeedData.chainHash(SeedData.hash('0'), CANONICAL, PDF);
    static final String NUMBER = T + "-2026-000001";

    @BeforeAll
    static void seed() {
        DB.seed(T, c -> SeedData.tenant(c, T));
    }

    // ------------------------------------------------------------------ 오라클

    static boolean mutable(String status) {
        return SeedData.MUTABLE_STATUSES.contains(status);
    }

    /** 설계서 §5 v1.8: 봉인 컬럼은 전부 있거나 전부 없고, 가변 상태는 없음, VOID는 둘 다, 나머지 봉인 이후 상태는 전부. */
    static boolean allowed(String status, int mask) {
        boolean all = mask == (1 << SEAL.size()) - 1;
        boolean none = mask == 0;
        if (!all && !none) {
            return false;
        }
        if (mutable(status)) {
            return none;
        }
        return status.equals("VOID") || all;
    }

    // ------------------------------------------------------------------ 실행

    private static String insert(String status, String no, String sealedAt, String canonical, String pdf, String chain, Long chainSeq,
                                 String retention, String voidedAt, String voidReason, boolean superseded, String ruleVersion) {
        return sqlStateOrNull(() -> DB.asApp(T, c -> {
            SeedData.exec(c, "INSERT INTO disclosure_counter (tenant_id, year, seq) VALUES (?, 2026, 1)", T);
            return SeedData.exec(c, """
                    INSERT INTO disclosure (tenant_id, disclosure_id, agent_id, customer_ref, group_code, template_id, template_version,
                                            issuer_mode, status, consult_date, rule_version_id, disclosure_no, sealed_at, canonical_hash,
                                            pdf_hash, chain_hash, chain_seq, retention_until, voided_at, void_reason, superseded_by_id)
                    VALUES (?, gen_random_uuid(), 'AGENT-1', 'C-1', 'PG-HEALTH', 'STANDARD', 1, 'SELF', ?, DATE '2026-09-23', ?, ?,
                            CAST(? AS timestamptz), ?, ?, ?, ?, CAST(? AS date), CAST(? AS timestamptz), ?,
                            CASE WHEN ? THEN gen_random_uuid() END)
                    """, T, status, ruleVersion, no, sealedAt, canonical, pdf, chain, chainSeq, retention, voidedAt, voidReason, superseded);
        }));
    }

    private static String sealedRow(String status, String no, String sealedAt, String canonical, String chain, Long chainSeq) {
        return insert(status, no, sealedAt, canonical, PDF, chain, chainSeq, SeedData.RETENTION_UNTIL, voidedAt(status),
                voidReason(status), status.equals("SUPERSEDED"), "DISC-2026-07");
    }

    private static String voidedAt(String status) {
        return status.equals("VOID") ? "2026-09-24 09:00:00+09" : null;
    }

    private static String voidReason(String status) {
        return status.equals("VOID") ? "상담 취소" : null;
    }

    private static String sqlStateOrNull(Runnable action) {
        try {
            action.run();
            return null;
        } catch (PostgresHarness.UncheckedSqlException e) {
            return e.sqlState();
        }
    }

    // ------------------------------------------------------------------ 결속 전수

    static Stream<Arguments> combinations() {
        List<Arguments> out = new ArrayList<>();
        for (String status : SeedData.ALL_STATUSES) {
            for (int mask = 0; mask < 1 << SEAL.size(); mask++) {
                out.add(Arguments.of(status, mask));
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} mask={1}")
    @MethodSource("combinations")
    void sealColumnsAreAllOrNoneAndBoundToStatus(String status, int mask) {
        boolean[] on = new boolean[SEAL.size()];
        for (int i = 0; i < on.length; i++) {
            on[i] = (mask & (1 << i)) != 0;
        }
        String state = insert(status, on[0] ? NUMBER : null, on[1] ? SeedData.SEALED_AT : null, on[2] ? CANONICAL : null,
                on[3] ? PDF : null, on[4] ? CHAIN : null, on[5] ? 1L : null, on[6] ? SeedData.RETENTION_UNTIL : null,
                voidedAt(status), voidReason(status), status.equals("SUPERSEDED"), "DISC-2026-07");
        assertThat(state).as("%s mask=%s", status, Integer.toBinaryString(mask)).isEqualTo(allowed(status, mask) ? null : "23514");
    }

    // ------------------------------------------------------------------ VOID·SUPERSEDED·고정 룰

    static Stream<Arguments> voidCombinations() {
        List<Arguments> out = new ArrayList<>();
        for (String status : SeedData.MUTABLE_STATUSES) {
            out.add(Arguments.of(status));
        }
        out.add(Arguments.of("VOID"));
        return out.stream().flatMap(a -> Stream.of(true, false).flatMap(voided ->
                Stream.of("NONE", "BLANK", "TEXT").map(reason -> Arguments.of(a.get()[0], voided, reason))));
    }

    /** 봉인 전 상태(가변 4 + 봉인 전 VOID)에서: VOID ⇔ 무효 시각 ⇔ 무효 사유, 사유는 공백이 아니다. */
    @ParameterizedTest(name = "{0} voided={1} reason={2}")
    @MethodSource("voidCombinations")
    void voidColumnsMatchTheStatus(String status, boolean voided, String reason) {
        String reasonValue = switch (reason) {
            case "NONE" -> null;
            case "BLANK" -> "   ";
            default -> "상담 취소";
        };
        boolean ok = status.equals("VOID") == voided && voided == (reasonValue != null) && !"BLANK".equals(reason);
        String state = insert(status, null, null, null, null, null, null, null, voided ? "2026-09-24 09:00:00+09" : null, reasonValue,
                false, "DISC-2026-07");
        assertThat(state).isEqualTo(ok ? null : "23514");
    }

    static Stream<Arguments> supersededCombinations() {
        return SeedData.ALL_STATUSES.stream().flatMap(s -> Stream.of(true, false).map(b -> Arguments.of(s, b)));
    }

    /** SUPERSEDED ⇔ 후속 ID(봉인 컬럼은 상태에 맞게 채운다 — 이 축만 바꾼다). */
    @ParameterizedTest(name = "{0} supersededBy={1}")
    @MethodSource("supersededCombinations")
    void supersededByIdOnlyOnSuperseded(String status, boolean supersededBy) {
        boolean sealed = !mutable(status) && !status.equals("VOID");
        String state = sealed
                ? insert(status, NUMBER, SeedData.SEALED_AT, CANONICAL, PDF, CHAIN, 1L, SeedData.RETENTION_UNTIL, voidedAt(status),
                        voidReason(status), supersededBy, "DISC-2026-07")
                : insert(status, null, null, null, null, null, null, null, voidedAt(status), voidReason(status), supersededBy,
                        "DISC-2026-07");
        assertThat(state).isEqualTo(status.equals("SUPERSEDED") == supersededBy ? null : "23514");
    }

    @Test
    void pinnedRuleVersionIsRequired() {
        assertThat(insert("DRAFT", null, null, null, null, null, null, null, null, null, false, null)).isEqualTo("23514");
        assertThat(insert("DRAFT", null, null, null, null, null, null, null, null, null, false, "DISC-2026-07")).isNull();
    }

    // ------------------------------------------------------------------ 형식

    @ParameterizedTest
    @ValueSource(strings = {"OTHER_GA-2026-000001", "%s-2026-00001", "%s-2026-0000001", "%s-26-000001", "%s_2026_000001", "%s-2026-00000A"})
    void disclosureNumberFormatAndTenantPrefix(String pattern) {
        String no = pattern.formatted(T);
        String state = sealedRow("SEALED", no, SeedData.SEALED_AT, CANONICAL, CHAIN, 1L);
        assertThat(state).as(no).isEqualTo("23514");
    }

    @Test
    void hashesAreLowercaseHex64() {
        String upper = CANONICAL.toUpperCase();
        assertThat(sealedRow("SEALED", NUMBER, SeedData.SEALED_AT, upper, SeedData.chainHash(SeedData.hash('0'), upper, PDF), 1L))
                .isEqualTo("23514");
        String short63 = CANONICAL.substring(1);
        assertThat(sealedRow("SEALED", NUMBER, SeedData.SEALED_AT, short63, SeedData.chainHash(SeedData.hash('0'), short63, PDF), 1L))
                .isEqualTo("23514");
        assertThat(sealedRow("SEALED", NUMBER, SeedData.SEALED_AT, CANONICAL, CHAIN, 1L)).isNull();
    }

    // ------------------------------------------------------------------ 봉인 정합(GD095)

    @Test
    void numberMustBeTheCurrentCounterValueOfTheSealYear() {
        // 카운터는 1인데 번호는 2 — 선할당·건너뛰기
        assertThat(sealedRow("SEALED", T + "-2026-000002", SeedData.SEALED_AT, CANONICAL, CHAIN, 1L)).isEqualTo("GD095");
        // 2027년 카운터 행이 없다
        assertThat(sealedRow("SEALED", T + "-2027-000001", "2027-01-02 10:00:00+09", CANONICAL, CHAIN, 1L)).isEqualTo("GD095");
        // 번호 연도 ≠ 봉인 시각의 Asia/Seoul 연도(UTC로는 2026-12-31이지만 서울은 2027-01-01)
        assertThat(sealedRow("SEALED", NUMBER, "2026-12-31 15:30:00+00", CANONICAL, CHAIN, 1L)).isEqualTo("GD095");
        // 서울 2026-12-31 23:59 = 2026년 번호
        assertThat(sealedRow("SEALED", NUMBER, "2026-12-31 23:59:00+09", CANONICAL, CHAIN, 1L)).isNull();
    }

    @Test
    void chainMustExtendTheHead() {
        assertThat(sealedRow("SEALED", NUMBER, SeedData.SEALED_AT, CANONICAL, CHAIN, 2L)).as("머리 없음 → 순번 1").isEqualTo("GD095");
        assertThat(sealedRow("SEALED", NUMBER, SeedData.SEALED_AT, CANONICAL, CHAIN, 0L)).isEqualTo("GD095");
        assertThat(sealedRow("SEALED", NUMBER, SeedData.SEALED_AT, CANONICAL, SeedData.hash('c'), 1L)).as("식이 다른 체인 해시")
                .isEqualTo("GD095");
        assertThat(sealedRow("SEALED", NUMBER, SeedData.SEALED_AT, CANONICAL, SeedData.chainHash(SeedData.hash('1'), CANONICAL, PDF), 1L))
                .as("다른 prev").isEqualTo("GD095");
    }

    @Test
    void sealingAnExistingRowIsCheckedTheSameWay() {
        // REASONED 행을 봉인 UPDATE로 봉인할 때도 같은 정합 검사(시드 봉인 경로 = 애플리케이션 봉인 경로의 SQL 모양)
        String state = sqlStateOrNull(() -> DB.asApp(T, c -> {
            java.util.UUID id = SeedData.disclosure(c, T, "REASONED", null);
            SeedData.exec(c, "INSERT INTO disclosure_counter (tenant_id, year, seq) VALUES (?, 2026, 1)", T);
            return SeedData.exec(c, """
                    UPDATE disclosure SET status = 'SEALED', disclosure_no = ?, sealed_at = CAST(? AS timestamptz), canonical_hash = ?,
                                          pdf_hash = ?, chain_hash = ?, chain_seq = 1, retention_until = CAST(? AS date)
                     WHERE tenant_id = ? AND disclosure_id = ?
                    """, NUMBER, SeedData.SEALED_AT, CANONICAL, PDF, SeedData.hash('c'), SeedData.RETENTION_UNTIL, T, id);
        }));
        assertThat(state).isEqualTo("GD095");
        // 같은 모양에 맞는 체인 해시면 통과한다(위 거부가 체인 해시 때문임을 보인다)
        assertThat(sqlStateOrNull(() -> DB.asApp(T, c -> SeedData.disclosure(c, T, "SEALED", CANONICAL)))).isNull();
    }
}
