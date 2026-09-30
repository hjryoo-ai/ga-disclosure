package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.disclosure.GradeSource;
import com.ga.disclosure.domain.disclosure.ItemDraft;
import com.ga.disclosure.domain.disclosure.ItemGrade;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.grade.GradeSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshotItem;
import com.ga.disclosure.domain.grade.RatioLabel;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.domain.vo.SnapshotId;
import com.ga.disclosure.rules.grade.GradeConsistencyCheck;
import com.ga.disclosure.rules.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 3A W3: 임시등록 항목 — 발행번호 없으면 생성 실패, 엔진 요청에 미포함, 스냅샷 적용 시 로컬 {@code UNAVAILABLE(TEMP_PRODUCT, LOCAL)},
 * 순위 세트에서 제외(엔진 스냅샷의 순위는 요청한 항목만으로 정합). 임시등록의 오버라이드 가능한 실패는 중간 단계 전이를 막지 않는다(Q2).
 */
class TempProductTest {

    private static List<ItemDraft> withTemp() {
        return List.of(Fixtures.catalog("INS-A:PRD-1001", true), Fixtures.temp("INS-B", "Q-2026-0001", true),
                Fixtures.catalog("INS-C:PRD-3120", false));
    }

    @Test
    void quoteDocumentNumberIsRequiredToCreateATempItem() {
        assertThatThrownBy(() -> ItemDraft.temp(InsurerCode.of("INS-B"), GroupCode.of("PG-HEALTH-SIMPLE-NR"), "임시", null, true, false, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ItemDraft.temp(InsurerCode.of("INS-B"), GroupCode.of("PG-HEALTH-SIMPLE-NR"), "임시", "", true, false, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tempItemIsNotSentToTheEngine() {
        Disclosure d = Fixtures.draft();
        d.replaceItems(withTemp(), Fixtures.CHECK);
        assertThat(d.engineRequest().products()).containsExactly(ProductKey.parse("INS-A:PRD-1001"), ProductKey.parse("INS-C:PRD-3120"));

        Disclosure onlyTemp = Fixtures.draft();
        onlyTemp.replaceItems(List.of(Fixtures.temp("INS-B", "Q-1", true)), Fixtures.CHECK);
        assertThatThrownBy(onlyTemp::engineRequest).hasMessageContaining("nothing to grade");
    }

    @Test
    void tempItemIsGradedLocallyAndLeftOutOfTheRankingSet() {
        Disclosure d = Fixtures.draft();
        d.replaceItems(withTemp(), Fixtures.CHECK);
        TransitionOutcome compared = d.compare(Fixtures.CHECK);
        assertThat(compared).isInstanceOf(TransitionOutcome.Applied.class);
        assertThat(((TransitionOutcome.Applied) compared).overridableFailures()).extracting(ValidationResult::ruleId)
                .containsExactly("R-TEMP-PRODUCT");

        // 엔진은 요청한 두 상품만으로 순위를 매긴다(1..2) — 임시등록은 순위 세트 밖
        GradeSnapshot engine = new GradeSnapshot(SnapshotId.of("GRD-20260923-000002"), "GRADING-2026-07", "RANK-2026-07", TieBreak.STRICT,
                List.of(GradeSnapshotItem.ok(ProductKey.parse("INS-C:PRD-3120"), "LOW", "낮음", 2, 1, false, new RatioLabel("0.84")),
                        GradeSnapshotItem.ok(ProductKey.parse("INS-A:PRD-1001"), "MID", "보통", 3, 2, false, new RatioLabel("1.02"))));
        assertThat(GradeConsistencyCheck.violations(d.engineRequest().products(), engine, Set.of("GRADING-2026-07"),
                Set.of("RANK-2026-07"), Set.of(TieBreak.STRICT))).isEmpty();

        TransitionOutcome graded = d.applySnapshot(Fixtures.engine(engine), Fixtures.CHECK);
        assertThat(graded).isInstanceOf(TransitionOutcome.Applied.class);
        assertThat(d.status()).isEqualTo(DisclosureStatus.GRADED);
        assertThat(((TransitionOutcome.Applied) graded).overridableFailures()).extracting(ValidationResult::ruleId)
                .containsExactly("R-GRADE-UNAVAILABLE");
        DisclosureItem temp = d.disclosureItems().get(1);
        assertThat(temp.grade()).contains(ItemGrade.Unavailable.localTempProduct());
        assertThat(temp.grade().orElseThrow().source()).isEqualTo(GradeSource.LOCAL);
        assertThat(d.disclosureItems().get(0).grade().orElseThrow()).isInstanceOfSatisfying(ItemGrade.Ok.class,
                ok -> assertThat(ok.rankInSet()).isEqualTo(2));
        assertThat(d.gradeSnapshot().orElseThrow().items()).hasSize(2);
    }

    @Test
    void aSnapshotThatDoesNotMatchTheRequestedSetIsRefused() {
        Disclosure d = Fixtures.draft();
        d.replaceItems(withTemp(), Fixtures.CHECK);
        d.compare(Fixtures.CHECK);
        GradeSnapshot extra = new GradeSnapshot(SnapshotId.of("GRD-20260923-000003"), "GRADING-2026-07", "RANK-2026-07", TieBreak.STRICT,
                List.of(GradeSnapshotItem.ok(ProductKey.parse("INS-A:PRD-1001"), "LOW", "낮음", 2, 1, false, new RatioLabel("0.84"))));
        assertThatThrownBy(() -> d.applySnapshot(Fixtures.engine(extra), Fixtures.CHECK)).hasMessageContaining("requested items");
        assertThat(d.status()).isEqualTo(DisclosureStatus.COMPARED);
    }
}
