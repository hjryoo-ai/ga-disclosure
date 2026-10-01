/**
 * 객체 저장소 어댑터(Phase 3B): AWS SDK v2의 벤더 무관 표준 S3 API만 쓴다(3B 계획 승인 Q1·Q12) — 특정 저장소 전용 API·헤더·관리 명령 없음.
 * 개발·CI 기준 구현은 SeaweedFS(이미지 digest 고정)이고, 운영은 Object Lock을 지원하는 어떤 S3 호환 저장소든 설정만 바꿔 붙는다. 교체 수용
 * 기준은 통합 테스트의 Object Lock 계약({@code ArtifactStoreContract})이다.
 */
package com.ga.disclosure.infra.storage;
