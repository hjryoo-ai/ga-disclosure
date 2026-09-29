package com.ga.disclosure.rules.testing;

import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 테스트 픽스처: 비교 항목. 메인 소스에는 구현체를 두지 않는다(Phase 3 애그리게이트가 구현). */
public record TestItem(
        ProductKey productKey,
        InsurerCode insurerCode,
        GroupCode groupCode,
        boolean isRecommended,
        boolean requestedByCustomer,
        boolean tempProduct,
        Optional<String> quoteDocNo,
        Map<String, String> fieldValues,
        List<ReasonCode> reasonCodes,
        Optional<String> reasonText) implements ValidationSubject.Item {

    public TestItem {
        fieldValues = Map.copyOf(fieldValues);
        reasonCodes = List.copyOf(reasonCodes);
    }

    /** {@code insurer}의 상품 1건, 상품군 {@code group}, 추천 아님, 사유 없음. */
    public static TestItem of(String insurer, String product, String group) {
        return new TestItem(ProductKey.parse(insurer + ":" + product), InsurerCode.of(insurer), GroupCode.of(group),
                false, false, false, Optional.empty(), Map.of(), List.of(), Optional.empty());
    }

    public TestItem recommended(String... codes) {
        return new TestItem(productKey, insurerCode, groupCode, true, requestedByCustomer, tempProduct, quoteDocNo, fieldValues,
                Arrays.stream(codes).map(ReasonCode::of).toList(), reasonText);
    }

    public TestItem reasons(String... codes) {
        return new TestItem(productKey, insurerCode, groupCode, isRecommended, requestedByCustomer, tempProduct, quoteDocNo, fieldValues,
                Arrays.stream(codes).map(ReasonCode::of).toList(), reasonText);
    }

    public TestItem text(String text) {
        return new TestItem(productKey, insurerCode, groupCode, isRecommended, requestedByCustomer, tempProduct, quoteDocNo, fieldValues,
                reasonCodes, Optional.ofNullable(text));
    }

    public TestItem requested() {
        return new TestItem(productKey, insurerCode, groupCode, isRecommended, true, tempProduct, quoteDocNo, fieldValues,
                reasonCodes, reasonText);
    }

    public TestItem temp(String quoteDocNoOrNull) {
        return new TestItem(productKey, insurerCode, groupCode, isRecommended, requestedByCustomer, true,
                Optional.ofNullable(quoteDocNoOrNull), fieldValues, reasonCodes, reasonText);
    }

    public TestItem group(String group) {
        return new TestItem(productKey, insurerCode, GroupCode.of(group), isRecommended, requestedByCustomer, tempProduct, quoteDocNo,
                fieldValues, reasonCodes, reasonText);
    }

    public TestItem field(String code, String value) {
        Map<String, String> values = new HashMap<>(fieldValues);
        values.put(code, value);
        return new TestItem(productKey, insurerCode, groupCode, isRecommended, requestedByCustomer, tempProduct, quoteDocNo, values,
                reasonCodes, reasonText);
    }

    public TestItem fields(Map<String, String> values) {
        return new TestItem(productKey, insurerCode, groupCode, isRecommended, requestedByCustomer, tempProduct, quoteDocNo, values,
                reasonCodes, reasonText);
    }
}
