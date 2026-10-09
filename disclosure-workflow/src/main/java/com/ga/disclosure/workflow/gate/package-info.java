/**
 * 청약 게이트(6B 지시문 §5, 계획 §6·승인 §4, 설계서 §4.4): {@code POST /internal/v1/gate} — 청약·증권 번호와 고객 가명으로 확인서를 찾아 Phase 4 순수
 * 함수({@code sign.gate.GateFunction})로 판정한다. 후보는 계약 연결과 같은 규칙(무효·정정·폐기·파기 제외 — 무효 확인서가 쥔 연결은 근거가 아니다).
 * 요청마다 감사 {@code GATE_DECISION} 1행(식별자는 해시), 응답에 개인정보 없음.
 */
package com.ga.disclosure.workflow.gate;
