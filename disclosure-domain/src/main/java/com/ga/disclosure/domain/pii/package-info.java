/**
 * 고객 개인정보 값객체(설계서 §9, CLAUDE.md 절대 규칙 6). 이름·연락처·생년월일은 {@link com.ga.disclosure.domain.pii.Sensitive}
 * 안에서만 존재한다 — {@code toString}은 전부 가리고, 직렬화할 수 없으며, 원문 접근은 {@code reveal} 하나뿐이고 그 호출처는
 * 아키텍처 테스트의 FQN 허용 목록으로 제한된다. 예외 메시지에는 입력값을 넣지 않는다.
 */
package com.ga.disclosure.domain.pii;
