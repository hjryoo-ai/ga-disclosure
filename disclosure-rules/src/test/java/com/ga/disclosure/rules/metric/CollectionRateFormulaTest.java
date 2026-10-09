package com.ga.disclosure.rules.metric;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.rules.metric.CollectionRates.Group;
import com.ga.disclosure.rules.metric.CollectionRates.Linked;
import com.ga.disclosure.rules.metric.CollectionRates.Result;
import com.ga.disclosure.rules.resolve.CollectionRateFormula;
import com.ga.platform.core.testing.SeededCases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 6B 계획 §5·§11 7단계: 징구율 순수 산식. 시드 고정 1,000건 — 생성된 연결 목록(기준월 안팎·파기·폐기·완료 시각의 KST 경계)을 산식과 독립된 셈(오라클)과
 * 대조하고, 입력 순서를 섞어도 같은 결과(해시 포함)인지 본다. 경계는 예시로 따로 고정한다.
 */
class CollectionRateFormulaTest {

    private static final long SEED = 0x5EED_6B07L;
    private static final YearMonth PERIOD = YearMonth.of(2026, 9);
    private static final List<String> ORGS = List.of("/HQ", "/HQ/S1", "/HQ/S2", "/HQX", "/B2");

    // ------------------------------------------------------------------ 시드 고정 1,000건

    static Stream<Arguments> cases() {
        return SeededCases.of(SEED, 1_000, r -> {
            int n = r.nextInt(0, 40);
            List<Linked> links = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                links.add(link(r, i));
            }
            List<String> unmatched = new ArrayList<>();
            int u = r.nextInt(0, 6);
            for (int i = 0; i < u; i++) {
                unmatched.add("k" + r.nextInt(0, 4));                          // 같은 키가 겹친다 — 한 번만 센다
            }
            return new Object[] {links, unmatched, r.nextLong()};
        });
    }

    private static Linked link(RandomGenerator r, int i) {
        DisclosureStatus status = DisclosureStatus.values()[r.nextInt(DisclosureStatus.values().length)];
        // 기준월 앞뒤 한 달까지 — 밖의 계약일은 세지 않는다
        LocalDate contract = PERIOD.atDay(1).minusDays(5).plusDays(r.nextInt(0, 41));
        Optional<Instant> completed = r.nextInt(4) == 0 ? Optional.empty()
                // 계약일 KST 자정 ± 이틀(분 단위) — KST 날짜 경계를 자주 밟는다
                : Optional.of(contract.atStartOfDay(ZoneOffset.ofHours(9)).toInstant().plusSeconds(60L * r.nextInt(-2 * 24 * 60, 2 * 24 * 60)));
        boolean destroyed = r.nextInt(8) == 0;
        // 폐기 묘비에는 번호가 없다(봉인 전 초안). 그 밖은 번호가 있다 — 파기 묘비도 번호를 남긴다
        Optional<String> no = status == DisclosureStatus.ABANDONED ? Optional.empty() : Optional.of("T1-2026-" + String.format("%06d", i));
        return new Linked(no, ORGS.get(r.nextInt(ORGS.size())), contract, status, completed, destroyed);
    }

    /** 오라클: 산식 코드를 쓰지 않고 정의 문장대로 다시 센다. */
    private record Expected(Set<String> inputs, Set<String> numerator) {
    }

    private static Expected expected(List<Linked> links, String org) {
        Set<String> inputs = new TreeSet<>();
        Set<String> numerator = new TreeSet<>();
        for (Linked l : links) {
            boolean inMonth = !l.contractDate().isBefore(PERIOD.atDay(1)) && !l.contractDate().isAfter(PERIOD.atEndOfMonth());
            boolean tombstone = l.destroyed() || l.status() == DisclosureStatus.ABANDONED;
            if (!inMonth || tombstone || (!org.equals("/") && !l.orgPath().equals(org))) {
                continue;
            }
            inputs.add(l.disclosureNo().orElseThrow());
            if (l.status() == DisclosureStatus.COMPLETED && l.completedAt().isPresent()
                    && !l.completedAt().get().atOffset(ZoneOffset.ofHours(9)).toLocalDate().isAfter(l.contractDate())) {
                numerator.add(l.disclosureNo().orElseThrow());
            }
        }
        return new Expected(inputs, numerator);
    }

    @ParameterizedTest
    @MethodSource("cases")
    void bothFormulasAgreeWithTheDefinitionAndIgnoreInputOrder(List<Linked> links, List<String> unmatched, long shuffleSeed) {
        for (CollectionRateFormula formula : CollectionRateFormula.values()) {
            Result result = CollectionRates.compute(formula, PERIOD, links, unmatched);
            assertThat(result.formula()).isEqualTo(formula);
            assertThat(result.tenantWide().orgPath()).isEqualTo("/");

            Set<String> orgs = links.stream().filter(l -> CollectionRates.counted(l, PERIOD)).map(Linked::orgPath).collect(Collectors.toCollection(TreeSet::new));
            assertThat(result.groups().stream().skip(1).map(Group::orgPath).toList()).containsExactlyElementsOf(orgs);

            int extra = formula == CollectionRateFormula.TARGET_INCLUDING_UNMATCHED ? new TreeSet<>(unmatched).size() : 0;
            int orgDenominators = 0;
            for (Group g : result.groups()) {
                Expected e = expected(links, g.orgPath());
                int denominator = e.inputs().size() + (g.orgPath().equals("/") ? extra : 0);
                assertThat(g.denominator()).as(g.orgPath()).isEqualTo(denominator);
                assertThat(g.numerator()).as(g.orgPath()).isEqualTo(e.numerator().size());
                assertThat(g.rateBp()).isEqualTo(denominator == 0 ? OptionalInt.empty() : OptionalInt.of(e.numerator().size() * 10_000 / denominator));
                if (!g.orgPath().equals("/")) {
                    orgDenominators += g.denominator();
                }
            }
            assertThat(result.tenantWide().denominator()).isEqualTo(orgDenominators + extra);

            List<Linked> shuffled = new ArrayList<>(links);
            Collections.shuffle(shuffled, new java.util.Random(shuffleSeed));
            List<String> unmatchedShuffled = new ArrayList<>(unmatched);
            Collections.shuffle(unmatchedShuffled, new java.util.Random(shuffleSeed));
            assertThat(CollectionRates.compute(formula, PERIOD, shuffled, unmatchedShuffled)).isEqualTo(result);
        }
    }

    // ------------------------------------------------------------------ 예시

    private static Linked completed(String no, String org, String contract, String completedAt) {
        return new Linked(Optional.of(no), org, LocalDate.parse(contract), DisclosureStatus.COMPLETED, Optional.of(Instant.parse(completedAt)), false);
    }

    @Test
    void theCompletionDayIsTheKoreanCalendarDay() {
        // 2026-09-29T15:00Z = 9/30 00:00 KST(계약일과 같은 날 — 징구), 2026-09-30T15:00Z = 10/1 00:00 KST(계약 다음 날 — 미징구)
        Result r = CollectionRates.compute(CollectionRateFormula.LINKED_COMPLETED_BY_CONTRACT_DATE, PERIOD, List.of(
                completed("N-1", "/HQ", "2026-09-30", "2026-09-29T15:00:00Z"),
                completed("N-2", "/HQ", "2026-09-30", "2026-09-30T14:59:59Z"),
                completed("N-3", "/HQ", "2026-09-30", "2026-09-30T15:00:00Z")), List.of());
        assertThat(r.tenantWide().denominator()).isEqualTo(3);
        assertThat(r.tenantWide().numerator()).isEqualTo(2);
        assertThat(r.tenantWide().rateBp()).hasValue(6_666);                  // 버림
    }

    @Test
    void tombstonesLeaveBothSidesAndAnEmptyMonthStillHasTheTenantRow() {
        Linked destroyed = new Linked(Optional.of("N-1"), "/HQ", LocalDate.parse("2026-09-10"), DisclosureStatus.COMPLETED,
                Optional.of(Instant.parse("2026-09-01T00:00:00Z")), true);
        Linked abandoned = new Linked(Optional.empty(), "/HQ", LocalDate.parse("2026-09-10"), DisclosureStatus.ABANDONED, Optional.empty(), false);
        Result r = CollectionRates.compute(CollectionRateFormula.LINKED_COMPLETED_BY_CONTRACT_DATE, PERIOD, List.of(destroyed, abandoned), List.of("k"));
        assertThat(r.groups()).hasSize(1);
        assertThat(r.tenantWide().denominator()).isZero();
        assertThat(r.tenantWide().numerator()).isZero();
        assertThat(r.tenantWide().rateBp()).isEmpty();
    }

    @Test
    void planBAddsDistinctUnmatchedOnlyToTheTenantRow() {
        List<Linked> links = List.of(completed("N-1", "/HQ", "2026-09-10", "2026-09-01T00:00:00Z"));
        Result a = CollectionRates.compute(CollectionRateFormula.LINKED_COMPLETED_BY_CONTRACT_DATE, PERIOD, links, List.of("k1", "k1", "k2"));
        Result b = CollectionRates.compute(CollectionRateFormula.TARGET_INCLUDING_UNMATCHED, PERIOD, links, List.of("k1", "k1", "k2"));
        assertThat(a.tenantWide().denominator()).isEqualTo(1);
        assertThat(b.tenantWide().denominator()).isEqualTo(3);
        assertThat(b.tenantWide().rateBp()).hasValue(3_333);
        assertThat(b.groups().get(1)).isEqualTo(a.groups().get(1));
        assertThat(b.tenantWide().inputsHash()).isNotEqualTo(a.tenantWide().inputsHash());
    }

    @Test
    void theInputsHashIsTheCanonicalHashOfTheSortedNumbers() {
        Result r = CollectionRates.compute(CollectionRateFormula.LINKED_COMPLETED_BY_CONTRACT_DATE, PERIOD, List.of(
                completed("N-2", "/HQ", "2026-09-10", "2026-09-01T00:00:00Z"),
                new Linked(Optional.of("N-1"), "/HQ", LocalDate.parse("2026-09-10"), DisclosureStatus.SEALED, Optional.empty(), false)), List.of());
        String expected = com.ga.platform.canonical.Sha256.of("{\"inputs\":[\"N-1\",\"N-2\"],\"numerator\":[\"N-2\"]}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(r.tenantWide().inputsHash()).isEqualTo(expected);
        assertThat(r.groups().get(1).inputsHash()).isEqualTo(expected);
    }

    @Test
    void inconsistentInputsFailInsteadOfBeingCounted() {
        Linked unnumbered = new Linked(Optional.empty(), "/HQ", LocalDate.parse("2026-09-10"), DisclosureStatus.SEALED, Optional.empty(), false);
        assertThatThrownBy(() -> CollectionRates.compute(CollectionRateFormula.LINKED_COMPLETED_BY_CONTRACT_DATE, PERIOD, List.of(unnumbered), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        Linked one = completed("N-1", "/HQ", "2026-09-10", "2026-09-01T00:00:00Z");
        assertThatThrownBy(() -> CollectionRates.compute(CollectionRateFormula.LINKED_COMPLETED_BY_CONTRACT_DATE, PERIOD, List.of(one, one), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CollectionRates.rateBp(2, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(CollectionRates.rateBp(1_000_000, 1_000_000)).hasValue(10_000);
    }
}
