package com.ga.disclosure.rules.grade;

import com.ga.disclosure.domain.enums.GradeStatus;
import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.grade.GradeSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshotItem;
import com.ga.disclosure.domain.vo.ProductKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 엔진 등급·순위 스냅샷의 정합성 검증(설계서 §6.3 (i)~(v), R-RANK-MONOTONIC).
 * <ol>
 *   <li>(i) 응답 상품 집합 = 요청 집합(중복 없음).</li>
 *   <li>(ii) OK 항목 m개: STRICT면 {@code rankInSet}이 1..m 순열, SHARED_RANK면 오름차순 r₁..rₘ이 r₁=1, rₖ ∈ {rₖ₋₁, k}(경쟁 순위)이고
 *       같은 순위를 공유하는 항목은 전부 {@code tie=true}, 단독 순위는 {@code tie=false}.</li>
 *   <li>(iii) 순위-등급 단조: {@code rankInSet} 오름차순일 때 {@code gradeOrdinal}이 감소하지 않고, 동순위끼리는 같다.</li>
 *   <li>(iv) 정책 버전 2종과 {@code tieBreak}가 룰의 허용 목록에 있다.</li>
 *   <li>(v) UNAVAILABLE 항목에 등급·순위·비율이 없다(도메인 타입이 1차로 보장, 여기서 재확인).</li>
 * </ol>
 *
 * <p><b>아키텍처 테스트 허용 목록에 FQN으로 오른 유일한 클래스</b>다 — {@code GradeSnapshotItem}을 다루며 순서 API
 * ({@code Comparator}·{@code sorted}…)를 쓸 수 있는 곳은 여기뿐이고, 그때도 엔진이 준 정수 {@code rankInSet}·{@code gradeOrdinal}만
 * 쓴다. {@code ratioToAvg}는 읽지 않는다(CLAUDE.md 절대 규칙 1).
 */
public final class GradeConsistencyCheck {

    private GradeConsistencyCheck() {
    }

    /** 위반 목록(비어 있으면 정합). */
    public static List<String> violations(Collection<ProductKey> requested, GradeSnapshot snapshot,
                                          Set<String> allowedGradingPolicies, Set<String> allowedRankingPolicies,
                                          Set<TieBreak> allowedTieBreaks) {
        List<String> out = new ArrayList<>();
        sameSet(requested, snapshot.items(), out);
        List<GradeSnapshotItem> ok = snapshot.items().stream().filter(GradeSnapshotItem::isAvailable).toList();
        switch (snapshot.tieBreak()) {
            case STRICT -> strictRanks(ok, out);
            case SHARED_RANK -> sharedRanks(ok, out);
        }
        monotonic(ok, out);
        if (!allowedGradingPolicies.contains(snapshot.gradingPolicyVersionId())) {
            out.add("(iv) grading policy " + snapshot.gradingPolicyVersionId() + " is not allowed by the rule " + allowedGradingPolicies);
        }
        if (!allowedRankingPolicies.contains(snapshot.rankingPolicyVersionId())) {
            out.add("(iv) ranking policy " + snapshot.rankingPolicyVersionId() + " is not allowed by the rule " + allowedRankingPolicies);
        }
        if (!allowedTieBreaks.contains(snapshot.tieBreak())) {
            out.add("(iv) tieBreak " + snapshot.tieBreak() + " is not allowed by the rule " + allowedTieBreaks);
        }
        for (GradeSnapshotItem item : snapshot.items()) {
            if (item.status() == GradeStatus.UNAVAILABLE && (item.gradeCode() != null || item.gradeLabel() != null
                    || item.ratioToAvg() != null || item.gradeOrdinal() != 0 || item.rankInSet() != 0 || item.tie())) {
                out.add("(v) UNAVAILABLE item " + item.productKey() + " carries grade or rank fields");
            }
        }
        return List.copyOf(out);
    }

    private static void sameSet(Collection<ProductKey> requested, List<GradeSnapshotItem> items, List<String> out) {
        Set<ProductKey> seen = new HashSet<>();
        for (GradeSnapshotItem item : items) {
            if (!seen.add(item.productKey())) {
                out.add("(i) product " + item.productKey() + " appears more than once in the snapshot");
            }
        }
        Set<ProductKey> wanted = new LinkedHashSet<>(requested);
        Set<ProductKey> missing = new LinkedHashSet<>(wanted);
        missing.removeAll(seen);
        Set<ProductKey> extra = new LinkedHashSet<>(seen);
        extra.removeAll(wanted);
        if (!missing.isEmpty()) {
            out.add("(i) snapshot lacks requested products " + missing);
        }
        if (!extra.isEmpty()) {
            out.add("(i) snapshot has products that were not requested " + extra);
        }
    }

    private static List<GradeSnapshotItem> byRank(List<GradeSnapshotItem> ok) {
        return ok.stream().sorted(Comparator.comparingInt(GradeSnapshotItem::rankInSet)).toList();
    }

    private static void strictRanks(List<GradeSnapshotItem> ok, List<String> out) {
        List<GradeSnapshotItem> sorted = byRank(ok);
        for (int k = 1; k <= sorted.size(); k++) {
            if (sorted.get(k - 1).rankInSet() != k) {
                out.add("(ii) STRICT ranks of " + sorted.size() + " OK items are not a permutation of 1.." + sorted.size()
                        + ": " + sorted.stream().map(GradeSnapshotItem::rankInSet).toList());
                return;
            }
        }
    }

    private static void sharedRanks(List<GradeSnapshotItem> ok, List<String> out) {
        List<GradeSnapshotItem> sorted = byRank(ok);
        for (int k = 1; k <= sorted.size(); k++) {
            int rank = sorted.get(k - 1).rankInSet();
            boolean valid = k == 1 ? rank == 1 : rank == sorted.get(k - 2).rankInSet() || rank == k;
            if (!valid) {
                out.add("(ii) SHARED_RANK ranks are not competition ranks (1-2-2-4): "
                        + sorted.stream().map(GradeSnapshotItem::rankInSet).toList());
                return;
            }
        }
        Map<Integer, Integer> sharing = new HashMap<>();
        sorted.forEach(i -> sharing.merge(i.rankInSet(), 1, Integer::sum));
        for (GradeSnapshotItem item : sorted) {
            boolean shared = sharing.get(item.rankInSet()) > 1;
            if (item.tie() != shared) {
                out.add("(ii) " + item.productKey() + " rank " + item.rankInSet() + " has tie=" + item.tie()
                        + " but the rank is " + (shared ? "shared" : "not shared"));
            }
        }
    }

    private static void monotonic(List<GradeSnapshotItem> ok, List<String> out) {
        List<GradeSnapshotItem> sorted = byRank(ok);
        for (int k = 1; k < sorted.size(); k++) {
            GradeSnapshotItem prev = sorted.get(k - 1);
            GradeSnapshotItem next = sorted.get(k);
            if (next.gradeOrdinal() < prev.gradeOrdinal()) {
                out.add("(iii) grade ordinal decreases from rank " + prev.rankInSet() + " (" + prev.gradeOrdinal() + ") to rank "
                        + next.rankInSet() + " (" + next.gradeOrdinal() + ")");
            }
            if (next.rankInSet() == prev.rankInSet() && next.gradeOrdinal() != prev.gradeOrdinal()) {
                out.add("(iii) items sharing rank " + next.rankInSet() + " have different grade ordinals");
            }
        }
    }
}
