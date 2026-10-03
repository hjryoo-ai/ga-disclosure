# Phase 6A 지시문 — 인가·REST·고객 공개 서명·이벤트 피드·작업 엔드포인트·통지 아웃박스 (v1.0)

## 역할과 맥락

`ga-disclosure` Phase 5(`phase-5`, PR #8) 수용 후. 대상은 설계서 **§3(API 경계·D-3·D-9·D-10), §4(계약: `disclosure-internal.openapi.yaml`·`events/v1`), §9(공개 엔드포인트·한도), §12 Phase 6**과 `phase-05-수용심사.md` §2(반영 지시 R1~R3)·§4(결정 1·2·3·7·8). 이번 Phase는 **HTTP 표면과 그 뒤의 인가·작업·통지 기반**이다. 준법 큐·징구율·계약 연결·청약 게이트·초안 폐기·보존 재계산은 6B, 화면은 Phase 7, 배포 매니페스트·인그레스 한도는 Phase 8이다.

이 Phase가 끝나면 다음이 테스트로 증명돼야 한다.

1. 모든 HTTP 진입은 **유스케이스 진입점의 인가 포트**를 지나고, 역할·조직·`agent_id`는 토큰이 아니라 `identity_link`에서 온다. 다른 테넌트의 자원은 존재 여부를 드러내지 않는다.
2. 고객 공개 서명 경로는 **거부 사유가 무엇이든 같은 응답·같은 시간**이고, 토큰은 로그·경로·응답 어디에도 없다(Phase 4 D3·B2의 마무리).
3. HTTP로 할 수 있는 모든 것은 CLI로도 할 수 있고(동일 유스케이스), **HTTP 계층에는 업무 규칙이 없다**.
4. 긴 작업은 작업 리소스로 추적되고, 같은 테넌트·같은 종류는 동시에 둘이 돌지 않는다.

## 시작 전 반영 (첫 커밋)

- 수용심사 §2 **R1**(앵커 소급 폐지 — V12 CHECK, `DATE_NOT_TODAY`, `ANCHOR_MISSING_DAY`, 데모 시계 오프셋으로 두 날), **R2**(데모 번들 차집합 테스트), **R3**(`VERIFY_RUN` 감사 확인·보강).
- 룰 키 `legalHoldReleaseReasons`(닫힌 목록, D12의 `TODO(confirm#15)` 제거).

## 시작 전 보고

계획에 ① V12 DDL(`idempotency_key`·`async_job`·`notification_outbox`·보류 4-eyes CHECK·R1) ② 인가 모델 표(역할 × 행위 × 범위 산식, `identity_link` 해석 지점) ③ OpenAPI 변경 목록(추가 경로·오류 모델·커서·작업 리소스) ④ 공개 서명 경로의 보안 체인 구성과 **동일 응답·패딩 방법** ⑤ 작업 잠금(advisory lock 키 산식)과 작업 상태 전이표 ⑥ 통지 아웃박스 재시도 산식(룰 파라미터 대응) ⑦ 토큰→테넌트 바인딩 순서(RLS 바인딩이 인가보다 먼저인지)를 넣고 승인 후 진행.

## 작업 목록

### 1. V12 마이그레이션
- R1의 `anchor` CHECK. `legal_hold`에 `CHECK (released_by IS NULL OR released_by <> placed_by)`.
- `idempotency_key(tenant_id, actor_subject, key, request_hash, response_status, response_hash, created_at, expires_at)`: 같은 키·같은 요청 해시 → 저장 응답 재생, 같은 키·다른 요청 해시 → 422. TTL은 룰 `api.idempotencyTtlHours`. 행은 만료 후 배치 삭제 허용(개인정보 없음 — 응답 본문이 아니라 해시만 저장).
- `async_job(tenant_id, job_id, kind, status, requested_by, requested_at, started_at, finished_at, result_ref, error_code)`: `kind ∈ {ANCHOR, EXPIRE, RECONCILE, DESTROY, DESTROY_DRY_RUN, VERIFY_TENANT}`, 상태 전이 `QUEUED→RUNNING→{SUCCEEDED,FAILED}` 1회(트리거), `result_ref`는 보고서 산출물 키(보고서는 `document_artifact`가 아니라 테넌트 저장소의 `reports/` 접두, 암호화 동일).
- `notification_outbox(tenant_id, notification_id, kind, recipient_ref, payload_ref, attempts, next_attempt_at, status, last_error_code, created_at, sent_at)`: 수신처는 `customer_ref` 참조만(전화번호 평문 저장 금지 — 발송 시 Phase 2 `phoneForNotification` 경로로 읽는다), payload는 토큰 **원문을 담지 않는다**(세션 ID만, 발송 어댑터가 발송 직전 1회 토큰을 생성·세션에 해시 저장). 재시도 횟수·백오프는 룰 `notify.retry`, 소진 시 `status=DEAD` + `compliance_flag(NOTIFY_FAILED)`.
- 오류 코드 이어서 배정, `db-error-codes.md`.

### 2. 인가 (`disclosure-workflow` 포트, `disclosure-app` 어댑터)
- `AuthorizationPort.require(actor, action, target)`; `Actor = {subject, tenantId}`만 토큰에서, `Principal = identity_link 해석 결과 {roles, orgPath, agentId}`는 포트 안에서 조회(캐시 없음 — 해석은 매 요청, 변경 즉시 반영).
- 범위 산식(닫힌 어휘, 코드): AGENT → `disclosure.agent_id = principal.agentId`; MANAGER → `disclosure.org_path`가 `principal.orgPath` 접두; COMPLIANCE → 테넌트 전체; OPERATOR → CLI 전용(HTTP 거부). 행위 어휘는 유스케이스 이름과 1:1.
- 거부는 `AuthorizationDenied` 한 예외 타입 → HTTP **404**(자원 존재 누설 금지; 같은 테넌트 안에서 역할만 부족한 경우도 404 — 설계서 §9에 근거 한 줄). 거부 감사 `AUTHZ_DENIED`는 **테넌트 행이 있을 때만**(Phase 4 D3 규약), 대상 ID는 감사에 넣되 응답에는 없음.
- 모든 유스케이스 진입점에 포트 호출이 있음을 ArchUnit으로 강제(진입점 어노테이션 또는 패키지 규칙 — 계획에서 제안). CLI는 `OPERATOR` 액터로 같은 포트를 지나되 범위 검사는 통과.

### 3. 내부 REST (`disclosure-api`)
- 리소스 서버: Boot BOM이 관리하는 Spring Security OAuth2 리소스 서버 스타터(JWT). 토큰 claim → `tenantId`·`subject`만 사용. **순서 고정**: 토큰 검증 → `TenantContext` 바인딩(RLS) → 인가 포트 → 유스케이스. 테넌트 없는 토큰·모르는 테넌트는 401.
- 경로(`/internal/v1/...`, 전부 OpenAPI 정본에): 확인서 수명주기(초안·항목·추천사유·검증·봉인·무효·정정·재기준), 설계사·관리자 서명 세션, 보류 설정·해제(4-eyes), 작업(`POST /jobs/{kind}` → 202 + `GET /jobs/{id}`), 검증 보고서·영수증 내보내기(COMPLIANCE, 열람 감사 `REPORT_VIEW`), 이벤트 피드(§5), 파기 dry-run 보고서 열람(COMPLIANCE). **파기 실행·앵커 실행은 사람 역할에 없음** — `jobs/DESTROY`·`jobs/ANCHOR`는 스케줄러 주체(별도 클라이언트 자격, 역할 `SCHEDULER`)만.
- 규약: 쓰기 POST는 `Idempotency-Key` 필수(없으면 428), 오류 `{code, message, details}`(메시지·details에 고객 정보 0 — 센티널 스캔), 상태 충돌 409·검증 거부 422·인가 404·토큰 401, 목록은 커서(`after`·`limit`, 불투명 커서 = 서명된 seq), 스키마 형식은 `pattern`.
- 컨트롤러는 **DTO 변환 + 유스케이스 호출**만. 업무 규칙·상태 판단이 컨트롤러에 있으면 ArchUnit/리뷰 거부. 계약 테스트: 모든 응답을 OpenAPI 스키마로 검증(요청 예시도).

### 4. 고객 공개 서명 (`/public/v1/sign/...`, 별도 보안 필터 체인)
- 인증 없음·세션 없음·쿠키 없음. 토큰은 **헤더 `X-Sign-Token` 또는 JSON 본문**으로만, 경로·쿼리에 오면 그 자체로 거부(같은 거부 응답). 고객 링크 규약을 문서화: `https://{host}/s#{token}` — 프래그먼트는 서버에 가지 않는다; Phase 7 화면이 본문으로 보낸다.
- 엔드포인트: `POST open`(열람 시작, PDF 스트림 — 감사 `ARTIFACT_VIEW` 사유 `SIGN`), `POST verify-identity`, `POST capture`, `POST status`(세션 상태만). Phase 4 유스케이스 그대로.
- **동일 거부**: 없는 테넌트 접두·틀린 토큰·만료·취소·사용됨·본인확인 실패 한도 — 전부 **같은 상태 코드·같은 본문·같은 헤더 집합**. 응답 시간은 모든 응답(성공 포함)을 **고정 하한(룰 `publicSign.minResponseMillis`)으로 패딩**. 테스트는 통계가 아니라 패딩 훅 호출과 하한 적용을 단언한다(결정론). 토큰 비교는 상수 시간(Phase 4 그대로).
- 한도: 테넌트·토큰 해시 단위 시도 한도는 Phase 4 `maxFailures`; 토큰 접두가 가리키는 테넌트 단위의 분당 요청 한도 룰 `publicSign.tenantRatePerMinute`(초과도 같은 거부 응답). IP 단위 한도는 인그레스 몫(Phase 8 문서에 요구사항만).
- 응답에 PDF 바이트 외 개인정보 0(이름·전화·생년월일 센티널 스캔을 공개 응답 전체에).
- 토큰 원문은 요청 로그·액세스 로그·예외·감사 어디에도 없음(평문 스캔 확장: 공개 경로의 모든 로그 출력).

### 5. 이벤트 피드
- `GET /internal/v1/events?after={cursor}&limit={n}`: 테넌트 아웃박스를 seq 순으로, at-least-once, 각 항목 `contracts/events/v1` 스키마 그대로(`eventId`로 소비자 중복 제거). 발행 표시(`published_at`)는 소비자가 `POST /events/ack?upTo=`로 보낸 커서까지 — 재전달 가능. 소비자 역할 `FEED_CONSUMER`(별도 클라이언트 자격).
- 소비자 문서 `docs/event-feed.md`(커서·재전달·중복 제거·`DisclosureDestroyed` 추가 공지). 푸시 어댑터는 **인터페이스만**(구현·의존성 없음).

### 6. 작업 엔드포인트와 잠금
- `JobRunner`: 작업 리소스 생성 → 테넌트·종류별 **PostgreSQL advisory lock**(키 산식 계획 ⑤) 획득 실패 시 409(`JOB_ALREADY_RUNNING`) → 유스케이스(Phase 4·5 배치 그대로) → 보고서 산출물 저장 → 상태 전이. 프로세스 종료 시 `RUNNING`으로 남은 작업은 다음 호출이 `FAILED(INTERRUPTED)`로 닫고 새로 연다.
- CLI와 HTTP가 **같은 `JobRunner`** 를 쓴다(CLI도 잠금을 잡는다 — 운영자가 스케줄러와 겹쳐 돌리는 사고 방지).

### 7. 통지 아웃박스
- `NotifyPort` 호출을 직접 발송에서 **아웃박스 적재**로 바꾼다(세션 생성 트랜잭션 안). `NotificationDispatcher` 배치(작업 종류 `NOTIFY`)가 `next_attempt_at ≤ now`인 행을 어댑터로 보낸다. 콘솔 어댑터 유지; 실 사업자 어댑터는 인터페이스만(§14).
- 발송 직전 토큰 생성·세션 해시 저장·링크 조립, 토큰 원문은 어댑터 호출 인자로만 존재. 실패 백오프·소진은 룰 데이터. 소진 시 `NOTIFY_FAILED` 플래그(6B 준법 큐 입력).

### 8. CLI·데모
- CLI 변화 없음(동일 유스케이스). 추가: `jobs list/show`, `notify dispatch`.
- 데모: 기존 `seed.sh`에 **HTTP 흐름** `http-demo.sh` 추가 — 데모 OIDC(로컬 발급자, 데모 프로파일만) 토큰으로 AGENT 초안→봉인, MANAGER 확인, 공개 경로로 고객 서명(링크 프래그먼트에서 토큰을 꺼내 본문으로), 피드 소비(커서 2회), 작업 `VERIFY_TENANT` 202→SUCCEEDED, 거부 응답 3종의 바이트 동일 확인(diff). 2회 실행 NOOP.

## 완료 기준 (전부 테스트로 증명)

| # | 기준 | 증명 방법 |
|---|---|---|
| G1 | 모든 HTTP 진입이 인가 포트를 지난다(ArchUnit), 역할·조직·`agent_id`가 토큰 claim에서 오지 않는다(claim에 역할을 넣은 토큰이 무시됨) | `AuthorizationCoverageTest`·`AuthzFromIdentityLinkIT` |
| G2 | 범위: AGENT 타인 확인서·MANAGER 다른 조직·COMPLIANCE 다른 테넌트 → 전부 404, 본문 동일, 존재하는 자원과 없는 자원의 응답 바이트 동일 | `AuthzScopeIT` |
| G3 | 교차 테넌트: 토큰 테넌트 ≠ 자원 테넌트 → 404, RLS 바인딩이 인가보다 먼저(바인딩 전 조회 주입 시 실패) | `TenantBindingOrderIT` |
| G4 | 멱등: 같은 키·같은 요청 → 저장 응답 재생(부작용 1회), 같은 키·다른 요청 → 422, 키 없음 → 428, TTL 룰 데이터 | `IdempotencyIT` |
| G5 | 공개 서명 거부 7사유의 상태·본문·헤더 집합 동일(바이트 diff), 성공 포함 모든 응답에 패딩 훅 호출·하한 적용, 토큰을 경로·쿼리로 보내면 같은 거부 | `PublicSignUniformResponseIT` |
| G6 | 공개 응답·공개 경로 로그·액세스 로그에 토큰 원문·이름·전화·생년월일 0건(센티널) | `PlaintextLeakScanIT` 확장 |
| G7 | 테넌트 분당 한도 초과 → 같은 거부 응답, 한도는 룰 데이터 | `PublicSignRateLimitIT` |
| G8 | 피드: seq 순·at-least-once(ack 전 재요청 시 재전달)·스키마 통과·다른 테넌트 이벤트 0건·`DisclosureDestroyed` 포함 | `EventFeedIT` |
| G9 | 작업: 같은 테넌트·종류 동시 2건 → 둘째 409, 다른 테넌트는 병행, CLI와 HTTP가 같은 잠금을 다툼, `RUNNING` 고아 정리, 상태 전이 1회(트리거) | `JobRunnerIT` |
| G10 | 통지: 세션 생성과 아웃박스 적재가 같은 트랜잭션(롤백 시 0행), 백오프·소진이 룰 데이터, 소진 시 `NOTIFY_FAILED`, 토큰 원문이 아웃박스 행·로그에 0건 | `NotificationOutboxIT` |
| G11 | 컨트롤러에 업무 규칙 없음(ArchUnit: 컨트롤러 → 유스케이스·DTO만, 저장소·도메인 서비스 참조 금지), 모든 응답이 OpenAPI 스키마 통과, `format` 사용 0 | `ApiLayerRulesTest`·`OpenApiContractIT` |
| G12 | 반영 지시 R1~R3 완료: `anchor` CHECK 거부, `DATE_NOT_TODAY`, `ANCHOR_MISSING_DAY`, 데모 번들 차집합, `VERIFY_RUN` 감사 | 해당 IT |
| G13 | Phase 0~5 무손상, 평문·jqwik·BOM 검사, 위반 주입 기록(최소: 인가 포트 호출 제거한 진입점, 역할을 claim에서 읽기, 인가 거부를 403으로, 거부 사유별 본문 차이, 패딩 제거, 토큰을 쿼리로 허용, 피드 교차 테넌트 필터 제거, 작업 잠금 제거, 아웃박스 적재를 트랜잭션 밖으로) | 빌드 로그·보고서 |

## 하지 말 것

- 준법 큐·징구율·계약 연결·게이트·초안 폐기·보존 재계산(6B). 화면(Phase 7). 인그레스·CronJob 매니페스트(Phase 8). 실 SMS·알림톡·Kafka·실 TSA.
- 컨트롤러에 상태 판단·룰 조회. 토큰 claim에서 역할·조직을 읽는 것. 인가 거부를 403으로. 거부 사유별로 다른 본문·헤더. 토큰을 URL 경로·쿼리로 받는 것. 전화번호·토큰 원문을 아웃박스에 저장하는 것.
- CLI와 HTTP가 다른 유스케이스 코드를 타는 것.

## 보고 형식

Phase 5와 동일. 추가로 ① 인가 모델 표(역할 × 행위 × 범위) ② 공개 거부 응답 7종의 바이트 diff 결과 ③ 작업 상태 전이표(기계 판독) ④ OpenAPI diff 요약 ⑤ 6B(준법 큐·징구율·계약 연결·게이트·초안 폐기·보존 재계산) 질문과 **엔진 E4에 요구할 것**(계약 연결·게이트가 엔진에서 필요로 하는 데이터가 있으면).
