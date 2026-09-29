/**
 * JSON 매핑(엔진 응답 등). {@code java.math.BigDecimal}/{@code BigInteger} 참조가 허용되는 유일한 패키지다(CLAUDE.md 절대 규칙 1,
 * 아키텍처 테스트). 여기서도 수수료율·비율을 숫자로 비교·정렬·분류하지 않는다 — 역직렬화 경계에서만 쓴다. 구현은 Phase 3.
 */
package com.ga.disclosure.infra.json;
