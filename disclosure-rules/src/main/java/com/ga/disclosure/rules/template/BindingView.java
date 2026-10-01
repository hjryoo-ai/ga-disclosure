package com.ga.disclosure.rules.template;

import com.ga.disclosure.domain.disclosure.ItemGrade;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * {@link BindingResolver}가 값을 찾는 확인서 단면. 검증 시점(워크플로 애그리게이트)과 렌더 시점(봉인 본문 canonical)이 각각 구현한다 —
 * 같은 결속이 두 시점에서 같은 값을 가리키게 하려는 것이다(3B 계획 §1).
 */
public interface BindingView {

    /** 확인서 번호. 검증 시점에는 아직 없다(봉인이 발급한다). */
    Optional<String> disclosureNo();

    LocalDate consultDate();

    String agentId();

    /** 고객 성명. 검증 시점에는 없다(봉인 유스케이스만 복호화한다 — 렌더러에는 복호화 경로가 없다, 승인 B3). */
    Optional<String> customerName();

    /** 상담일 카탈로그의 유사상품군 이름. */
    Optional<String> productGroupName();

    /** 상담일 위탁 패널 보험사 이름(코드 순). */
    List<String> panelInsurerNames();

    /** 비교 항목, 항목 번호 순. */
    List<? extends Item> items();

    /** 비교 항목 1건. */
    interface Item {

        /** 항목 보험사가 상담일 패널에 있으면 그 이름. */
        Optional<String> insurerName();

        String productName();

        /** 항목값 {@code fieldValues[code]}(JSON). */
        Optional<JsonNode> fieldValue(String code);

        /** 그 항목값의 출처가 설계사 입력인가. canonical은 출처를 싣지 않으므로(수용심사 §3-3) 봉인 본문의 값은 봉인 검증을 통과한 값이다. */
        boolean enteredByAgent(String code);

        Optional<ItemGrade> grade();

        boolean recommended();

        /** 추천사유 라벨(고정 룰의 라벨 문자열, 코드 순서 그대로). 사유가 없으면 빈 목록. */
        List<String> reasonLabels();

        Optional<String> reasonText();
    }
}
