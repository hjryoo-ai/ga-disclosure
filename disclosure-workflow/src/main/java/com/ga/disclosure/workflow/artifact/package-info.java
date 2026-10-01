/**
 * 봉인 산출물(Phase 3B, 설계서 §6.4·§9): 객체 저장소 포트({@link com.ga.disclosure.workflow.artifact.ArtifactStore} — S3 호환 + Object Lock),
 * 문서 데이터 키 암호화 포트({@link com.ga.disclosure.workflow.artifact.DocumentCryptoPort}), 산출물·키 기록 포트, 열람·잔여물 정리·보존 재적용
 * 유스케이스. DEK 평문은 이 패키지를 지나지 않는다(infra 암호화 어댑터 안에서만).
 */
package com.ga.disclosure.workflow.artifact;
