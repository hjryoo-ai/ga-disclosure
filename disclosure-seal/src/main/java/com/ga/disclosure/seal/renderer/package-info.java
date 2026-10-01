/**
 * 렌더러(서식 layout·bind → XHTML → PDF/A-2b, openhtmltopdf, Phase 3B). 입력은 봉인 본문 + 고정 서식 + 확인서 번호뿐이고 벽시계·난수·
 * 기본 로케일·시간대를 읽지 않는다(아키텍처 테스트). {@code RatioLabel.value()} 호출이 허용되는 패키지 중 하나지만 확인서 본문에는 비율을
 * 인쇄하지 않는다(허용 목록과 사유는 아키텍처 테스트 소스 {@code ArchitectureRulesTest}).
 */
package com.ga.disclosure.seal.renderer;
