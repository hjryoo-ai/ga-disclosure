# 플랫폼 0.2.0 정비 지시문 — ga-disclosure (2026-10-14)

포털 Phase 0 v1.1의 전제 6건(H1~H6) 중 ga-disclosure 쪽에서 고쳐야 하는 것을 한 PR로 닫는다. 브랜치 `work/platform-0.2`, 태그 `platform-v0.2.0`(병합 뒤), 병합은 심사 회신 뒤. 핸드오프 브랜치 `work/portal-handoff`는 **push하고 PR을 열되** 이 PR 뒤에 병합한다(문서만이지만 규약은 같다; 아래 H2·H5 결정으로 핸드오프 본문도 고쳐야 한다).

## 결정

| # | 결정 | ga-disclosure 작업 |
|---|---|---|
| H1 두 개의 0.1.0 | **결함 인정. 0.2.0을 발행한다.** 발행된 버전은 절대 재발행하지 않는다 | ① 현재 `main`의 `platform-*`를 **0.2.0**으로 올려 GitHub Packages에 발행(`platform-v0.2.0`). ② **재발 방지**: 플랫폼 모듈의 공개 API 서명 덤프(클래스·공개 멤버의 정렬된 목록)를 `platform/API-0.2.0.txt`로 커밋하고, 빌드가 현재 덤프와 비교해 **버전이 같은데 API가 다르면 실패**(릴리스 노트 없는 변경 금지). 0.1.0의 "발행본 ≠ 로컬" 사실은 `DECISIONS.md`와 설계서 §3.3에 결함으로 기록. ③ `~/.m2`의 0.1.0은 지우지 않는다(사용자 환경) — 대신 포털이 잡는다(아래 v1.2 C9). |
| H2 Envelope가 disclosure 전용 | **분리(옵션 A).** 공유되는 것은 필드 집합이므로 `contracts/events/v1/envelope-core.schema.json`(eventId·seq·type:string·schemaVersion·tenantId·aggregate{kind:string,id}·occurredAt·payload:object)을 새로 두고, disclosure의 `envelope.schema.json`은 `allOf: [core, {type enum, aggregate.kind enum, payload oneOf 11}]`로 다시 쓴다 | 추가형(기존 이벤트 전부 통과 — `OutboxEventsIT`·계약 샘플로 증명). 테스트: 파생 Envelope가 core의 제약을 **전부 포함**(core를 통과 못 하는 문서는 파생도 통과 못 함). CHECKSUMS 갱신. 포털은 `envelope-core`만 사본으로 가져간다. |
| H3 `defs.schema.json`의 `format` 2곳 | **제거(`pattern`만).** 6A G11의 "두 계약" 범위가 `defs`를 빼놓았다 — 테스트 범위 결함 | `format` 0 테스트를 **`contracts/` 아래 전 파일**로 넓힌다. 주입: `format` 하나 되돌림 → 실패. |
| H6 RLS 설정 이름 상수가 package-private | **공개 상수로.** `platform-spring`에 `TenantSession.SETTING_NAME = "app.tenant_id"`(public static final) | 0.2.0에 포함. `TenantSessionBinder`와 ga-disclosure의 RLS 마이그레이션이 같은 값을 쓴다는 테스트(상수 ↔ `V2__rls.sql` 문자열 대조)를 ga-disclosure에도 추가 — 포털이 같은 테스트를 복제한다. |
| H4·H5 | ga-disclosure 변경 없음(아래 포털 v1.2에서 정정) | — |

## 완료 기준

- 0.2.0 발행본의 jar SHA-256 목록을 보고서에(포털이 의존성 검증 메타데이터에 고정한다). API 덤프 테스트가 "버전 동일 + API 변경" 주입에서 실패. Envelope 분리 뒤 기존 이벤트·샘플 전부 통과, core 제약 포함 테스트. `contracts/` 전체 `format` 0. 상수 ↔ RLS SQL 대조 테스트. Phase 0~8 무손상(전체 check, `TZ=UTC`).
- GitHub Packages **읽기 권한**: 다른 저장소(`ga-agent-portal`)의 Actions가 패키지를 읽을 수 있도록 패키지 설정에서 저장소 접근을 추가해야 할 가능성이 있다 — 이것은 **사용자 조치**(패키지 설정 화면). 보고서에 필요한 설정 이름과 확인 방법을 적고, 실제 확인은 포털 C9의 첫 CI에서.

## 보고 형식

E3.2와 같게, 짧게. 핸드오프 PR과 이 PR의 병합 순서(이것 먼저)를 적는다.
