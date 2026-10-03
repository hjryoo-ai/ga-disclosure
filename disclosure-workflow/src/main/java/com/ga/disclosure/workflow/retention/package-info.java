/**
 * 보존기간 종료 후 파기·법적 보류(5 계획 §5·§8.5·§8.6, CLAUDE.md 절대 규칙 2). 판정은 순수 {@code RetentionDecision}(sign), 지정 컬럼 NULL은
 * 파기자 롤로만 부르는 V9 함수 세 개({@link com.ga.disclosure.workflow.retention.DestroyerPort})가 한다. 행 삭제는 없다 — 번호·상태·해시·시각·체인은
 * 묘비로 남고, 파기 감사에는 지운 값의 해시(승인 Q3의 표현)를 남긴다.
 */
package com.ga.disclosure.workflow.retention;
