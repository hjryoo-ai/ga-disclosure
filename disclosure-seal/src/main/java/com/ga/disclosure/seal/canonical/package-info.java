/**
 * 봉인 본문(canonical)의 구성·검증·RFC 8785 JCS 직렬화(Phase 3B). 정본 정의는 {@code contracts/seal/v1/canonical.schema.json}이다.
 * 엔진이 준 {@code ratioToAvg} 원문 문자열을 그대로 싣기 위해 {@code RatioLabel.value()} 호출이, 봉인 유스케이스가 복호화한 성명을 싣기 위해
 * {@code Sensitive.reveal} 호출이 허용되는 패키지다(아키텍처 테스트 허용 목록). 벽시계·난수·기본 로케일·시간대를 읽지 않는다.
 */
package com.ga.disclosure.seal.canonical;
