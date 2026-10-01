package com.ga.disclosure.rules.testing;

import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.rules.validation.ValidationSubject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 테스트 픽스처: 비교 항목(규칙 단위 테스트용). 운영 구현은 워크플로 애그리게이트(Phase 3A).
 *
 * @param fieldValues 항목값(문자열), {@code agentCodes}에 있는 코드는 설계사 입력, 나머지는 카탈로그 출처
 */
public record TestItem(
        ProductKey key,
        InsurerCode insurerCode,
        GroupCode groupCode,
        boolean isRecommended,
        boolean requestedByCustomer,
        boolean tempProduct,
        Optional<String> quoteDocNo,
        Map<String, String> fieldValues,
        Set<String> agentCodes,
        List<ReasonCode> reasonCodes,
        Optional<String> reasonText) implements ValidationSubject.Item {

    public TestItem {
        fieldValues = Map.copyOf(fieldValues);
        agentCodes = Set.copyOf(agentCodes);
        reasonCodes = List.copyOf(reasonCodes);
    }

    @Override
    public Optional<ProductKey> productKey() {
        return Optional.ofNullable(key);
    }

    @Override
    public String productName() {
        return "상품 " + (key == null ? quoteDocNo.orElse("-") : key.code());
    }

    /** {@code insurer}의 상품 1건, 상품군 {@code group}, 추천 아님, 사유 없음. */
    public static TestItem of(String insurer, String product, String group) {
        return new TestItem(ProductKey.parse(insurer + ":" + product), InsurerCode.of(insurer), GroupCode.of(group),
                false, false, false, Optional.empty(), Map.of(), Set.of(), List.of(), Optional.empty());
    }

    public TestItem recommended(String... codes) {
        return new TestItem(key, insurerCode, groupCode, true, requestedByCustomer, tempProduct, quoteDocNo, fieldValues, agentCodes,
                Arrays.stream(codes).map(ReasonCode::of).toList(), reasonText);
    }

    public TestItem reasons(String... codes) {
        return new TestItem(key, insurerCode, groupCode, isRecommended, requestedByCustomer, tempProduct, quoteDocNo, fieldValues, agentCodes,
                Arrays.stream(codes).map(ReasonCode::of).toList(), reasonText);
    }

    public TestItem text(String text) {
        return new TestItem(key, insurerCode, groupCode, isRecommended, requestedByCustomer, tempProduct, quoteDocNo, fieldValues, agentCodes,
                reasonCodes, Optional.ofNullable(text));
    }

    public TestItem requested() {
        return new TestItem(key, insurerCode, groupCode, isRecommended, true, tempProduct, quoteDocNo, fieldValues, agentCodes,
                reasonCodes, reasonText);
    }

    /** 임시등록으로 바꾼다: 상품키가 없어진다(3A 계획 Q4). */
    public TestItem temp(String quoteDocNoOrNull) {
        return new TestItem(null, insurerCode, groupCode, isRecommended, requestedByCustomer, true,
                Optional.ofNullable(quoteDocNoOrNull), fieldValues, agentCodes, reasonCodes, reasonText);
    }

    public TestItem group(String group) {
        return new TestItem(key, insurerCode, GroupCode.of(group), isRecommended, requestedByCustomer, tempProduct, quoteDocNo,
                fieldValues, agentCodes, reasonCodes, reasonText);
    }

    public TestItem field(String code, String value) {
        Map<String, String> values = new HashMap<>(fieldValues);
        values.put(code, value);
        return new TestItem(key, insurerCode, groupCode, isRecommended, requestedByCustomer, tempProduct, quoteDocNo, values,
                agentCodes, reasonCodes, reasonText);
    }

    /** 설계사가 입력한 항목값(출처 AGENT — {@code AGENT_INPUT} 결속은 이 값만 존재로 본다). */
    public TestItem agentField(String code, String value) {
        Map<String, String> values = new HashMap<>(fieldValues);
        values.put(code, value);
        Set<String> agent = new HashSet<>(agentCodes);
        agent.add(code);
        return new TestItem(key, insurerCode, groupCode, isRecommended, requestedByCustomer, tempProduct, quoteDocNo, values, agent,
                reasonCodes, reasonText);
    }

    public TestItem fields(Map<String, String> values) {
        return new TestItem(key, insurerCode, groupCode, isRecommended, requestedByCustomer, tempProduct, quoteDocNo, values,
                agentCodes, reasonCodes, reasonText);
    }
}
