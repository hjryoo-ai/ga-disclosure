package com.ga.disclosure.rules.grade;

import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.grade.GradeSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshotItem;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.rules.testing.Snapshots;
import com.ga.platform.core.testing.SeededCases;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

import static com.ga.disclosure.rules.testing.Snapshots.ok;
import static com.ga.disclosure.rules.testing.Snapshots.snapshot;
import static com.ga.disclosure.rules.testing.Snapshots.unavailable;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 C8: 스냅샷 정합성(설계서 §6.3 (i)~(v)). STRICT·SHARED_RANK × 동점 유무 × UNAVAILABLE 포함 여부 매트릭스, 위반 표본,
 * 그리고 시드 고정 속성 테스트(유효 스냅샷은 통과, 서수 교란은 단조성 위반으로 잡힘).
 */
class GradeConsistencyCheckTest {

    private static final long SEED = 0x5EED_1A01L;
    private static final Set<String> GRADING = Set.of(Snapshots.GRADING);
    private static final Set<String> RANKING = Set.of(Snapshots.RANKING);
    private static final Set<TieBreak> BOTH = EnumSet.allOf(TieBreak.class);

    private static List<String> check(GradeSnapshot snapshot) {
        List<ProductKey> requested = snapshot.items().stream().map(GradeSnapshotItem::productKey).toList();
        return GradeConsistencyCheck.violations(requested, snapshot, GRADING, RANKING, BOTH);
    }

    private static List<String> check(List<String> requested, GradeSnapshot snapshot) {
        return GradeConsistencyCheck.violations(requested.stream().map(ProductKey::parse).toList(), snapshot, GRADING, RANKING, BOTH);
    }

    // ------------------------------------------------------------------ 매트릭스: 전부 정합

    static Stream<Arguments> matrix() {
        List<Arguments> out = new ArrayList<>();
        for (TieBreak tieBreak : TieBreak.values()) {
            for (boolean tie : List.of(false, true)) {
                for (boolean withUnavailable : List.of(false, true)) {
                    out.add(Arguments.of(tieBreak, tie, withUnavailable));
                }
            }
        }
        return out.stream();
    }

    @ParameterizedTest(name = "{0} tie={1} unavailable={2}")
    @MethodSource("matrix")
    void consistentSnapshotsPass(TieBreak tieBreak, boolean tie, boolean withUnavailable) {
        List<GradeSnapshotItem> items = new ArrayList<>();
        if (tieBreak == TieBreak.SHARED_RANK && tie) {
            items.add(ok("INS-A:P1", 2, 1, false));
            items.add(ok("INS-B:P2", 3, 2, true));
            items.add(ok("INS-C:P3", 3, 2, true));
            items.add(ok("INS-D:P4", 5, 4, false));
        } else if (tieBreak == TieBreak.STRICT && tie) {
            // STRICT: 1차 기준 동점을 2차 기준으로 분리 — 순위는 1..m 순열, tie 표시는 엔진 정보로 보존
            items.add(ok("INS-A:P1", 2, 1, false));
            items.add(ok("INS-B:P2", 3, 2, true));
            items.add(ok("INS-C:P3", 3, 3, true));
            items.add(ok("INS-D:P4", 5, 4, false));
        } else {
            items.add(ok("INS-A:P1", 2, 1, false));
            items.add(ok("INS-B:P2", 3, 2, false));
            items.add(ok("INS-C:P3", 5, 3, false));
        }
        if (withUnavailable) {
            items.add(1, unavailable("INS-X:TEMP-7", "NO_RATE_DATA"));   // 순위는 OK 항목만으로 매긴다
        }
        Collections.reverse(items);                                         // 엔진 응답 순서는 무관
        assertThat(check(snapshot(tieBreak, items.toArray(GradeSnapshotItem[]::new)))).isEmpty();
    }

    // ------------------------------------------------------------------ 위반 표본

    @Test
    void setMismatchIsCaught() {
        GradeSnapshot s = snapshot(TieBreak.STRICT, ok("INS-A:P1", 1, 1, false), ok("INS-B:P2", 2, 2, false));
        assertThat(check(List.of("INS-A:P1", "INS-B:P2", "INS-C:P3"), s)).anyMatch(v -> v.startsWith("(i) snapshot lacks"));
        assertThat(check(List.of("INS-A:P1"), s)).anyMatch(v -> v.startsWith("(i) snapshot has products that were not requested"));
        GradeSnapshot dup = snapshot(TieBreak.STRICT, ok("INS-A:P1", 1, 1, false), ok("INS-A:P1", 2, 2, false));
        assertThat(check(List.of("INS-A:P1"), dup)).anyMatch(v -> v.contains("more than once"));
    }

    @Test
    void strictRequiresAPermutation() {
        assertThat(check(snapshot(TieBreak.STRICT, ok("INS-A:P1", 1, 1, false), ok("INS-B:P2", 1, 1, true), ok("INS-C:P3", 2, 2, false))))
                .anyMatch(v -> v.startsWith("(ii) STRICT"));
        assertThat(check(snapshot(TieBreak.STRICT, ok("INS-A:P1", 1, 1, false), ok("INS-B:P2", 2, 3, false))))
                .anyMatch(v -> v.startsWith("(ii) STRICT"));
    }

    @Test
    void sharedRankRequiresCompetitionRanksAndTieFlags() {
        // 1-2-2-3은 경쟁 순위가 아니다(1-2-2-4)
        assertThat(check(snapshot(TieBreak.SHARED_RANK, ok("INS-A:P1", 1, 1, false), ok("INS-B:P2", 2, 2, true),
                ok("INS-C:P3", 2, 2, true), ok("INS-D:P4", 3, 3, false)))).anyMatch(v -> v.contains("competition ranks"));
        assertThat(check(snapshot(TieBreak.SHARED_RANK, ok("INS-A:P1", 1, 2, false), ok("INS-B:P2", 2, 3, false))))
                .anyMatch(v -> v.contains("competition ranks"));
        assertThat(check(snapshot(TieBreak.SHARED_RANK, ok("INS-A:P1", 1, 1, true), ok("INS-B:P2", 1, 1, false))))
                .anyMatch(v -> v.contains("tie=false but the rank is shared"));
        assertThat(check(snapshot(TieBreak.SHARED_RANK, ok("INS-A:P1", 1, 1, true), ok("INS-B:P2", 2, 2, false))))
                .anyMatch(v -> v.contains("tie=true but the rank is not shared"));
    }

    @Test
    void gradeOrdinalMustNotDecreaseAlongRankAndTiesShareIt() {
        assertThat(check(snapshot(TieBreak.STRICT, ok("INS-A:P1", 3, 1, false), ok("INS-B:P2", 2, 2, false))))
                .anyMatch(v -> v.startsWith("(iii) grade ordinal decreases"));
        assertThat(check(snapshot(TieBreak.SHARED_RANK, ok("INS-A:P1", 2, 1, true), ok("INS-B:P2", 3, 1, true))))
                .anyMatch(v -> v.startsWith("(iii) items sharing rank"));
    }

    @Test
    void policiesAndTieBreakMustBeAllowedByTheRule() {
        GradeSnapshot s = Snapshots.snapshot("GRADING-2099", "RANK-2099", TieBreak.STRICT, List.of(ok("INS-A:P1", 1, 1, false)));
        List<String> v = GradeConsistencyCheck.violations(List.of(ProductKey.parse("INS-A:P1")), s, GRADING, RANKING,
                EnumSet.of(TieBreak.SHARED_RANK));
        assertThat(v).anyMatch(x -> x.contains("grading policy GRADING-2099"))
                .anyMatch(x -> x.contains("ranking policy RANK-2099"))
                .anyMatch(x -> x.contains("tieBreak STRICT"));
    }

    // ------------------------------------------------------------------ 시드 고정 속성 테스트

    /** 유효 스냅샷 생성: OK 항목 m개(1~8), 경쟁 순위 또는 순열, 순위 따라 서수 비감소, UNAVAILABLE 0~2개, 응답 순서 섞음. */
    private static GradeSnapshot randomValid(RandomGenerator r) {
        TieBreak tieBreak = r.nextBoolean() ? TieBreak.STRICT : TieBreak.SHARED_RANK;
        int m = r.nextInt(1, 9);
        List<GradeSnapshotItem> items = new ArrayList<>();
        int ordinal = r.nextInt(1, 3);
        int k = 1;
        while (k <= m) {
            int group = tieBreak == TieBreak.SHARED_RANK ? Math.min(m - k + 1, r.nextInt(1, 4)) : 1;
            ordinal += r.nextInt(0, 2);
            for (int g = 0; g < group; g++) {
                items.add(ok("INS-" + (char) ('A' + items.size()) + ":P" + items.size(), ordinal, k, group > 1));
            }
            k += group;
        }
        int unavailable = r.nextInt(0, 3);
        for (int u = 0; u < unavailable; u++) {
            items.add(unavailable("INS-U" + u + ":TEMP-" + u, "NO_RATE_DATA"));
        }
        Collections.shuffle(items, r);
        return snapshot(tieBreak, items.toArray(GradeSnapshotItem[]::new));
    }

    static Stream<Arguments> validSnapshots() {
        return SeededCases.of(SEED, r -> new Object[] {randomValid(r)});
    }

    @ParameterizedTest
    @MethodSource("validSnapshots")
    void everyGeneratedConsistentSnapshotPasses(GradeSnapshot snapshot) {
        assertThat(check(snapshot)).isEmpty();
    }

    static Stream<Arguments> invertedSnapshots() {
        return SeededCases.of(SEED + 1, r -> {
            GradeSnapshot valid;
            do {
                valid = randomValid(r);
            } while (valid.items().stream().filter(GradeSnapshotItem::isAvailable).map(GradeSnapshotItem::gradeOrdinal).distinct().count() < 2);
            return new Object[] {valid};
        });
    }

    /** 가장 싼 순위 항목에 가장 큰 서수를 주면(엔진 이상) 단조성 위반으로 잡힌다. */
    @ParameterizedTest
    @MethodSource("invertedSnapshots")
    void raisingTheFirstRankedOrdinalAboveOthersIsCaught(GradeSnapshot valid) {
        int max = valid.items().stream().filter(GradeSnapshotItem::isAvailable).mapToInt(GradeSnapshotItem::gradeOrdinal).max().orElseThrow();
        List<GradeSnapshotItem> mutated = valid.items().stream()
                .map(i -> i.isAvailable() && i.rankInSet() == 1
                        ? ok(i.productKey().value(), max + 1, 1, i.tie())
                        : i)
                .toList();
        assertThat(check(snapshot(valid.tieBreak(), mutated.toArray(GradeSnapshotItem[]::new))))
                .anyMatch(v -> v.startsWith("(iii)"));
    }
}
