/**
 * 증거 패키지(EVIDENCE_ZIP, 설계서 §6.5 v1.9 — seal = 문서 산출물): 매니페스트(JCS, {@code contracts/seal/v1/evidence-manifest.schema.json})
 * + canonical·봉인 PDF·서명본 PDF·서명 레코드·감사 발췌. 결정론적 ZIP(STORED, 고정 DOS 시각, 매니페스트 먼저·나머지 경로 순). 서명 스트로크·
 * 이미지·스캔 원본은 넣지 않는다(해시만). 벽시계·난수·기본 로케일·시간대를 읽지 않는다(아키텍처 규칙).
 */
package com.ga.disclosure.seal.evidence;
