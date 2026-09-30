package com.ga.disclosure.domain.disclosure;

import com.ga.disclosure.domain.grade.RatioLabel;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.domain.vo.ReasonCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 항목 입력의 두 형태(카탈로그·임시등록, 3A W3·Q4)와 등급 복사본·추천사유 값의 불변식. */
class ItemDraftTest {

    private static final GroupCode G = GroupCode.of("PG-HEALTH");
    private static final InsurerCode INS_B = InsurerCode.of("INS-B");

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "Q 1", "가입설계서"})
    void tempProductWithoutAValidQuoteDocumentNumberCannotBeCreated(String quote) {
        assertThatThrownBy(() -> ItemDraft.temp(INS_B, G, "임시 상품", quote, true, false, Map.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quote document number");
    }

    @Test
    void tempProductHasNoProductKeyAndCarriesItsQuoteNumber() {
        ItemDraft temp = ItemDraft.temp(INS_B, G, "임시 상품", "Q-2026-0001", true, false, Map.of());
        assertThat(temp.productKey()).isEmpty();
        assertThat(temp.quoteDocNo()).contains("Q-2026-0001");
        assertThatThrownBy(() -> new ItemDraft(ProductKey.parse("INS-B:X"), INS_B, G, "임시", true, "Q-1", true, false, Map.of()))
                .hasMessageContaining("no product key");
    }

    @Test
    void catalogProductNeedsAKeyOfItsInsurerAndNoQuoteNumber() {
        assertThat(ItemDraft.catalog(ProductKey.parse("INS-A:PRD-1"), G, "상품", false, false, Map.of()).insurer())
                .isEqualTo(InsurerCode.of("INS-A"));
        assertThatThrownBy(() -> new ItemDraft(null, INS_B, G, "상품", false, null, true, false, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ItemDraft(ProductKey.parse("INS-A:PRD-1"), INS_B, G, "상품", false, null, true, false, Map.of()))
                .hasMessageContaining("does not belong");
        assertThatThrownBy(() -> new ItemDraft(ProductKey.parse("INS-B:PRD-1"), INS_B, G, "상품", false, "Q-1", true, false, Map.of()))
                .hasMessageContaining("only a temp product");
        assertThatThrownBy(() -> ItemDraft.catalog(ProductKey.parse("INS-A:PRD-1"), G, " ", false, false, Map.of()))
                .hasMessageContaining("product name");
        assertThatThrownBy(() -> ItemDraft.catalog(ProductKey.parse("INS-A:PRD-1"), G, "상품", false, false,
                Map.of("premium", new FieldValue("1", FieldValue.Origin.CATALOG)))).hasMessageContaining("field code");
    }

    @Test
    void gradeCopiesHaveExactlyTheV6Shapes() {
        assertThat(new ItemGrade.Ok("LOW", "낮음", 2, 1, false, new RatioLabel("0.84")).source()).isEqualTo(GradeSource.ENGINE);
        assertThatThrownBy(() -> new ItemGrade.Ok("LOW", "낮음", 0, 1, false, new RatioLabel("0.84"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ItemGrade.Ok("LOW", "낮음", 2, 1, false, null)).isInstanceOf(NullPointerException.class);
        assertThat(ItemGrade.Unavailable.localTempProduct().reason()).isEqualTo(ItemGrade.TEMP_PRODUCT_REASON);
        assertThat(ItemGrade.Unavailable.engine("NO_RATE_DATA").source()).isEqualTo(GradeSource.ENGINE);
        assertThatThrownBy(() -> new ItemGrade.Unavailable("NO_RATE_DATA", GradeSource.LOCAL)).hasMessageContaining("local");
        assertThatThrownBy(() -> ItemGrade.Unavailable.engine(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recommendationNeedsCodesAndNeverInventsText() {
        Recommendation r = new Recommendation(List.of(ReasonCode.of("PREMIUM")), " ");
        assertThat(r.text()).isEmpty();
        assertThatThrownBy(() -> new Recommendation(List.of(), "텍스트")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Recommendation(List.of(ReasonCode.of("PREMIUM"), ReasonCode.of("PREMIUM")), null))
                .hasMessageContaining("duplicate");
        assertThatThrownBy(() -> new AgentReason(0, List.of(), null)).isInstanceOf(IllegalArgumentException.class);
    }
}
