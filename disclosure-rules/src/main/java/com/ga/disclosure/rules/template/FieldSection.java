package com.ga.disclosure.rules.template;

/**
 * 서식 항목의 구분(3B 계획 승인 Q2): 문서 식별부(확인서 번호·상담일·설계사·고객 성명)와 비교 항목. 식별부는 비교 항목 수 산정·
 * {@code pendingConfirmation}과 섞지 않는다. 서식 스키마가 {@code HEADER ⇔ bind가 식별부 결속}을 강제한다.
 */
public enum FieldSection {
    HEADER,
    COMPARISON
}
