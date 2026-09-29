/**
 * 확인서 본문의 정규화 JSON(RFC 8785 JCS) 직렬화. {@code canonical_hash}의 입력을 만든다. 엔진이 준 {@code ratioToAvg}
 * 원문 문자열을 그대로 싣기 위해 {@code RatioLabel.value()} 호출이 허용되는 패키지다(아키텍처 테스트 허용 목록). 구현은 Phase 3.
 */
package com.ga.disclosure.seal.canonical;
