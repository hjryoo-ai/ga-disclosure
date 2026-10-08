/**
 * 목록 페이지와 서명된 커서(6A 계획 §4.2, 승인 Q5 — 확인서·보류·작업 목록만, 이벤트 피드는 정수 {@code afterSeq}). 커서는 유스케이스가 봉하고 연다 —
 * 테넌트가 MAC에 묶이므로 다른 테넌트의 커서는 {@link com.ga.disclosure.workflow.page.InvalidCursorException}(400)이다.
 */
package com.ga.disclosure.workflow.page;
