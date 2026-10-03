/**
 * RFC 3161 타임스탬프(5 계획 §3, 4 수용심사 결정 3·승인 ①): 일일 머클 루트에만 토큰을 받는다. 포트({@link
 * com.ga.disclosure.audit.tsa.TimestampAuthorityPort})는 DER 바이트만 주고받아 BC 타입이 없고, 요청 생성·응답 수락·토큰 검증은
 * 이 패키지가 한다. {@code org.bouncycastle..} 참조는 이 패키지와 하위({@code stub}·{@code http})만 허용한다(ArchitectureRulesTest).
 */
package com.ga.disclosure.audit.tsa;
