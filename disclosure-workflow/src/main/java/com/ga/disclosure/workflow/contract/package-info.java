/**
 * 계약 연결(6B 계획 §4, 지시문 §3): 인바운드 계약 {@code contracts/contract-link/v1}의 배치를 확인서에 연결한다 — 매칭(청약번호 → 증권번호), 활성 연결
 * 1건·정정 이력, 확인서 현재값 투영(V14 GD136·V15), 보존기한 연장(앵커 CONTRACT_DATE, 연장만), 감사 {@code CONTRACT_LINK_CHANGED}, 아웃박스
 * {@code PolicyLinked} v2(번호 없음). 매칭되지 않은 항목은 보고 행({@code contract_link_unmatched})으로만 남고 룰 기간 뒤 지운다. 증권·청약 번호는
 * 예외 메시지·보고서·작업 매개변수에 싣지 않는다.
 */
package com.ga.disclosure.workflow.contract;
