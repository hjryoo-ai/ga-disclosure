package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.disclosure.GradeSource;
import com.ga.disclosure.domain.disclosure.ItemDraft;
import com.ga.disclosure.domain.disclosure.ItemGrade;
import com.ga.disclosure.domain.disclosure.Recommendation;

import java.util.Objects;
import java.util.Optional;

/**
 * 애그리게이트 안의 비교 항목(표의 열): 입력({@link ItemDraft}), 등급 복사본(산출 전이면 없음), 추천사유(없을 수 있음).
 *
 * @param itemNo 1부터, 항목 순서 그대로
 */
public record DisclosureItem(int itemNo, ItemDraft draft, ItemGrade gradeOrNull, Recommendation recommendationOrNull) {

    public DisclosureItem {
        if (itemNo < 1) {
            throw new IllegalArgumentException("itemNo starts at 1");
        }
        Objects.requireNonNull(draft, "draft");
        if (gradeOrNull instanceof ItemGrade.Unavailable u && u.source() == GradeSource.LOCAL
                && !draft.tempProduct()) {
            throw new IllegalArgumentException("only temp products carry a local grade");
        }
        if (draft.tempProduct() && gradeOrNull != null && !(gradeOrNull instanceof ItemGrade.Unavailable u2
                && u2.source() == GradeSource.LOCAL)) {
            throw new IllegalArgumentException("a temp product is graded only locally as " + ItemGrade.TEMP_PRODUCT_REASON);
        }
    }

    public Optional<ItemGrade> grade() {
        return Optional.ofNullable(gradeOrNull);
    }

    public Optional<Recommendation> recommendation() {
        return Optional.ofNullable(recommendationOrNull);
    }

    DisclosureItem withGrade(ItemGrade grade) {
        return new DisclosureItem(itemNo, draft, grade, recommendationOrNull);
    }

    DisclosureItem withRecommendation(Recommendation recommendation) {
        return new DisclosureItem(itemNo, draft, gradeOrNull, recommendation);
    }

    DisclosureItem ungraded() {
        return new DisclosureItem(itemNo, draft, null, null);
    }

    DisclosureItem withoutRecommendation() {
        return new DisclosureItem(itemNo, draft, gradeOrNull, null);
    }
}
