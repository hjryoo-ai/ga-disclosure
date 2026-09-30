# Phase 2 지시문 — 카탈로그·보험사 패널·고객 참조 (v1.0)

## 역할과 맥락

`ga-disclosure` Phase 1(`phase-1`) 수용 후 이어지는 Phase다. 대상은 설계서 **§4.2(`ProductCatalogPort`), §5(`product_group`·`product_catalog`·`insurer_panel`·`customer_ref`), §9(개인정보 최소·암호화), §12 Phase 2**와 `CLAUDE.md` 절대 규칙 6. Phase E3(엔진)와 병렬로 진행할 수 있으며 접점이 없다.

목적: 비교표를 구성할 때 설계사가 고르는 **상품·보험사 목록의 출처와 유효기간**을 데이터로 다루고, 서명 링크 발송·본인확인에 필요한 **최소 고객 정보를 암호화된 상태로만** 보유하는 것. 이 Phase가 끝나면 (1) 카탈로그는 외부 정본의 캐시임이 출처·동기 시각으로 증명되고 (2) 평문 고객 정보가 DB·로그·예외 메시지 어디에도 없음이 테스트로 증명돼야 한다.

## 시작 전 보고

계획에 ① 선행 소과제 처리 ② 암호화 방식(알고리즘·키 계층·키 ID 저장 방식) ③ V5 DDL 초안 ④ 카탈로그 파일 형식을 넣고 승인 후 진행.

## 선행 소과제 (Phase 1 심사 이월)

- **A.** `phase-01-수용심사.md` §3의 설계서 v1.5·CLAUDE.md 반영(검증 단계 객체 배열, `exceptionApproval`, 활성화 누락 플래그, 직접 접근 허용 목록 규약, 아티팩트 배포 방식). 번들·스키마·C1 테스트 갱신 포함.
- **B.** `RuleActivationJob`: 적용 구간이 끝난 APPROVED 룰에 `compliance_flag(RULE_ACTIVATION_MISSED)`.
- **C.** 플랫폼 아티팩트: 태그 `platform-v0.1.0`에서 GitHub Packages로 발행하는 CI 잡. README에 "mavenLocal 우선, 없으면 GitHub Packages(read:packages 토큰)" 두 경로 기재.

## 작업 목록

### 1. V5 마이그레이션
- `customer_ref`: `birth_year SMALLINT` 제거, `birth_date_enc BYTEA` 추가, `enc_key_id TEXT NOT NULL`(암호문이 어느 키로 만들어졌는지 — 키 순환 대비), `name_enc`·`phone_enc`도 같은 키 ID 규약. `phone_enc`는 NULL 허용(현장 서명만 하는 고객).
- `product_catalog`·`product_group`·`insurer_panel`: 설계서 §5 그대로. `product_catalog.source`·`synced_at`·`source_ref`(파일명+해시)를 NOT NULL로. 유효기간 컬럼은 반개구간 `[from, to)` 규약을 Phase 1 룰과 통일.
- 모두 RLS·`tenant_id` 첫 컬럼. Phase 0의 C2 RLS 테스트가 새 테이블을 자동으로 포함하는지(테이블 목록을 카탈로그에서 읽는 구조인지) 확인하고, 아니면 테이블 수 단언을 갱신.

### 2. 카탈로그 (`disclosure-workflow` 포트 + `disclosure-infra` 어댑터 + CLI)
- 포트 `ProductCatalogPort`(§4.2의 3개 메서드)와 `InsurerPanelPort.isOnPanel(tenant, insurerCode, date)`. Phase 1의 `ValidationSubject.isInsurerOnPanel`은 이 포트를 쓰는 함수로 연결(애그리게이트는 Phase 3이므로 어댑터와 픽스처만).
- 파일 수입 어댑터: `catalog import --tenant T1 --file <path>` (형식은 CSV 또는 JSON 한 가지로 정하고 스키마를 `contracts/catalog/v1/`에 둔다). 수입은 **스냅샷 교체**가 아니라 **행 단위 upsert + 유효기간 닫기**다: 파일에 없는 기존 상품은 `sale_to`를 수입 기준일로 닫고 삭제하지 않는다(확인서가 참조한 상품이 사라지면 안 된다). 같은 파일 재수입은 no-op(파일 해시로 판정, 감사 `NOOP`).
- 상품군 코드 체계는 외부 정본(§14 #1)이므로 `product_group`도 파일로 수입하고 코드에 상수를 두지 않는다.
- 조회는 전부 기준일 필수(`asOf`) — 기본값 오늘 금지(Clock 주입도 호출자 몫).

### 3. 고객 참조와 암호화 (`disclosure-domain` 값객체 + `disclosure-infra`)
- 암호화: AES-256-GCM, 랜덤 96비트 nonce, AAD에 `tenant_id`·`customer_ref`·컬럼명을 넣어 **다른 행·컬럼으로 암호문을 옮겨 붙이는 것을 복호화 실패로** 만든다. 키 계층: 테넌트별 데이터 키(DEK) ← 마스터 키(KEK). `KeyProviderPort` 인터페이스(운영: KMS, 개발·테스트: 로컬 파일 키). 키 ID를 암호문 옆에 저장하고 순환 시 새 키 ID로 재암호화하는 명령(`customer rekey`)을 둔다 — 실행은 하되 실 KMS 연동은 어댑터 교체.
- 값객체 `Sensitive<T>`(도메인): `toString()`이 마스킹, `equals`는 상수 시간, 직렬화 금지(Jackson·로그·`record` 자동 `toString` 모두). `CustomerName`, `PhoneNumber`, `BirthDate`는 `Sensitive` 안에서만 존재.
- 본인확인 대조용 `BirthDate.matches(input)`: 복호화 후 상수 시간 비교. 결과만 남기고 입력값은 로그·감사에 남기지 않는다(`identity_check.result`만).
- 서비스: `CustomerRefService.register(tenant, name, phone?, birthDate?) → CustomerRef`, `lookup(tenant, customerRef)`, `phoneForNotification(tenant, customerRef)`(REMOTE_LINK 발송 전용, 호출 사유를 감사에 기록).
- 마스킹 규칙은 테넌트 파라미터(§14 #8 포털과 동일 미결정) — 기본값을 데이터로 두고 코드에 하드코딩하지 않는다.

### 4. 평문 유출 방지 검증
- 로그 스캔 테스트: 통합 테스트 전체를 돌리며 캡처한 로그·예외 메시지·`toString()` 출력에서 픽스처의 평문 이름·전화·생년월일 문자열이 **0건**. 픽스처 값은 우연히 다른 곳에 나타나지 않을 고유 문자열로 만든다.
- DB 덤프 스캔: 통합 테스트 후 `pg_dump`(또는 전 테이블 SELECT)에 평문 0건.
- 아키텍처 규칙 추가: `Sensitive` 타입을 `record` 컴포넌트로 직접 갖는 DTO 금지(API 응답으로 새는 경로 차단), Jackson `ObjectMapper`가 `Sensitive`를 직렬화하려 하면 예외.

## 완료 기준 (전부 테스트로 증명)

| # | 기준 | 증명 방법 |
|---|---|---|
| P1 | 기준일별 상품·패널 유효기간(반개구간) — 경계일 양쪽 | `CatalogAsOfIT` |
| P2 | 수입 멱등(같은 파일 no-op), 사라진 상품은 삭제가 아니라 `sale_to` 닫힘, 출처·해시·동기 시각 기록 | `CatalogImportIT` |
| P3 | 암호화 왕복, 다른 행·컬럼으로 암호문 이식 시 복호화 실패(AAD), 키 순환 후 구·신 키 모두 복호화 가능, 순환 완료 후 구 키 제거 시 재암호화된 행만 남음 | `CustomerEncryptionIT` |
| P4 | 로그·예외·DB 덤프에 평문 0건 | `PlaintextLeakScanIT` |
| P5 | `Sensitive` 직렬화 시도 예외, DTO 컴포넌트 금지 규칙(위반 주입 → 실패 → 제거) | `ArchitectureRulesTest` 확장 |
| P6 | `BirthDate.matches`가 상수 시간 비교를 쓰고 입력값이 어떤 출력에도 남지 않음 | `IdentityMatchTest` + P4 |
| P7 | 새 테이블이 RLS·`tenant_id` 규칙·SQL 스캔에 자동 포함 | Phase 0 테스트 갱신 |
| P8 | 선행 소과제 A·B·C: C1 시나리오 통과, `RULE_ACTIVATION_MISSED` 플래그, 아티팩트 발행 CI 잡 실행 로그 | 기존 테스트 + CI |
| P9 | Phase 0·1 전체 무손상, BOM 불일치 0, `net.jqwik` 0 | 빌드 로그 |

## 하지 말 것

- 확인서 애그리게이트·상태기계(Phase 3), 서명·본인확인 흐름(Phase 4), REST(Phase 6), CRM 기능(3순위 — `crm_customer_id`는 컬럼만).
- 주민번호·주소·계좌 수신. 카탈로그에 확인되지 않은 상품군 코드 체계를 지어 넣는 것(데모 파일은 부록 B 규격의 가상 코드 `PG-…`로만).
- 실 KMS 연동(어댑터 인터페이스까지만).

## 보고 형식

Phase 1과 동일. 추가로 ① 암호화 방식·키 계층 요약 ② 카탈로그 파일 스키마 ③ 로그 스캔이 검사한 출력 경로 목록 ④ 다음 Phase(3 워크플로 코어·봉인) 질문.
