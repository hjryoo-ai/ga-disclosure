# Phase 6A 완료 보고 — 인가·REST·고객 공개 서명·이벤트 피드·작업 엔드포인트·통지 아웃박스

작성 2026-10-08 · 대상 지시문 `docs/phase-06A-지시문.md` · 계획 `docs/phase-06A-계획.md`(승인 2026-10-03, `docs/phase-06A-계획승인.md`)
- 승인 내용: Q5·Q6·Q7·Q15는 대안 또는 수정, 나머지 권장안. 보강 B1~B3. 지시문 문언이 틀렸던 곳(Q6·Q15)은 승인이 정본.
- 설계서 v1.13(변경 이력 ①~⑲) · 브랜치 `work/phase-6A` · PR [#9](https://github.com/hjryoo-ai/ga-disclosure/pull/9)
- **병합은 수용 심사 회신 뒤**에 한다. 태그 `phase-6A`는 이 보고서 커밋에 단다.

## 요약

- **인가는 호출자 하나만 받는다(지시문 §2, G1~G3).**
  - 유스케이스 진입점은 `Caller(테넌트, 주체, 채널)`만 받고 첫 줄에서 `AuthorizationPort.require`/`requireList`를 부른다. 진입점 전수는 `@UseCaseEntry`·`@NotAnEntry`와 ArchUnit(`AuthorizationCoverageTest`)이 강제한다.
  - 역할·조직·`agent_id`는 `identity_link`에서만 온다. 토큰의 `roles`·`scope`·`org_path`·`agent_id` 클레임은 아무것도 열지 못하고, 링크를 바꾸면 다음 요청에 바로 반영된다(`AuthzFromIdentityLinkIT`).
  - 역할 × 행위 × 범위 표 `authz-matrix`(설계서 §9, §2 ①)가 정본이고 코드와 양방향 대조한다. 권한 없음과 없는 자원은 같은 404 바이트, 거부는 별도 트랜잭션의 `AUTHZ_DENIED` 감사.
- **REST 세 접두(승인 Q15).** `/api/v1`(AGENT·MANAGER·COMPLIANCE), `/internal/v1`(SCHEDULER·FEED_CONSUMER), `/public/v1`(고객). 사람 역할 → `/internal`, 서비스 역할 → `/api`는 같은 404.
  - JWT 체인 하나, 권한(authority)을 만들지 않는 변환, 테넌트 바인딩 필터(바인딩이 인가보다 먼저 — `TenantBindingOrderIT`), 같은 401/404 바이트, 오류 본문 `Problem{code, message, details}`(JCS), 예외 장벽.
  - 쓰기 POST는 `Idempotency-Key` 필수: 요청 해시·별도 트랜잭션 청구/완료·영수증 튜플 재생(해시 대조)·`Idempotency-Replayed: true`. TTL·임차는 룰 데이터.
  - 목록은 서명 커서(테넌트·목록 종류를 MAC에), 피드만 정수 `afterSeq`·`nextSeq`·`headSeq`(승인 Q5).
  - 업무 거부는 범주 `CONFLICT` 409 / `INVALID` 422(정본 블록 `rejection-categories` ↔ 코드).
  - 계약 세 개: `disclosure-api`(신설 1.0.0, 29 연산), `disclosure-internal`(2.1.0, 8 연산 — 게이트·증권 연결은 `x-ga-phase: 6B`), `disclosure-public`(신설 1.0.0, 5 연산). **모든 IT 응답이 계약 스키마를 통과**한다(`ApiTestSupport.send`), 라우트 ↔ 계약 양방향, `format` 0.
- **고객 공개 서명 경로(지시문 §4, G5~G7).**
  - 토큰은 헤더 `X-Sign-Token` 또는 본문만. 질의·경로·GET은 거부. 고객 링크는 프래그먼트(`/s#{token}`).
  - 거부는 사유와 무관하게 **같은 바이트**(상태·헤더 − Date·본문, §2 ②). 모든 응답(성공 포함)이 패딩 훅을 정확히 한 번 지나고 대기 = max(0, 하한 − 경과). 하한은 배포 설정(승인 Q6, 기본값 없음 — 없으면 기동 실패).
  - 테넌트 분당 한도는 룰 데이터, 알려진 테넌트만 키.
  - **보안 검토 반영(`636d74a`)**: 백그라운드 보안 검토가 게이트의 fail-open을 지적했다 — 입장 검사의 예외(룰 없는 알려진 테넌트)가 500으로 새어 테넌트 존재를 구별하게 했다. 이제 입장 예외·핸들러 예외·`sendError`·리다이렉트·200/422 밖 상태가 전부 같은 거부다.
  - **누출 스캔(G6, `07b3221`)**이 실제 누출 둘을 찾았다(전부 DEBUG/TRACE): 톰캣·스프링 시큐리티가 요청 원문·원 URI(토큰 포함)를, MVC TRACE가 DTO `toString`(토큰)을 찍었다. 요청 원문 로거를 INFO로 고정하고, 민감 레코드 `toString`을 가리고, ArchUnit 규칙 (f)로 강제했다.
- **작업(지시문 §6, G9)**: 테넌트·종류별 세션 어드바이저리 잠금(전용 롤 `disclosure_job_lock`, 허용 목록 네 번째), 잠금 상실 확인(B1), 고아 정리, GD121 전이, 암호화 보고서. CLI와 HTTP가 같은 `JobRunner`를 지난다. `ANCHOR`는 HTTP에 없다(승인 Q7).
- **통지(지시문 §7, G10)**: 원격 링크 발급과 아웃박스 적재가 한 트랜잭션, 토큰은 발송 때 만들어 해시만 저장(GD123), 백오프·소진은 룰 데이터, 소진 시 `NOTIFY_FAILED`.
- **이벤트 피드(지시문 §5, G8)**: pull + ack, at-least-once(ack 전 재요청은 같은 이벤트), `afterSeq` 생략 = ack한 지점, 소비자 문서 `docs/event-feed.md`.
- **테스트 12,204건, 실패 0, 스킵 0**(Phase 5: 12,037건). `TZ=UTC`·`--rerun-tasks` 전체 check도 통과(§3).
  - 위반 주입 105건이 전부 의도한 테스트에서 잡혔다(§3). 첫 시도에 잡히지 않은 것 3건(A2·C6·J7)은 규칙·테스트를 고친 뒤 잡혔다. 무효였던 주입 3건(G1 첫 형태, P3·P4 첫 실행 — 주입 도구 결함)은 고쳐 다시 돌렸다.
- **CI**: PR #9의 run `37786410851`(head `5d013a6`) — `build`·`pdfa-verify`·`no-docker` 전부 success. CI 테스트 보고서는 로컬과 같은 12,204건(실패 0, 스킵 0)이고 모듈별 수도 같다. `gh run view`와 아티팩트 내려받기로 직접 확인했다(§3 CI).
- **데모**: 격리 컨테이너(15432/18333)에서 `seed.sh` 뒤 `http-demo.sh` 2회 — 1회째 완료·피드·검증·거부 바이트 동일, 2회째 쓰기 전부 재생(§4). 실 TSA 계약 태스크는 FreeTSA로 1차 확인했다.
- **엔진**: 엔진 저장소는 손대지 않았다(§6).

## 1. 커밋·파일

커밋 목록(`main..work/phase-6A`, 보고서 커밋은 이 표 뒤에 붙는다):

| 커밋 | 계획 §12 | 요약 |
|---|---|---|
| `9b10297` | — | docs: Phase 5 수용 심사, 6A 지시문, 파기 멱등 근거 |
| `b5ac5d9` | — | docs: 6A 계획(승인 대기) |
| `249b0f0` | 1 | docs: 계획 승인 반영 |
| `f9a3f01` | 2 | V12 — R1 앵커 날짜 CHECK, 4-eyes, 설계사가 아닌 주체, 조직 경로 스냅샷, 발송 때 토큰, 멱등·작업·통지 테이블 |
| `3c21895` | 3 | 인가 — 호출자만 받는 진입점, `identity_link` 인가, 범위 표 |
| `e4852d8` | 4 | 작업 — 세션 어드바이저리 잠금, 잠금 상실 확인(B1), 암호화 보고서, CLI 경유 |
| `6ab7210` | 5 | 통지 — 발급 때 아웃박스, 룰 백오프 디스패처, 발송 때 토큰 |
| `99b544c` | 6a | REST — JWT 체인, 테넌트 바인딩, 같은 401/404, Problem, 작업 라우트 |
| `ab753ad` | 6b | 멱등 — 청구/재생/완료, JCS 응답, `IDEMPOTENCY_PURGE` |
| `3bb8369` | 6c-1 | 범위 목록·상세, 서명 커서, 보류 라우트 |
| `5265b9a` | 6c-2a | 거부 범주(409/422), 확인서 쓰기 경로 |
| `402fb5a` | 6c-2b | 서명 경로, 현장 기기 토큰은 재생용으로 저장하지 않음 |
| `8f91f70` | 6c-3 | `/api`·`/internal` 계약, 모든 IT 응답을 계약으로 검증 |
| `b8abcd6` | 7a | 공개 서명 게이트 — 같은 거부, 패딩, 테넌트 한도 |
| `636d74a` | 7a(보완) | 보안 검토: 게이트는 실패하면 닫힌다 |
| `07b3221` | 7b | 누출 스캔(G6), 요청 원문 로거 고정, DTO `toString` 가림 |
| `8b013ad` | 8 | 이벤트 피드·ack |
| `5d9af89` | 9 | 데모 OIDC·`demo token`·`http-demo.sh`·opt-in 실 TSA 계약 태스크 |
| `5d013a6` | 10 | G1·G7 시험(`AuthzFromIdentityLinkIT`·`PublicSignRateLimitIT`)과 지시문 최소 주입 2·3·4 |

주요 추가 파일:

| 영역 | 파일 |
|---|---|
| DB | `V12__api_jobs_notify.sql`, `docker/postgres/init-roles.sql`(`disclosure_job_lock`), `docs/db-error-codes.md` GD120~GD124 |
| 계약 | `contracts/api/v1/disclosure-api.openapi.yaml`(신설), `disclosure-internal.openapi.yaml` 2.1.0, `disclosure-public.openapi.yaml`(신설), 룰 스키마(`api.*`·`publicSign`·`notify.retry`·`legalHoldReleaseReasons`), `CHECKSUMS` |
| 워크플로 | `authz/{Caller, Channel, Action, Target, Principal, Role, AuthorizationPort, ScopePolicy, ListScope, ListGrant, UseCaseEntry, NotAnEntry, …}`, `job/{JobRunner, JobQueryService, JobStore, JobLockPort, …}`, `idempotency/*`, `page/{CursorPort, Page}`, `feed/{EventFeed, EventFeedStore, EventPushPort}`, `disclosure/{NotificationDispatcher, DisclosureQueryService, …}`, `sign/PublicSignLimits`, `RejectionCategory` |
| 인프라 | `authz/IdentityLinkAuthorization`, `jobs/JobLockGateway`, `persistence/{AsyncJobRepository, IdempotencyRepository}`, `crypto/{ReportCipher, CursorCodec}`, `outbox/EventFeedRepository`, `persistence/NotificationRepository` |
| API | `security/{ApiSecurityConfiguration, TenantBindingFilter, PublicSignGate, ResponsePadding, RateWindow, AccessLogFilter, ExceptionBarrierFilter, IdempotencyCaptureFilter}`, `idempotency/{IdempotencyInterceptor, CanonicalJsonMessageConverter}`, `error/*`, `rest/*`·`internal/*`·`publicsign/*` 컨트롤러, `mapper/*`, `dto/*` |
| 앱·데모 | `QueryConfiguration`·`JobConfiguration`, `demo/DemoOidcIssuer`, `application-demo.yaml`, CLI `demo token`·`jobs …`·`notify dispatch`, `demo/phase6a-seed.json`, `scripts/http-demo.sh`, 태스크 `demoJavaLauncher` |
| 문서 | `docs/event-feed.md`(소비자 안내) |

## 2. 지시문 추가 보고 5항목

### ① 인가 모델 표 (역할 × 행위 × 범위)

정본은 설계서 §9의 `authz-matrix` 블록이고 `AuthzMatrixTest`가 `ScopePolicy`와 양방향으로 대조한다(주입 Z5·Z6). `SELF` = 자기 이름으로 생성, `OWN` = 자기 `agent_id`, `ORG` = `org_path` 아래(경로 경계 — `/HQX`는 `/HQ` 아래가 아니다), `TENANT` = 테넌트 전체, `ANY` = CLI 운영자(감사 역할 OPERATOR), `SESSION` = 서명 토큰의 세션.

```authz-matrix
action,COMPLIANCE,MANAGER,AGENT,SCHEDULER,FEED_CONSUMER,OPERATOR,CUSTOMER
DISCLOSURE_CREATE,-,-,SELF,-,-,ANY,-
DISCLOSURE_READ,TENANT,ORG,OWN,-,-,ANY,-
ITEMS_REPLACE,-,-,OWN,-,-,ANY,-
COMPARE,-,-,OWN,-,-,ANY,-
GRADES_REQUEST,-,-,OWN,-,-,ANY,-
RECOMMENDATIONS_SET,-,-,OWN,-,-,ANY,-
VALIDATE,-,ORG,OWN,-,-,ANY,-
SEAL,-,-,OWN,-,-,ANY,-
VOID,-,ORG,OWN,-,-,ANY,-
SUPERSEDE,-,ORG,-,-,-,ANY,-
REBASE,-,-,OWN,-,-,ANY,-
EXCEPTION_APPROVE,-,ORG,-,-,-,ANY,-
ARTIFACT_VIEW,TENANT,ORG,OWN,-,-,ANY,-
SIGN_SESSION_ISSUE,-,-,OWN,-,-,ANY,-
FACE_TO_FACE_CONFIRM,-,-,OWN,-,-,ANY,-
PAPER_SCAN_UPLOAD,-,-,OWN,-,-,ANY,-
AGENT_SIGN,-,-,OWN,-,-,ANY,-
MANAGER_CONFIRM,-,ORG,-,-,-,ANY,-
PAPER_SCAN_REVIEW,-,ORG,-,-,-,ANY,-
COMPLETE,-,ORG,OWN,-,-,ANY,-
SIGN_OPEN,-,-,-,-,-,-,SESSION
SIGN_VIEW_RECORD,-,-,-,-,-,-,SESSION
SIGN_VERIFY_IDENTITY,-,-,-,-,-,-,SESSION
SIGN_CAPTURE,-,-,-,-,-,-,SESSION
SIGN_STATUS,-,-,-,-,-,-,SESSION
LEGAL_HOLD_PLACE,TENANT,-,-,-,-,ANY,-
LEGAL_HOLD_RELEASE,TENANT,-,-,-,-,ANY,-
LEGAL_HOLD_READ,TENANT,-,-,-,-,ANY,-
RECEIPT_EXPORT,TENANT,-,-,-,-,ANY,-
VERIFY_TENANT,TENANT,-,-,TENANT,-,ANY,-
DESTROY_DRY_RUN,-,-,-,TENANT,-,ANY,-
DESTROY,-,-,-,TENANT,-,ANY,-
DISCLOSURE_EXPIRE,-,-,-,TENANT,-,ANY,-
ARTIFACT_RECONCILE,-,-,-,TENANT,-,ANY,-
NOTIFY_DISPATCH,-,-,-,TENANT,-,ANY,-
IDEMPOTENCY_PURGE,-,-,-,TENANT,-,ANY,-
ARTIFACT_GC,-,-,-,-,-,ANY,-
ANCHOR_RUN,-,-,-,-,-,ANY,-
CATALOG_IMPORT,-,-,-,-,-,ANY,-
CUSTOMER_REGISTER,-,-,-,-,-,ANY,-
CUSTOMER_REKEY,-,-,-,-,-,ANY,-
JOB_READ,TENANT,-,-,TENANT,-,ANY,-
REPORT_VIEW,TENANT,-,-,-,-,ANY,-
EVENT_FEED_READ,-,-,-,-,TENANT,-,-
EVENT_FEED_ACK,-,-,-,-,TENANT,-,-
```

- 채널 규칙(승인 Q15): 사람 역할은 `API` 채널에서만, 서비스 역할은 `INTERNAL`에서만, 고객은 `SIGN_TOKEN`에서만 위 칸이 유효하다. 어긋나면 사유 `CHANNEL`로 404(`ChannelSeparationIT`).
- 파기 실행·앵커는 사람 역할에 없다. COMPLIANCE는 dry-run·파기 보고서를 열람만 한다. 법적 보류 해제는 설정자와 다른 준법 주체(4-eyes, 유스케이스 + DB CHECK).

### ② 공개 거부 응답의 바이트 비교

`PublicSignUniformResponseIT.everyRejectionIsTheSameResponseAndEveryResponseIsPaddedOnce`가 다음을 **상태·헤더(− Date, 소문자 이름 정렬)·본문의 지문**으로 비교한다. 전부 기준(① 형식 오류)과 같다.

| 사유 | 경로 |
|---|---|
| ① 형식 오류 | 입장 전처리 |
| ② 없는 테넌트 | 입장 전처리(카운터 전) |
| ③ 틀린 비밀 | 핸들러(토큰 거부) |
| ④ 만료 | 핸들러 |
| ⑤ 취소(재발급) | 핸들러 |
| ⑥ 사용됨 | 핸들러 |
| ⑦ 본인확인 실패 한도 | 핸들러 |
| 토큰을 질의로 · 경로로 · GET | 입장 전처리 |
| 룰이 해석되지 않는 알려진 테넌트(보안 검토) | 입장 검사의 예외 |
| 게이트 안쪽의 `sendError` · 리다이렉트 · 500 | 응답 검사(실패는 닫힌 쪽) |
| 테넌트 분당 한도 초과 | 카운터(`theTenantRateLimitIsTheSameRejectionAndPaddedTheSameWay`, `PublicSignRateLimitIT`) |

데모(`http-demo.sh`)가 실제 서버에서 받은 세 거부(틀린 토큰·질의 토큰·사용된 토큰)의 정규화 파일은 SHA-256 앞 16자리가 셋 다 `373190dd9b310124`이고 `diff`는 0이다. 내용:

```
HTTP/1.1 404 
cache-control: no-cache, no-store, max-age=0, must-revalidate
content-length: 91
content-type: application/json
expires: 0
pragma: no-cache
referrer-policy: no-referrer
x-content-type-options: nosniff
x-frame-options: DENY
x-xss-protection: 0
{"code":"SIGN_LINK_UNAVAILABLE","details":{},"message":"This signing link cannot be used."}
```

- `Set-Cookie` 없음. 업무 거부(유효 토큰 보유자 — 서명 순서·본인확인 미완 등)만 422 `REJECTED`.
- 모든 응답(성공 포함)에서 패딩 훅 1회, 요청 대기 = max(0, 하한 − 경과)를 기록형 `Sleeper`로 결정론적으로 단언한다(통계 시험 없음, 승인 B2). 없는 테넌트 거부와 한도 거부도 같은 식이다.

### ③ 작업 상태 전이표 (기계 판독)

정본은 설계서 §6의 `job-states` 블록이고 `JobStateTableTest`가 GD121 트리거의 허용 전이와 양방향 대조한다(주입 J4·J5).

```job-states
from,to,trigger,sets
-,QUEUED,submit (잠금 획득 후),requested_at
QUEUED,RUNNING,실행기 시작,started_at
RUNNING,SUCCEEDED,유스케이스 정상 종료 + 보고서 저장,finished_at·result_ref·report_sha256·report_key_wrapped·report_kek_id
RUNNING,FAILED,유스케이스 예외·보고서 저장 실패·INTERRUPTED·LOCK_LOST,finished_at·error_code
QUEUED,FAILED,실행 전 고아(INTERRUPTED)·실행기 거부(REJECTED),finished_at·error_code
```

그 밖의 전이(종단 → 무엇이든, RUNNING → QUEUED 등)는 GD121로 거부된다(`JobStateTableTest`가 불허 전이 전수를 DB에 직접 시도).

### ④ OpenAPI diff 요약 (`9b10297` → 이 브랜치)

| 계약 | 버전 | 연산 | 변경 |
|---|---|---|---|
| `disclosure-api.openapi.yaml` | (없음) → 1.0.0 | 0 → 29 | 신설. 확인서 목록·상세·쓰기 13·산출물·영수증, 서명 경로 7, 보류 3, 작업 4. `Idempotency-Key`·`After`·`Limit` 공통 매개변수, `Problem`(닫힌 `details`), 영수증 닫힌 모양 |
| `disclosure-internal.openapi.yaml` | 1.0.0 → 2.1.0 | 3 → 8 | `bearerJwt`, 작업 4(`ANCHOR` 없음), 피드 ack 신설, 피드 `afterSeq` 기본값 0 → "ack한 지점", 응답 `additionalProperties: false`, 게이트·증권 연결은 `x-ga-phase: 6B`(구현 없음), `format` → `pattern` |
| `disclosure-public.openapi.yaml` | (없음) → 1.0.0 | 0 → 5 | 신설. 전부 POST, 토큰은 헤더/본문, 거부는 상수 본문 `SIGN_LINK_UNAVAILABLE` 또는 업무 거부 `REJECTED` |
| `engine-disclosure.openapi.yaml` | 1.2.0 | 2 | 변경 없음 |

- 깨지는 변경: `disclosure-internal`의 메이저 상향(2.0.0) — 인증 방식 명시와 6B 경로 표시. 1.0.0의 소비자는 아직 없다(게이트·증권 연결은 6B 구현).
- `OpenApiContractIT`: 라우트 ↔ 계약 양방향(세 계약), 요청 예시가 자기 스키마 통과, `format` 0, 검증기가 틀린 모양을 거부함을 대조군으로.

### ⑤ 6B 질문과 엔진 E4 요구 — §7·§6

## 3. 테스트와 완료 기준

### 테스트 수 (`5d013a6`에서 `TZ=UTC ./gradlew --no-daemon check --rerun-tasks --continue`)

| 모듈 / 스위트 | Phase 5 | Phase 6A |
|---|---|---|
| platform-core test | 3,058 | 3,067 |
| platform-canonical test | 1,054 | 1,054 |
| platform-spring test | 13 | 12 |
| disclosure-domain test | 1,085 | 1,085 |
| disclosure-rules test | 1,366 | 1,378 |
| disclosure-workflow test | 1,141 | 1,155 |
| disclosure-seal test | 63 | 63 |
| disclosure-sign test | 491 | 491 |
| disclosure-audit test | 141 | 141 |
| disclosure-app archTest | 46 | 58 |
| disclosure-infra integrationTest | 3,566 | 3,645 |
| disclosure-app integrationTest | 13 | 55 |
| **합계** | **12,037** | **12,204** (실패 0, 스킵 0) |

- `BUILD SUCCESSFUL`(86 태스크 전부 재실행), `scanPlaintextLeaks: 175 result files, 14 forbidden strings, 0 hits`, `elapsed-retention scan: harness=started, hits=18, allowed=25, controls=18`(B3).
- platform-spring −1: OIDC 골격 폐기와 그 테스트 삭제(D4).

- **집계 기준**: 결과 XML(`*/build/test-results/*/*.xml`), opt-in `tsaContractTest`는 `check` 밖이라 제외.
- **증가분(주요)**: app integrationTest 13 → 55 — `IdempotencyIT` 7, `AuthzScopeIT` 3, `ListCursorIT` 3, `DisclosureFlowIT` 3, `SigningFlowIT` 3, `OpenApiContractIT` 4, `PublicSignUniformResponseIT` 2, `PublicSignStartupIT` 2, `EventFeedIT` 2, `DemoOidcIT` 2, `AuthzFromIdentityLinkIT` 2, `ChannelSeparationIT` 2, `PublicSignFlowIT`·`PublicPlaintextLeakScanIT`·`PublicSignRateLimitIT`·`LegalHoldApiIT`·`TenantBindingOrderIT` 각 1. infra: `JobRunnerIT`, `JobStateTableTest`, `NotificationOutboxIT`, `AuthorizationIT`, `V12GuardIT`, `CursorCodecTest` 등. workflow: `ScopePolicyTest`, `AuthzMatrixTest`, `RejectionCategoryTableTest`. arch: `AuthorizationCoverageTest`, `ApiLayerRulesTest`(a)~(f).
- 집계 시점 이후 바뀐 것은 보고서 문서뿐이다.

### 완료 기준

| # | 기준 | 증거 |
|---|---|---|
| G1 | 모든 진입이 인가 포트를 지난다, 역할·조직·`agent_id`가 클레임에서 오지 않는다 | `AuthorizationCoverageTest`(규칙 1~4: 진입점 전수가 `require`/`requireList`를 직접 부른다, 행위 선언 일치, `@NotAnEntry` 호출자 닫힌 목록, 폐기 항목 검사). `AuthzFromIdentityLinkIT`(2): `roleScopeOrgAndAgentClaimsAreIgnored`, `anIdentityLinkChangeTakesEffectOnTheNextRequest`. `ApiLayerRulesTest.onlyTheTenantBindingFilterTouchesTheJwt`, `TenantBindingFilter.noAuthorities` |
| G2 | 범위: AGENT 타인·MANAGER 다른 조직·COMPLIANCE 다른 테넌트 → 404, 존재·부재 바이트 동일 | `AuthzScopeIT`(3): `outOfScopeDetailIsTheSame404AsAMissingIdAndIsAuditedOnce`(`/HQX` 대 `/HQ`, 조직 없는 옛 행 포함, `AUTHZ_DENIED` 1행, 응답에 대상 ID 없음), 목록 범위·준법 `DISCLOSURE_VIEW` 감사 |
| G3 | 교차 테넌트 404, RLS 바인딩이 인가보다 먼저 | `TenantBindingOrderIT`(바인딩 전 조회 주입 → `TenantNotBoundException` 500, 장벽), `AuthzScopeIT`(다른 테넌트 토큰), `ListCursorIT`(다른 테넌트 커서 400) |
| G4 | 멱등 | `IdempotencyIT`(7): 같은 키·요청 → 같은 바이트·`Idempotency-Replayed`·감사·아웃박스 1회, 다른 본문 422, 키 없음 428, 진행 중 409 → 임차 경과 뒤 인수, TTL 룰 변경 → `expires_at` 변화(코드 diff 0), 404 미저장, `IDEMPOTENCY_PURGE`. `SigningFlowIT`(기기 토큰 응답 409 `IDEMPOTENCY_NOT_REPLAYABLE`, 원격 링크 영수증 재생) |
| G5 | 공개 거부 같은 바이트, 패딩, 경로·질의 토큰 | `PublicSignUniformResponseIT`(2) — §2 ②. `PublicSignStartupIT`(2): 하한 누락·0이면 기동 실패. `PublicSignFlowIT`(쿠키 없음·`no-referrer`·`no-store`) |
| G6 | 누출 0(센티널) | `PublicPlaintextLeakScanIT`: 센티널 고객(파일로만 등록)으로 공개 경로 전 흐름 뒤 루트 TRACE·`ga.access`·표준 출력·오류·공개/내부 응답·DB 덤프·DB 서버 로그에 센티널 14종과 토큰 비밀 0. CLI 버퍼(`OperatorCliIT`·`Phase5CliIT`·`ApiTestSupport.cli`)도 `CliOutputScan`. `ApiLayerRulesTest.recordsWithSensitiveComponentsRedactThemInToString` |
| G7 | 테넌트 한도 = 룰 데이터, 같은 거부 | `PublicSignRateLimitIT.theLimitComesFromTheRuleAndRecoversInTheNextWindow`(한도 2·4, N+1 거부 지문 동일, 다른 테넌트 무영향, 다음 창 회복), `PublicSignUniformResponseIT.theTenantRateLimitIsTheSameRejectionAndPaddedTheSameWay`(없는 테넌트는 카운터 키가 되지 않음) |
| G8 | 피드 | `EventFeedIT`(2): seq 오름차순·갭 없음, ack 전 재요청 바이트 동일, ack 뒤 기본 시작점 이동, 다른 테넌트 0, `DisclosureDestroyed` 포함, 모든 응답이 envelope 스키마 포함 계약 통과, 머리 너머 422 두 코드, 사람 역할 404 |
| G9 | 작업 | `JobRunnerIT`: `aLostLockFailsTheOldJobWithoutAReportAndTheNextJobRuns`·`anOldExecutorThatWakesAfterBeingInterruptedWritesNothing`(B1), `theSameLockKeyNeverRunsTwice`(아래 넷), `aLeftoverActiveRowIsInterruptedByTheNextSubmission` — B1 잠금 상실 → 옛 작업 `FAILED(LOCK_LOST)`·보고서 미저장, 새 작업 SUCCEEDED, 같은 테넌트·종류 둘째 409(행 미생성), 다른 테넌트 병행, CLI가 쥔 잠금에 HTTP 409, 프로세스 사망 모사 뒤 `FAILED(INTERRUPTED)`, DESTROY·DRY_RUN 상호 배제. `JobStateTableTest`(GD121 전수). `ChannelSeparationIT`·`InternalJobsController`(HTTP `ANCHOR` 404) |
| G10 | 통지 | `NotificationOutboxIT`: 발급 롤백 → 세션·아웃박스 0행, 백오프 산식·룰 변경, 소진 → DEAD + `NOTIFY_FAILED` 1건, 토큰 원문이 아웃박스·세션·감사·로그 0, 발송 실패 롤백 시 `token_hash` NULL 유지·`CUSTOMER_PHONE_READ` 재기록, GD123 |
| G11 | 컨트롤러에 업무 규칙 없음, 응답 스키마, `format` 0 | `ApiLayerRulesTest`(a)~(f), `OpenApiContractIT`(4), `ChannelSeparationIT`(2), 모든 IT 응답의 계약 검증 |
| G12 | R1~R3 | `AnchorGuardIT.theAnchorDateIsTheKstDayOfItsCreation`(CHECK 거부), `AnchorJobTest`·`Phase5CliIT`(`DATE_NOT_TODAY`), `VerifyTenantIT`(`ANCHOR_MISSING_DAY` 중간·끝 공백, 오늘 미포함, `CHAIN_BROKEN` 없음), `DemoShortBundleTest`(차집합 = 보존 키만), `LegalHoldIT.releaseReasonsAreTheRuleListAndTheReleaserIsNotThePlacer`(해제 사유 룰 목록·4-eyes·DB CHECK 23514). R3(`VERIFY_RUN` 감사)은 Phase 5 `VerifyTenantIT`·`Phase5CliIT`가 이미 단언 |
| G13 | 0~5 무손상, 평문·jqwik·BOM, 주입 | 위 테스트 수, `TZ=UTC` 전체 check, 아래 주입 기록, CI |

**BOM·의존성**:
- 새 외부 의존성은 없다. 추가는 전부 Boot BOM 관리: `spring-boot-starter-security-oauth2-resource-server`(4.1.1, disclosure-api), 앱 통합 시험의 `spring-boot-starter-webmvc`·`jackson-dataformat-yaml`·`spring-security-oauth2-jose`, 그리고 기존 카탈로그의 `json-schema-validator` 3.0.6.
- 카탈로그에서 `spring-security-config`·`-web`·`-oauth2-resource-server` 직접 항목을 지웠다(platform-spring의 OIDC 골격 폐기, D4).
- `disclosure-audit` 락은 새 스위트 `tsaContractTest`의 구성 이름만 늘었다(버전 변화 0).
- `net.jqwik`은 락 파일·의존 그래프에 0건이다.

### 규칙 테스트 위반 주입 기록 (주입 → 실패 확인 → 제거)

각 주입은 코드·마이그레이션·문서를 임시로 고쳐 대상 스위트를 돌린 뒤 원복했다. 원문은 각 커밋 메시지 본문에 있다. **굵게** 표시한 것은 지시문 G13 최소 9종이다.

| 단계 | 주입 → 잡은 테스트 |
|---|---|
| 2 (`f9a3f01`) | V1 R1 CHECK 제거 → `AnchorGuardIT`; V2 4-eyes CHECK 제거 → `LegalHoldIT`; V3 조직 경로 트리거 제거 → `V12GuardIT`; V4 `ANCHOR_MISSING_DAY`를 운영 발견에서 빼기 → `VerifyTenantIT`(`CHAIN_BROKEN` 발생); V5 데모 번들의 `minCompare` 변경 → `DemoShortBundleTest` |
| 3 (`3c21895`) | Z1 `@UseCaseEntry` 누락 → coverage; Z2 선언·호출 행위 불일치 → coverage; **Z3 진입점의 `require` 제거 → coverage 1, `AuthorizationIT` 4**; Z4 ORG 범위를 문자열 접두로 → `ScopePolicyTest`; Z5·Z6 표 칸 어긋남(문서·코드 각각) → `AuthzMatrixTest`; Z7 거부 감사를 업무 트랜잭션에 → `AuthorizationIT`; Z8 CLI가 비진입점 호출 → coverage; Z9 CLI `--role` 재허용 → `OperatorCliIT`; Z10 api 클래스가 비진입점 호출 → coverage |
| 4 (`e4852d8`) | J1 B1 잠금 확인 제거 → `JobRunnerIT` 2(두 작업 SUCCEEDED / 옛 실행기 기록); J2 고아 정리 제거; J3 DRY_RUN 별도 잠금 키; J4·J5 `job-states` 블록 어긋남 → `JobStateTableTest`; J6 GC가 `result_ref` 무시; J7 보고서 AAD에서 작업 ID 제거 → `ReportCipherTest`(첫 판은 못 잡음 — 시험 강화 후 잡음); J8 종단 FAILED 무조건; J9 CLI가 FAILED 무시 → `Phase5CliIT`; **J10 잠금 획득 생략 → `JobRunnerIT` 3**(벨트 인덱스는 못 잡는다 — 고아 정리가 살아 있는 행을 닫아 버린다. 잠금이 통제, 벨트는 고아 단계 없는 두 삽입 경합만. 설계서 §6.10 기록) |
| 5 (`6ab7210`) | N1 토큰을 발송 감사에 기록(계획의 "아웃박스 컬럼"은 쓸 컬럼이 없어 대체) → `NotificationOutboxIT`; **N2 아웃박스 적재를 별도 트랜잭션으로 → 7 실패**(별도 트랜잭션은 커밋 전 세션을 FK로 볼 수 없어 발급 자체가 실패); N3 백오프 지수 하나 어긋남; N4 어댑터 실패 삼킴; N5 소진 1회 이르게; N6 실패 시 번호 읽기 감사 재기록 누락; N7 만료 확인 누락 |
| 6a (`99b544c`) | A1 내부 컨트롤러를 `/api`에; A2 컨트롤러가 도메인 값 파싱(**첫 실행 미검출** — 진입점 시그니처의 workflow 밖 타입이 허용 집합에 샜다. 규칙을 workflow 타입으로 좁혀 검출); A3 유스케이스 2회 호출; A4 보안 구성이 Jwt 읽기; A5 컨트롤러가 원 헤더 읽기; **A6 채널을 항상 API로(Q15 채널 검사 제거) → `ChannelSeparationIT`**; A7 기본 bearer 진입점; A8 모르는 테넌트 미검사; A9 장벽 끔; A10 체인 밖 permitAll |
| 6b (`ab753ad`) | I1 완료 미기록; I2 해시가 본문 무시; **I3 404 저장 → `IdempotencyIT`**; I4 임차 무시; I5 TTL 상수; I6 해시 대조 없는 재생; I7 원 경로로 해시; I8 변환기 미등록; I9 키 없음 진행; I10 인터셉터가 원 헤더 읽기(`ApiLayerRulesTest`); I11 완료 호출 제거(폐기 `OUTSIDE_CALLERS` 항목); I12 정리가 만료 무시(GD120 + 시험); I13 정리 수 0 |
| 6c-1 (`3bb8369`) | C1 조직 필터 문자열 접두; C2 목록이 범위 무시; **C3 커서 MAC에서 테넌트 제외 → `ListCursorIT`·`CursorCodecTest`**; C4 준법 조회 미감사; C5 키셋 `<=`; C6 목록 종류 미검사(**첫 실행 미검출** — 작업 커서가 날짜 파싱에서 먼저 실패했다. 같은 모양의 보류 목록 사례를 더해 검출); C7 보류 조회에 텍스트; C8 상세 인가 누락(coverage); C9 커서 키 파일 권한 미검사 |
| 6c-2a (`5265b9a`) | D1 범주 무시(항상 422); D2 코드만 범주 뒤집기(표 시험); D3 검증 차단이 규칙 ID 잃음; D5 산출물 미디어 타입 소실; D6 대상 해시가 JCS 아님 |
| 6c-2b (`402fb5a`) | E1 no-store 응답을 재생용으로 저장; E2 발급 응답 no-store 누락; E3 서명 거부 범주 무시 |
| 6c-3 (`8f91f70`) | F1 계약 경로 이름 변경(라우트 시험); F2 `format` 추가; F3 계약의 Job 필드 이름 변경(모든 `IdempotencyIT` 사례가 응답 검사에서 실패); F4 요청 예시가 자기 스키마 위반 |
| 7a (`b8abcd6`) | **G1 전처리 거부 패딩 누락**(첫 형태는 무효 — finally가 여전히 패딩, 고쳐 검출); G2 없는 테넌트가 카운터 키; **G3 질의 문자열 허용**; G4 공개 advice가 예외를 거부로 안 바꿈; G5 리퍼러 정책 누락; **G7 패딩이 경과 무시** → `PublicSignUniformResponseIT` |
| 7a 보완 (`636d74a`) | G8 이전 게이트로 새 시험 → 500 `INTERNAL_ERROR`(룰 없는 알려진 테넌트); G9 입장 catch만 두고 래퍼·상태 허용 목록 제거 → 컨테이너 `/error` 재전달의 다른 404 본문 |
| 7b (`07b3221`) | H1 톰캣 요청 로거 고정 해제 → 루트 TRACE에 센티널#2·토큰 비밀 둘; H2 `TokenRequest.toString`이 토큰 출력 → `ApiLayerRulesTest` (f); H3 액세스 로그에 원 URI(첫 형태는 템플릿 단언에만 걸림 → **H3b 뒤에 덧붙임 → `ga.access` 토큰 비밀**); H4 고객 파일 파서가 이름 출력 → `OperatorCliIT` 2(`CliOutputScan`) |
| 8 (`8b013ad`) | **K1 피드 SQL에서 테넌트 조건 제거 → `TenantPredicateScanTest` + `EventFeedIT`**(런타임 가드 `MissingTenantPredicateException`이 먼저 500); K1b `:tenantId`는 남기고 조건만 제거 → **스캔만 실패, RLS가 막아 `EventFeedIT` 통과**(계획이 예상한 대로); K2 기본 시작점이 ack 무시; K3 ack가 발행 행 재기록 → GD106 500; K4 거부 블록에서 피드 행 삭제 → `RejectionCategoryTableTest`; K5 ack UPDATE를 다른 메서드로 → `DisclosureWriteScanTest` |
| 9 (`5d9af89`) | M1 데모 토큰에 `roles` 클레임; M2 가드가 `ga.demo.oidc-*` 통과; M3 발급자에 데모 프로파일 없음; M4 키 파일 644 → `DemoOidcIT`; M5 원격 링크 영수증 no-store 복귀 → `SigningFlowIT` |
| 10 (`5d013a6`) | P1 주체를 `agent_id` 클레임에서 → `AuthzFromIdentityLinkIT`; **P2 `roles` 클레임을 인가 어댑터가 존중**(`Caller`에 역할 칸이 없고 `Jwt`는 바인딩 필터만 닿으므로 — 규칙 (d) — 주체 문자열로 밀반입해야 했다) → `AuthzFromIdentityLinkIT` 2; **P3 인가 거부를 403으로 → `AuthzScopeIT`**; **P4 거부 사유별 본문 → `PublicSignUniformResponseIT`**(계약이 상수 본문을 고정해 먼저 잡음), P4b 사유를 헤더에만 → 같은 IT의 바이트 비교("3 wrong secret") |

합계 105건. 무효였던 시도(G1 첫 형태, H3 첫 형태 — 잡혔으나 다른 단언으로, P3·P4 첫 실행 — 주입 도구가 여러 편집 파일을 잘못된 순서로 되돌려 P2의 부분 편집이 남아 컴파일 실패)는 고쳐 다시 돌렸고 위 표는 다시 돌린 결과다.

### CI (1차 증거, `gh run view`)

**run `37786410851`**(pull_request, head `5d013a6`) — 전부 success. 로그는 `gh run view --job <id> --log`, 아티팩트는 `gh run download`로 직접 받아 확인했다.
- `build`(job `113342188532`, ubuntu-latest x64, Temurin 25.0.4)
  - `net.jqwik` 의존 그래프 검사 통과.
  - `elapsed-retention scan: harness=started, hits=18, allowed=25, controls=18`(B3, 로컬과 같은 수).
  - `scanPlaintextLeaks: 175 result files, 14 forbidden strings, 0 hits`, `BUILD SUCCESSFUL in 6m 3s`. 발행 아티팩트 소비 빌드 `BUILD SUCCESSFUL`.
  - 아티팩트 `test-reports`의 모듈별 HTML 보고서 합계 **12,204건, 실패 0, 스킵 0**. 모듈별 수가 §3 표의 로컬 값과 하나도 다르지 않다(app archTest 58·integrationTest 55, infra integrationTest 3,645 …).
  - 로그 898행에서 지시문 형태의 문장 0건(규칙 9).
- `pdfa-verify`(job `113342188787`): `case-01`·`case-02`·`case-03`·`signed-01` 각각 `flavour=2b declared=2b compliant=true failedChecks=0`. 아티팩트 `signed-01.pdf`의 SHA-256 `7a1f6a86d8758e67…`은 Phase 4·5 골든과 같다(6A는 렌더러를 바꾸지 않았다).
- `no-docker`(job `113342188720`): `372 tests completed, 365 failed`, `result files: 74, with failures/errors: 72, skipped: 0`, `OK: integration tests failed (not skipped) because Docker is missing`. 실패하지 않은 2파일은 Docker가 필요 없는 순수 시험(`CursorCodecTest`·`ReportCipherTest` — 통합 소스셋에 있는 암호 시험)이다.
- 보고서 커밋이 올린 새 head의 CI는 PR 코멘트로 덧붙인다(문서만 바뀐다).

### CLAUDE.md 규칙 9 기록

- 지시문 형태의 문장은 0건이었다("ignore previous"·"disregard previous"·"이전 지시"·"system prompt"·"AI agent"·"language model" 패턴). 대상: `seed.sh` 출력, `http-demo.sh` 2회 출력, 데모 서버 로그, `TZ=UTC` 전체 check 출력, 실 TSA 계약 태스크 출력(합계 1,437행), CI `build` 로그(아래).
- `net.jqwik`은 카탈로그의 금지 주석 한 줄뿐이고 락 파일·의존 그래프에 0건이다.
- 새로 받은 외부 산출물은 FreeTSA의 CA·TSA 인증서(데이터 — 실 TSA 계약 태스크의 신뢰 앵커)뿐이고, scratch 디렉터리에 두었다.

## 4. 데모

- 환경: 사용자 compose 볼륨을 건드리지 않으려고 새 격리 컨테이너 — `postgres:18.6`(15432, `init-roles.sql`), SeaweedFS(18333, compose와 같은 digest), KEK는 scratch 파일. 데모 OIDC 키·목록 커서 키는 설계대로 `~/.ga-disclosure/`에 생겼다(`demo-oidc.key`·`api-cursor.key`, 600).
- `seed.sh`(Phase 1~5 절): 종료 0, `verify tenant` DEMO1·2·3 MATCH.
- `http-demo.sh` 1회째(run id `2026-10-08-r5`, 종료 0):

```
TOKENS agent manager compliance scheduler feed (claims: sub, tenant_id, iss, aud, exp — no role claim)
WEB UP http://localhost:18080 (profile demo)
STEP create 201 replayed=no
STEP items 200 replayed=no
STEP compare 200 replayed=no
STEP grades 200 replayed=no
STEP recommendations 200 replayed=no
STEP validate 200 replayed=no
STEP seal 200 replayed=no
SEALED 8b83917d-6600-4026-8e08-21d515c191f3 DEMO1-2026-000008
STEP remote-link 201 replayed=no
STEP notify 202 replayed=no
JOB NOTIFY 5124dfca-88fd-4d0c-88f1-a5cd877d5140 SUCCEEDED
PUBLIC open 200 → build/demo/http/sign.pdf
PUBLIC view 200
PUBLIC verify-identity 200 {"missing":[],"passed":true}
WAIT 60s — the customer reads before signing (rule minSecondsFromSendToSign)
PUBLIC capture 200 signatureId=f4636a92-2c2b-4b9a-8a67-463b5f7e986b
STEP agent-signature 200 replayed=no
STEP manager-confirmation 200 replayed=no
STATUS 8b83917d-6600-4026-8e08-21d515c191f3 COMPLETED
FEED read 1: 6 events, nextSeq=47, headSeq=47
FEED read 2 before ack: same events again (at-least-once)
STEP ack-47 200 replayed=no
FEED after ack: 0 events from the ack point 47
STEP verify-tenant 202 replayed=no
JOB VERIFY_TENANT b033063c-4d4c-412c-b804-697b56a86805 SUCCEEDED
REPORT build/demo/http/verify-report.json result=MATCH
REJECTIONS wrong-token / query-token / used-token: identical (HTTP/1.1 404 )
HTTP DEMO DONE run=http-demo-2026-10-08-r5 disclosure=8b83917d-6600-4026-8e08-21d515c191f3
```

- 2회째(같은 run id, 종료 0): 쓰기 13단계 전부 `replayed=yes`, 같은 확인서·같은 작업 ID, 고객 경로는 완료된 확인서라 건너뜀, 피드는 ack 지점 47에서 0건, 거부 비교는 1회째의 사용된 토큰으로 다시 동일.
- 데모 서버 로그와 두 실행 출력에서 데모 고객 파일의 이름·번호·생년월일(하이픈 제거형 포함) 0건.
- 같은 DB에서 r5 전의 시도는 스크립트 결함(macOS bash 3.2의 따옴표 중첩, 검증 본문 누락, `GA_DEMO_*` 환경변수 — `DemoKeysGuard`가 데모가 아닌 CLI의 기동을 바르게 멈췄다)과 **올바른 대리 서명 플래그**(발송 뒤 60초 안의 기계적 서명 → `SIGNATURE_DEVICE_REUSE` HIGH, 관리자 확인이 그 플래그의 확인을 요구)로 멈췄다. 그 시도들은 다른 run id를 썼다. 스크립트는 이제 룰의 `minSecondsFromSendToSign`만큼 기다린다(고객이 읽는 시간).
- 두 번째 실행이 찾은 것: 원격 링크 발급 영수증까지 no-store였다 → 기기 토큰을 담은 응답만 no-store로 좁혔다(D20).
- 실 TSA 계약 태스크: 환경 없이 1건 실패("this task never skips"), `GA_TSA_URL=https://freetsa.org/tsr` + FreeTSA CA 인증서로 1건 통과(2026-10-08T13:17Z).

## 5. 설계서와 달리 구현했거나 해석한 지점

설계서는 해당 코드와 같은 커밋에서 v1.13 변경 이력 ①~⑲로 고쳤다. 아래는 계획·지시문의 문언과 다른 지점이다.

| # | 지점 | 이유 |
|---|---|---|
| D1 | `notification_outbox`의 컬럼은 `customer_ref`·`session_id`(계획 `recipient_ref`·`payload_ref`), `async_job.params`·`closed_at` 추가, 시계 역행 `CLOCK_BEHIND_LATEST`, 앱 롤은 `async_job`·`notification_outbox` DELETE 권한 없음 | `PiiColumnTableTest`가 `customer_ref` 이름으로 가명 참조를 묶는다. 작업 매개변수·고아 정리 시각이 필요했다 |
| D2 | VALIDATE에 MANAGER(ORG) 추가, SUPERSEDE는 MANAGER만(계획 §3.3의 AGENT 칸 정정) | 관리자가 봉인 차단 목록을 보고 예외 승인한다. 업무 룰이 정정에 `exceptionApproval.role`(MANAGER)을 요구한다 |
| D3 | 인가 어댑터는 `infra.authz`(계획 app), `AnchorJob`은 운영자 문자열, CLI는 `--role`을 주면 거부 | 저장소 접근이 infra에 있다. 앵커는 플랫폼 배치(승인 Q7) |
| D4 | 예외 장벽 필터(필터 단계 예외 → 500), 체인 밖 `denyAll`도 내부 404 본문, 대상 검사는 `JwtClaimValidator`, **platform-spring의 `OidcResourceServerConfiguration` 폐기**(발행물 공개 클래스 감소) | 없으면 `/error` 재전달이 404로 실패를 가린다. JWT 체인은 API 모듈 하나가 소유한다 |
| D5 | 재생 헤더 `Idempotency-Replayed`, 본문 상한 32 MiB(서명 캡처), 만료 미정리 행은 청구 때 지우고 재청구, JSON 응답 전부 JCS(`CanonicalJsonMessageConverter`), 규칙 4 `OUTSIDE_CALLERS` | 재생 바이트가 처음과 같아야 해시 대조가 성립한다 |
| D6 | 상세에 검증 결과 없음, 확인서 키셋은 상담일\|ID(생성 시각 컬럼 없음), `requireList` 포트·규칙 2 확장, CLI 커서 키는 프로세스마다 새 키 | 검증은 쓰기 경로(`/validate`)의 영수증 |
| D7 | 영수증 미가용 코드의 `:` → `_`, 항목 플래그 `Boolean`(Jackson 3 `FAIL_ON_NULL_FOR_PRIMITIVES`), `ALREADY_HELD` 409 | 코드 패턴 `^[A-Z][A-Z0-9_]*$` |
| D8 | 관리자 확인이 완료까지 한다(별도 `/complete`는 409) | Phase 4 유스케이스 그대로 |
| D9 | `disclosure-api.openapi.yaml` 신설(계획은 "기존 파일") | 없었다 |
| D10 | 공개 업무 거부는 범주와 무관하게 422, 공개 본문 상한 4 MiB, `RateWindow.tracks` 관찰 메서드, `PublicSignLimits`는 `@NotAnEntry` + `OUTSIDE_CALLERS` | 유효 토큰 보유자에게만 보이는 거부라 범주 구분이 정보가 아니다 |
| D11 | **게이트는 실패하면 닫힌다**(보안 검토, `636d74a`) | 입장 검사 예외가 500으로 테넌트 존재를 드러냈다 |
| D12 | 요청 원문 로거(`org.apache.coyote`·`catalina`·`tomcat`·`FilterChainProxy`) INFO 고정, 민감 레코드 `toString` 가림 + 규칙 (f) | 누출 스캔이 찾은 실제 누출 |
| D13 | 누출 스캔은 기기 토큰을 전달하는 발급 응답 1건을 제외한다(no-store 단언·정확히 1건), `CliOutputScan`은 센티널 + 데모 고객 파일 값 | 그 응답이 설계상 유일한 전달 경로다 |
| D14 | 웹 IT의 Hikari 풀 상한(최대 5, 최소 유휴 1) | 캐시된 컨텍스트 14개에서 기본 풀이 서버 연결 100개를 넘었다(53300) |
| D15 | 피드 `afterSeq` 생략 = ack한 지점(계약의 기본값 0 삭제), 머리 너머 422 `AFTER_BEYOND_HEAD`·`ACK_BEYOND_HEAD`(계획에 없던 코드), ack는 본문 `{upToSeq}`(지시문 `?upTo=` 질의), 읽기는 감사하지 않음 | "ack 뒤 기본 시작점 이동"(G8)을 성립시키려면 기본값이 ack 지점이어야 한다. 머리 너머는 다른 발행자(복원된 DB)를 보고 있다는 신호 |
| D16 | `EventFeedIT`의 `DisclosureDestroyed`는 운영 `OutboxPort`로 적재 | 그 이벤트를 만드는 파기 경로는 Phase 5 IT가 다룬다. 피드는 종류와 무관 |
| D17 | 데모 OIDC 서명 키는 PKCS#8 PEM(계획 `.p12`) | PKCS#12 개인키 항목은 인증서가 필요하고, 인증서를 만드는 BC는 `..audit.tsa..` 전용 |
| D18 | 토큰을 웹 앱보다 먼저 만든다(계획 순서 1·2 뒤바뀜), 웹 앱은 부트 jar + 툴체인 JDK(`demoJavaLauncher`) | 웹 앱이 기동 때 PEM을 읽는다. `bootRun`은 Gradle 데몬 아래라 끝낼 PID가 없다 |
| D19 | `http-demo.sh`의 환경변수는 `HTTP_DEMO_*` | `GA_DEMO_*`는 `ga.demo.*`로 묶여 데모가 아닌 CLI가 기동하지 않는다(가드가 옳다) |
| D20 | **원격 링크 발급 영수증은 no-store가 아니다** — no-store와 409 `IDEMPOTENCY_NOT_REPLAYABLE`은 기기 토큰을 담은 응답만 | 6c-2b의 내 결정을 좁혔다. 자격이 없는 응답까지 재생하지 않으면 재시도가 막힌다(데모 2회째가 찾음) |
| D21 | 계획 §11의 `AuthzFromIdentityLinkIT`·`PublicSignRateLimitIT`는 10단계에서 더했다 | 보고서를 맞추다가 빠진 것을 찾았다. 그 전까지 G1 후반·G7 일부는 증거가 없었다 |

## 6. 엔진

- 엔진 저장소는 손대지 않았다.
- E4 첫 커밋(이미 승인된 것): ① 마이그레이션 번호 전역 단조 문서화 ② V104 `product_key` 폭 40.
- **E4에 새로 요구할 것**: 6A 범위에서는 없다. 6B의 계약 연결·게이트가 엔진에서 필요로 할 수 있는 것은 §7 Q4에 질문으로 둔다.

## 7. 6B 질문

1. **관리자가 확인할 플래그를 HTTP로 어떻게 찾는가.** 관리자 확인은 열린 플래그를 ID로 확인(`acknowledgedFlags`)해야 하는데, 6A에는 플래그를 나열하는 경로가 없다(데모가 찾음 — 기계적 서명이 대리 서명 플래그를 올리자 HTTP로는 완료할 수 없었다). 선택지: (a) 확인서 상세에 열린 플래그(ID·유형·심각도)를 싣는다 — 단 설계사도 상세를 읽고, `SIGNATURE_DEVICE_REUSE`는 그 설계사에 대한 의심이다. (b) 관리자·준법 전용 플래그 목록(6B 준법 큐와 함께). 권장: (b), 관리자 칸은 ORG 범위, 설계사에게는 "확인 대기 중" 여부만.
2. **저장하지 않는 응답(400·404·5xx) 뒤의 키.** 지금은 청구 행이 남아, 같은 키로 **고친 본문**을 보내면 TTL(24시간) 동안 422 `IDEMPOTENCY_KEY_REUSED`다(설계대로 — 같은 요청만 임차 뒤 인수). 데모 스크립트 결함으로 실제로 겪었다. 4xx 입력 오류에서는 청구를 지워 즉시 재사용을 허용할지(404는 저장하지 않는 이유가 존재 누설이므로 지워도 누설은 없다).
3. **준법 큐·징구율·초안 폐기·보존 재계산의 범위**: 지시문 6B 그대로 진행하되, 고객 등록 API는 6B 끝의 별도 계획·별도 심사(승인 Q17).
4. **계약 연결·게이트와 엔진.** 증권의 상품을 확인서 항목과 대조하려면 증권 쪽 상품 키가 엔진·카탈로그 키 체계(40자)와 같아야 한다. 권장: 증권 연결 요청이 `productKey`를 같은 형식으로 싣고 대조는 이 시스템의 카탈로그로 한다(엔진 호출 없음) — 그러면 E4에 새 요구는 V104 폭 40 하나다. 엔진이 증권 단위로 수수료 정보를 다시 계산해야 한다는 요구가 있으면 알려 달라.
5. **피드 푸시**: 인터페이스(`EventPushPort`)만 있다. 6B에서 포털 알림이 pull로 충분한지, 푸시 구현(Kafka 등 — 지시문의 금지 목록)을 Phase 8로 미룰지.
6. **공개 경로의 다중 인스턴스 한도**: `RateWindow`는 인스턴스 메모리다. Phase 8 인그레스 문서에 "테넌트 한도는 인스턴스별" 또는 공유 저장소 중 하나를 정해야 한다.
