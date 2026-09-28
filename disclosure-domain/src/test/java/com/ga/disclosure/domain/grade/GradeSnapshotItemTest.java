package com.ga.disclosure.domain.grade;

import com.ga.disclosure.domain.enums.GradeStatus;
import com.ga.disclosure.domain.vo.ProductKey;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GradeSnapshotItemTest {

    private static final ProductKey KEY = ProductKey.parse("INS-A:PRD-1001");

    @Test
    void okItemCarriesEngineValuesVerbatim() {
        GradeSnapshotItem item = GradeSnapshotItem.ok(KEY, "LOW", "낮음", 2, 1, false, new RatioLabel("0.840"));
        assertThat(item.isAvailable()).isTrue();
        assertThat(item.ratioToAvg()).isEqualTo(new RatioLabel("0.840")).isNotEqualTo(new RatioLabel("0.84"));
    }

    @Test
    void unavailableItemCarriesNothing() {
        GradeSnapshotItem item = GradeSnapshotItem.unavailable(ProductKey.parse("INS-D:TEMP-7"));
        assertThat(item.status()).isEqualTo(GradeStatus.UNAVAILABLE);
        assertThat(item.isAvailable()).isFalse();
        assertThat(item.gradeCode()).isNull();
        assertThat(item.ratioToAvg()).isNull();
    }

    @Test
    void rejectsInconsistentCombinations() {
        assertThatThrownBy(() -> GradeSnapshotItem.ok(KEY, "LOW", "낮음", 0, 1, false, new RatioLabel("0.84")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GradeSnapshotItem.ok(KEY, "LOW", "낮음", 2, 1, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GradeSnapshotItem(KEY, GradeStatus.UNAVAILABLE, null, null, 0, 3, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RatioLabel(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void ratioLabelIsOpaque() {
        // 원문 형식을 검사하지 않는다(절대 규칙 1): 엔진이 무엇을 보내든 그대로 보존한다.
        assertThat(new RatioLabel("84%").value()).isEqualTo("84%");
        assertThat(new RatioLabel(" 1.3700 ").value()).isEqualTo(" 1.3700 ");
        assertThat(RatioLabel.class.getRecordComponents()).extracting(RecordComponent::getName).containsExactly("value");
        assertThat(Arrays.stream(RatioLabel.class.getDeclaredMethods()).map(Method::getReturnType))
                .allMatch(t -> t == String.class || t == boolean.class || t == int.class);
    }

    @Test
    void neitherTypeIsComparable() {
        assertThat(Comparable.class.isAssignableFrom(RatioLabel.class)).isFalse();
        assertThat(Comparable.class.isAssignableFrom(GradeSnapshotItem.class)).isFalse();
    }
}
