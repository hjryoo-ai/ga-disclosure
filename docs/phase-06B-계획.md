# Phase 6B 계획 — 준법 큐·계약 연결·징구율·청약 게이트·초안 폐기·보존 재계산·고객 등록 API (승인됨 — `docs/phase-06B-계획승인.md`)

지시문 `docs/phase-06B-지시문.md` v1.0, 6A 수용심사 `docs/phase-06A-수용심사.md`(2026-10-08). 브랜치 `work/phase-6B`(PR #9 병합 `1e38bb2` 위).
**§9(고객 등록 API)는 별도 심사 항목이다** — 나머지가 승인돼도 §9는 따로 승인받는다.

---

## A. 승인 반영 (2026-10-09, `docs/phase-06B-계획승인.md` — 이 절이 아래 본문보다 우선한다)

1. **D2 `SUPERSEDE` 되돌림(§2 거부), `VALIDATE`는 유지.** `authz-matrix`의 `SUPERSEDE` 관리자 칸을 지운다 — 사람 칸이 하나도 남지 않으므로
   **HTTP 경로 `POST /api/v1/disclosures/{id}/supersede`를 컨트롤러·계약에서 지운다**(모두에게 404인 경로를 두지 않는다). 정정 유스케이스는 운영자 CLI
   대리 실행(`disclosure supersede --operator`, 그 주체가 `exceptionApproval.role`로 연결 — 6A 규약)으로만 남는다. 퇴사 설계사의 문서는 관리자가 `VOID`(ORG)하고
   다른 설계사가 새로 작성한다. 업무 규칙(정정 행위자 = `exceptionApproval.role`)과 승인 문언(정정 버전의 `agent_id` = 서명할 설계사)의 관계·재배정은
   §14 새 항목 #20 "정정의 행위자와 재배정"에 올린다(Phase 7 화면 전에 결정 필요). **6B 단계 1b**(첫 구현 커밋).
2. **멱등 요청 해시 = HMAC-SHA256(서버 키, JCS{method, routeTemplate, pathVariables, body})** — 전 라우트. 키는 커서 키와 같은 규약
   (`ga.api.request-hash-key-file`, 저장소 밖, 소유자 전용 600, 없으면 생성, 권한·길이 틀리면 기동 실패, 웹은 필수 — 기본값 없음). JCA는 `infra.crypto`만이므로
   워크플로 포트 `RequestHashPort` + `infra.crypto.RequestHashKey`. 키가 바뀌면 진행 중 청구는 다른 해시라 재생되지 않고 새 요청으로 처리된다(부작용은 상태
   가드). 설계서 §7에 **"6A 결함, 6B 수정"**. **단계 1b**.
3. **징구율**: 안 A `LINKED_COMPLETED_BY_CONTRACT_DATE`가 기본 enum 값, 안 B `TARGET_INCLUDING_UNMATCHED`도 enum에. API 응답·작업 보고서·설계서에
   `definition: "INTERNAL_METRIC_NO_REGULATORY_DEFINITION"`(문구: "내부 지표 — 규제 정의 없음")과 `formula`를 함께 싣고, 이름을 "규제 징구율"로 쓰지 않는다.
   두 안의 근거 링크를 설계서 §6.8에 남긴다. 승인 문언의 "§14 #10"은 이 계획의 새 항목 #17(징구율 정의)로 읽는다 — #10은 실값 목록이고 지시문 오기와 같은
   자리다. #17에 "규제 정의 발견 시 enum 추가로 반영, 기존 스냅샷은 산식 ID로 구분"을 적는다.
4. **게이트 `POST /internal/v1/gate`** — 설계서 §4.4와 계약의 GET 경로 삭제(식별자가 액세스 로그·쿼리에 남지 않게), CHECKSUMS.
5. **스냅샷 유일 키** `(tenant_id, period_month, org_path, rule_version_id)`. `GET /api/v1/collection-rates`의 기본값은 달마다 **그 달 마지막 날(KST)에 ACTIVE였던
   GLOBAL 룰 버전**("그 기간에 가장 최근 ACTIVE였던 버전")의 행, `ruleVersionId` 질의로 다른 버전을 고른다. 응답 항목마다 `ruleVersionId`·`formula`·`definition`.
   같은 키 재계산은 거부(409 `SNAPSHOT_EXISTS`).
6. **계약 연결의 현재값**: `disclosure.policy_no`·`contract_date`는 `contract_link` 활성 행의 현재값이다. V14 가드(GD136): 이 두 컬럼의 UPDATE는 **새 값이
   그 확인서의 활성 `contract_link`(policy_no, contract_date)와 같을 때만**(또는 파기 분기의 `policy_no` NULL) — 표식이 아니라 데이터 정합으로 막으므로 연결
   유스케이스 밖의 UPDATE는 거부된다. 변경마다 감사 `CONTRACT_LINK_CHANGED`(이전·이후 계약일, 증권번호는 `sha256-utf8`, 링크 ID). 첫 연결도 같은 감사
   (`CONTRACT_LINKED`는 쓰지 않는다 — 감사 이름 하나). G4가 Phase 4·5 보존·파기 경로(앵커 연장·`contractLinkWaitDays` 종료)를 재확인한다.
7. **§9 고객 등록 — 조건부 승인 조건 6개**(하나라도 어긋나면 그 단계 전에 멈추고 묻는다):
   - 응답은 `{customerRef, receiptId}`뿐(§9.1의 `created` 삭제 — 기존 고객 여부를 드러내지 않는다). `receiptId`는 등록 감사 행의 ID(가명이 아닌 무작위 UUID)로,
     같은 키 재시도는 같은 영수증(멱등 재생).
   - 한도 초과는 429가 아니라 **업무 거부 422 하나**(`REJECTED` + `{code: REGISTRATION_REJECTED}`) — 한도·그 밖의 등록 거부가 같은 바이트. 형식 오류만 400.
   - 누출 스캔은 **실행 중 생성한 동적 센티널**(요청마다 무작위로 만든 이름·전화·생년월일 그 값 자체)로 돈다.
   - `RegisterCustomer`(Phase 2·3A 규약 — 암호화·가명·등록 키 중복)를 그대로 쓰고, HTTP 계층은 DTO → 값객체 변환·등록 키 도출만 한다(판단 없음).
8. **E4는 열지 않는다**(§10 그대로). **지시문 오기 3곳**은 §12 Q16대로 기록.

단계 순서(§11)는 1b(SUPERSEDE 되돌림 + HMAC)를 2 앞에 끼운다. 나머지는 그대로.

---

## 0. 수용심사 단서 — 6A에서 설계서와 다르게 한 것

6A 보고서 §5(D1~D21)와 "첫 시도에 놓친 3건"을 옮긴다. 마지막 열은 **불변식·인가 표·동일 응답을 넓히는가**에 대한 내 판정이다. ▲는 넓힘, △는 검사 범위의 예외(불변식 자체는 그대로), ─는 좁힘 또는 무관이다.

| # | 지점 | 넓힘? | 근거 |
|---|---|---|---|
| D1 | `notification_outbox` 컬럼명 `customer_ref`·`session_id`, `async_job.params`·`closed_at`, `CLOCK_BEHIND_LATEST`, 앱 롤 DELETE 없음 | ─ | 이름·컬럼 추가, 시계 역행 거부와 DELETE 권한 제거는 좁힘 |
| D2 | **VALIDATE에 MANAGER(ORG) 추가, SUPERSEDE는 MANAGER(ORG)만** | **▲ 인가 표** | 계획 §3.3 표보다 관리자 칸이 둘 늘었다(VALIDATE·SUPERSEDE). SUPERSEDE의 설계사 칸은 빠졌다(좁힘) |
| D3 | 인가 어댑터 `infra.authz`, `AnchorJob` 운영자 문자열, CLI `--role` 거부 | ─ | 위치 변경, CLI는 좁힘 |
| D4 | 예외 장벽, 체인 밖 `denyAll`도 같은 404, `JwtClaimValidator`, platform-spring OIDC 구성 폐기 | ─ | 동일 응답을 오히려 강화(필터 단계 실패가 404로 숨지 않음) |
| D5 | `Idempotency-Replayed`, 본문 상한 32 MiB, 만료 미정리 행은 청구 때 지우고 재청구, JSON 응답 JCS, 규칙 4 `OUTSIDE_CALLERS` | ─ | 만료 행 삭제는 GD120이 원래 허용하던 범위. 규칙 4 예외는 닫힌 FQN 열거 |
| D6 | 상세에 검증 결과 없음, 키셋 상담일\|ID, `requireList`, CLI 커서 키 프로세스마다 | ─ | 응답 축소 |
| D7 | `:`→`_`, 항목 플래그 `Boolean`, `ALREADY_HELD` 409 | ─ | 형식 |
| D8 | 관리자 확인이 완료까지(별도 `/complete` 409) | ─ | Phase 4 유스케이스 그대로 |
| D9 | `disclosure-api.openapi.yaml` 신설 | ─ | 파일이 없었다 |
| D10 | 공개 업무 거부는 범주 무관 422, 공개 본문 4 MiB, `RateWindow.tracks`, `PublicSignLimits` `@NotAnEntry` | ─ | 범주 구분을 없앤 것은 좁힘. `@NotAnEntry`는 OUTSIDE_CALLERS 닫힌 열거 |
| D11 | 게이트 실패는 닫힌 쪽 | ─ | 좁힘 |
| D12 | 요청 원문 로거 INFO 고정, `toString` 가림 | ─ | 좁힘 |
| D13 | **누출 스캔이 기기 토큰 전달 응답 1건을 제외**, `CliOutputScan` 데모 고객 값 | **△ 검사 예외** | 그 응답이 설계상 유일한 전달 경로이고, 예외는 "no-store 단언 + 정확히 1건"으로 묶여 있다. 로그·DB·감사 스캔은 예외가 없다 |
| D14 | IT Hikari 풀 상한 | ─ | 시험 기반 |
| D15 | `afterSeq` 생략 = ack 지점, 머리 너머 422 두 코드, ack 본문 `{upToSeq}`, 피드 읽기 미감사 | ─ | 피드에 개인정보 없음(가명만). 기본 시작점 변경은 at-least-once 그대로 |
| D16 | `EventFeedIT`의 `DisclosureDestroyed`는 운영 `OutboxPort`로 적재 | ─ | 시험 |
| D17~D19 | 데모 OIDC 키 PEM, 토큰을 웹 앱보다 먼저·부트 jar, `HTTP_DEMO_*` | ─ | 데모 |
| D20 | 원격 링크 영수증은 no-store가 아님(재생) | ─ | 수용심사가 좁힘으로 인정. 영수증에 자격 없음 |
| D21 | G1·G7 IT를 10단계에서 더함 | ─ | 시험 추가 |

**첫 시도에 놓친 3건**

| 주입 | 왜 놓쳤나 | 무엇을 고쳤나 | 넓힘? |
|---|---|---|---|
| A2 컨트롤러가 도메인 값을 파싱 | 규칙 (b)의 허용 집합에 "진입점 시그니처의 타입"이 workflow 밖 타입(도메인 값객체)까지 새어 들어갔다 | 허용 집합을 workflow 패키지 타입으로 좁혔다 | ─(규칙을 좁혀 더 엄하게) |
| C6 커서의 목록 종류 미검사 | 시험이 작업 커서를 확인서 목록에 넣었는데, 위치 형식(시각 vs 날짜)이 달라 파싱에서 먼저 400이 났다 — 목록 종류 검사가 없어도 통과 | 위치 형식이 같은 두 목록(보류 ↔ 작업)의 교차 사례를 더했다 | ─ |
| J7 보고서 AAD에서 작업 ID 제거 | 시험이 같은 작업의 보고서만 풀어 봤다 | 다른 작업 ID의 AAD로 풀면 실패해야 한다는 단언을 더했다 | ─ |

**판정 요청**: ▲ D2(인가 표 — 관리자의 VALIDATE·SUPERSEDE)와 △ D13(누출 스캔 예외 1건)이다. 되돌리라면 6B 선행 소과제로 둔다 — D2는 `authz-matrix` 두 칸을 지우고 정정·예외 승인 흐름을 CLI 대리 실행으로만 남기는 것이고(업무 규칙이 관리자를 요구하므로 설계사 칸을 여는 대안은 없다), D13은 발급 응답을 스캔에 넣고 기기 토큰 값만 정확히 한 번 나타나는지 세는 방식으로 바꾸는 것이다. 권장은 둘 다 유지다. D2는 업무 규칙(`exceptionApproval.role`)이 이미 관리자를 요구한다. D13은 예외가 고정 개수로 단언돼 있다.

---

## 1. 첫 커밋(시작 전 반영) — 했다

수용심사 §2 ①·②와 문서. 커밋 `602d865`, `TZ=UTC` 전체 check 12,211건 실패 0·스킵 0.

- **① 플래그 목록**: 행위 `FLAG_READ`(준법 TENANT·관리자 ORG·운영자 ANY, **설계사 칸 없음**) — `authz-matrix` 한 줄. `GET /api/v1/flags?status&type&after&limit`·`GET /api/v1/disclosures/{id}/flags`. 응답은 플래그 ID·유형·상태(OPEN/RESOLVED)·열린 시각·대상 확인서 **ID와 번호**다. 수용심사는 "번호만"이라고 했는데, 봉인 전 확인서(예: `VALIDATION_OVERRIDE`)에는 번호가 없어서 관리자가 대상을 찾을 수 없다. 그래서 ID도 함께 싣는다(번호는 봉인 전이면 null). 관리자 범위는 대상 확인서의 작성 시점 조직이므로, 확인서 없는 테넌트 수준 플래그(`CHAIN_BROKEN` 미부착·룰 플래그)는 관리자에게 보이지 않는다. 계약 `disclosure-api` 1.1.0. 플래그 조회는 감사하지 않는다(개인정보·본문 없음). 시험은 `FlagVisibilityIT` 4건이다.
- **② 400 뒤 키 해제**: 저장하지 않는 응답 뒤 인터셉터가 `IdempotencyService#release`로 **그 청구 순번의 진행 중 행**을 지운다. DB 가드가 만료 전 삭제를 막고 있었기 때문에 **V13 마이그레이션**(`ga_idempotency_key_guard` DELETE 분기 — 진행 중 행은 언제든, 완료 행은 만료 뒤에만)이 필요했다. 시험은 `IdempotencyIT`에 3건(400 → 같은 키 고친 본문 → 202, 404 → 같은 키 다른 요청 → 202, 해제는 자기 순번만)을 더했고, `V12GuardIT.aLiveKeyIsReleasedOnlyWhileInProgress`도 더했다.
- **결과**: 지시문의 "V13 마이그레이션"(6B 본 DDL)은 **V14**가 된다(Q1).
- **위반 주입 Q1~Q7은 전부 의도한 시험에서 잡혔다**(첫 커밋 본문).
  - 설계사 칸 → `FlagVisibilityIT`.
  - 코드만 바꿈 → `AuthzMatrixTest`.
  - 관리자 범위 무시 → `FlagVisibilityIT`.
  - 해제 생략 → `IdempotencyIT` 2건.
  - 순번 조건 제거 → `IdempotencyIT`.
  - V13 가드 미변경 → `V12GuardIT` + `IdempotencyIT`.
  - 완료 행 삭제 허용 → `V12GuardIT` 2건.

---

## 2. ① V14 DDL

한 파일 `V14__compliance_contract_abandon.sql`이다. 기존 `V*`는 고치지 않는다(함수·제약은 `CREATE OR REPLACE`·`DROP/ADD CONSTRAINT`).

### 2.1 계약 연결

```sql
CREATE TABLE contract_link (                       -- append-only 이력. 활성 = superseded_by IS NULL
  tenant_id      TEXT NOT NULL REFERENCES tenant,
  link_id        UUID NOT NULL,
  disclosure_id  UUID NOT NULL,                    -- FK (tenant_id, disclosure_id) → disclosure
  policy_no      TEXT,                             -- EXTERNAL_ID: 파기 함수만 NULL로(그 외 NOT NULL — CHECK: destroyed 표식 없이는 NOT NULL)
  application_no TEXT,                             -- EXTERNAL_ID, 선택
  contract_date  DATE NOT NULL,
  insurer_code   TEXT NOT NULL,
  product_key    TEXT,                             -- 선택. 엔진·카탈로그 키 형식(40자 패턴)
  source         TEXT NOT NULL,                    -- 배치의 source(어댑터 이름)
  source_ref     TEXT NOT NULL,                    -- batchId#항목 순번
  received_at    TIMESTAMPTZ NOT NULL,
  linked_by      TEXT NOT NULL,                    -- 서비스 주체 또는 운영자
  superseded_by  UUID, superseded_at TIMESTAMPTZ,  -- 정정: 새 행 + 이전 행에 한 번
  PRIMARY KEY (tenant_id, link_id)
);
-- 활성 연결 1건/확인서, 활성 증권 1건/테넌트(한 증권이 두 확인서에 붙지 않는다)
CREATE UNIQUE INDEX ux_contract_link_active ON contract_link (tenant_id, disclosure_id) WHERE superseded_by IS NULL;
CREATE UNIQUE INDEX ux_contract_link_active_policy ON contract_link (tenant_id, policy_no) WHERE superseded_by IS NULL AND policy_no IS NOT NULL;
-- 재수입 멱등: 같은 source·source_ref는 한 번
CREATE UNIQUE INDEX ux_contract_link_source ON contract_link (tenant_id, source, source_ref);

CREATE TABLE contract_link_unmatched (            -- 매칭 0건·다수·봉인 전의 보고 행. 개인정보는 policy_no만
  tenant_id TEXT NOT NULL, unmatched_id UUID NOT NULL, policy_no TEXT NOT NULL, contract_date DATE NOT NULL,
  insurer_code TEXT NOT NULL, reason TEXT NOT NULL,  -- UNMATCHED | AMBIGUOUS_MATCH | NOT_SEALED
  source TEXT NOT NULL, source_ref TEXT NOT NULL, received_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (tenant_id, unmatched_id), UNIQUE (tenant_id, source, source_ref)
);
```

- **가드**
  - GD130 `contract_link`: INSERT는 활성으로만 할 수 있다.
  - UPDATE는 두 가지만 허락한다.
    - `superseded_by`·`superseded_at`을 NULL에서 값으로 한 번 바꾸는 것(같은 테넌트의 새 행을 가리켜야 한다).
    - 파기 분기(`ga_destroy_branch('disclosure:'‖id)`)에서 `policy_no`·`application_no`만 NULL로 바꾸는 것.
  - DELETE·TRUNCATE는 거부한다.
  - GD131 `contract_link_unmatched`: UPDATE·TRUNCATE를 거부한다. DELETE는 앱 롤에 주고, 삭제 시점은 정리 작업이 정한다(룰 `contractLink.unmatchedRetentionDays`).
- **기존 컬럼과의 관계(Q2)**: `disclosure.policy_no`·`contract_date`는 V1부터 있는 봉인 뒤 가변 메타데이터이고, Phase 4·5의 보존 앵커(`CONTRACT_DATE`)와 파기 판정(`contractLinkWaitDays`)이 이 컬럼을 읽는다. **활성 연결을 같은 트랜잭션에서 이 두 컬럼에 투영**하면 Phase 4·5 로직을 바꾸지 않고 연결이 반영된다(지시문 "Phase 5 로직 변경 없음"). `contract_link`는 이력이자 정본이다.
- `subject_policy`(V1, 대상 계약 대사)는 지금까지 쓰이지 않았고 이번에도 쓰지 않는다. 대상 계약 판정(§14 #6)이 정해질 때 다시 본다. 테이블은 그대로 둔다.

### 2.2 확인서

- `disclosure.application_no TEXT NULL`
  - 작성 때만 들어간다. CHECK는 `btrim(application_no) <> '' AND length(application_no) <= 64`이다. 형식은 지어내지 않고 §14 새 항목에 둔다.
  - 가드(GD132)는 UPDATE에서 값을 바꾸는 것을 거부한다. 예외는 폐기·파기 분기에서 NULL로 만드는 것뿐이다.
  - 정정 새 버전은 원본의 값을 복제한다(같은 청약).
- `status`
  - CHECK에 `ABANDONED`를 넣고 `abandoned_at TIMESTAMPTZ`를 더한다.
  - CHECK `(status = 'ABANDONED') = (abandoned_at IS NOT NULL)`이고, ABANDONED이면 봉인 컬럼이 전부 NULL이다(번호 없음).
  - 상태 목록을 열거하는 기존 CHECK는 V14가 전수 다시 만든다. 예: V8의 `completed_at` 정합.
- **불변 가드 분기**: `ga_disclosure_guard_update`에 폐기 분기를 넣는다.
  - 조건은 표식 `ga_destroy_branch('abandon:'‖id)`이다. 파기와 같은 경유 조건이며, 표식은 정의자 함수만 세운다.
  - OLD는 가변 상태(DRAFT~REASONED)이고 미파기여야 한다. NEW는 `ABANDONED`·`abandoned_at`, `application_no` NULL이고 그 밖은 같아야 한다.
  - ABANDONED 행은 이후 어떤 변경도 거부한다(GD133). 예외는 고객 파기 경로의 가명 유지뿐이다 — 가명 컬럼은 원래 바뀌지 않는다.
  - 자식 테이블(`disclosure_item`·`recommendation`·`review`)은 `ga_is_mutable_status('ABANDONED') = false`이므로 자동으로 동결된다. 폐기 분기에서만 지정 컬럼을 바꿀 수 있다(§7).

### 2.3 준법 플래그 확장

```sql
ALTER TABLE compliance_flag
  ADD COLUMN assigned_role       TEXT,          -- 룰에서 복사(생성 시 고정)
  ADD COLUMN visible_to_agent    BOOLEAN NOT NULL DEFAULT false,   -- 룰에서 복사(생성 시 고정)
  ADD COLUMN due_at              TIMESTAMPTZ,   -- raised_at + slaHours, slaHours null이면 NULL
  ADD COLUMN assignee            TEXT,          -- AssignFlag(Q9)
  ADD COLUMN sla_breached_at     TIMESTAMPTZ,   -- FLAG_SLA_SWEEP가 한 번
  ADD COLUMN resolution_code     TEXT,          -- 수동 해소의 룰 코드
  ADD COLUMN resolution_evidence JSONB;         -- 닫힌 모양(유형별 키, 앱 스키마 + CHECK jsonb_typeof = object)
ALTER TABLE compliance_flag ADD CONSTRAINT ck_compliance_flag_type CHECK (type IN (
  'GRADE_INCONSISTENT','VALIDATION_OVERRIDE','RULE_SUPERSEDED_DRAFT','IDENTITY_FAILED','SIGNATURE_DEVICE_REUSE',
  'PAPER_SCAN_REVIEW','SIGN_EXPIRED','CHAIN_BROKEN','NOTIFY_FAILED','RULE_DRIFT','RULE_ACTIVATION_MISSED'));
```

- 기존 행은 `assigned_role = 'COMPLIANCE'`, `visible_to_agent = false`, `due_at` NULL로 채운다(fail-closed 기본값).
- `compliance_flag`에는 지금 불변 가드가 **없다**(RLS만). V14가 GD134를 더한다. 이것은 좁힘이다.
  - 식별·유형·대상·열린 시각·`assigned_role`·`visible_to_agent`·`due_at`은 고정한다.
  - 해소(`resolved_*`·`resolution*`)는 한 번, `assignee`는 열린 동안만, `sla_breached_at`은 한 번 바꿀 수 있다.
  - 파기 분기는 `policy_no` NULL만 허락한다.
  - DELETE·TRUNCATE는 거부한다.
- 유형 CHECK ↔ 룰 `complianceQueue.types`의 키 ↔ 코드 상수(`DisclosureFlagPort.Type`·`RuleActivationJob.MISSED_FLAG_TYPE`·`RuleBundleReconciler.FLAG_TYPE`)를 `FlagTypeTableTest`가 셋 다 대조한다.

### 2.4 징구율 스냅샷

```sql
CREATE TABLE collection_rate_snapshot (            -- append-only(GD135)
  tenant_id TEXT NOT NULL, snapshot_id UUID NOT NULL,
  period_month DATE NOT NULL CHECK (extract(day FROM period_month) = 1),   -- KST 기준월의 1일
  org_path TEXT NOT NULL,                           -- '/' = 테넌트 전체, 그 밖은 작성 시점 조직
  formula TEXT NOT NULL,                            -- 룰 collectionRate.formula(닫힌 enum) 그대로
  denominator INTEGER NOT NULL CHECK (denominator >= 0),
  numerator   INTEGER NOT NULL CHECK (numerator >= 0 AND numerator <= denominator),
  rate_bp     INTEGER CHECK ((denominator = 0 AND rate_bp IS NULL) OR rate_bp = (numerator * 10000) / denominator),
  computed_at TIMESTAMPTZ NOT NULL, rule_version_id TEXT NOT NULL, inputs_hash TEXT NOT NULL CHECK (inputs_hash ~ '^[0-9a-f]{64}$'),
  job_id UUID NOT NULL,
  PRIMARY KEY (tenant_id, snapshot_id),
  UNIQUE (tenant_id, period_month, org_path, rule_version_id)               -- Q7
);
```

`rate_bp`는 정수 나눗셈(버림)이고 DB CHECK가 식을 한 번 더 확인한다. `double` 없음.

### 2.5 역할·작업·이벤트·함수

- `identity_link` 역할 CHECK에 `CONTRACT_FEED`·`GATE_CLIENT`를 넣는다. 서비스 주체 단독 CHECK(설계사·조직 없음)에도 둘을 넣는다.
- `async_job.kind`에 `FLAG_SLA_SWEEP`·`COLLECTION_RATE_SNAPSHOT`·`ABANDON_DRAFTS`·`RETENTION_RECOMPUTE`·`CONTRACT_LINK_UNMATCHED_PURGE`를 넣는다. `job-states` 블록은 그대로다.
- `outbox_event.type`에 `DisclosureAbandoned`를 넣는다. 추가형이며 계약 스키마·샘플·CHECKSUMS를 함께 갱신한다. `PolicyLinked`는 **이미 있다**(Phase 4 8개 중 하나) — 그것을 쓴다.
- **함수**
  - `ga_draft_abandon(p_tenant, p_disclosure, p_at, p_by) RETURNS VOID`를 둔다 — 지운 값의 해시는 앱이 호출 직전에 읽어 감사에 남긴다(파기 경로와 같은 규약, 2026-10-09 중간 회신 ⑦로 이 문구를 고침). 소유는 정의자 롤 `disclosure_destroy_definer`, EXECUTE는 새 롤 `disclosure_abandoner`만 갖는다(Q10).
  - `ga_disclosure_destroy`를 CREATE OR REPLACE한다. 지정 컬럼에 `disclosure.application_no`와 그 확인서의 `contract_link.policy_no`·`application_no`(모든 이력 행)를 더한다.
- 오류 코드는 GD130~GD135이고 `docs/db-error-codes.md`에 적는다.

### 2.6 `pii-columns` 블록 추가 행

```
disclosure,application_no,EXTERNAL_ID,ga_disclosure_destroy|ga_draft_abandon,sha256-utf8,disclosure_no·status·hashes·times
contract_link,policy_no,EXTERNAL_ID,ga_disclosure_destroy,sha256-utf8,link id·dates·insurer·source
contract_link,application_no,EXTERNAL_ID,ga_disclosure_destroy,sha256-utf8,same row
contract_link_unmatched,policy_no,EXTERNAL_ID,DELETE(contractLink.unmatchedRetentionDays),-,row removed after the rule period
recommendation,reason_text,FREE_TEXT,ga_disclosure_destroy|ga_draft_abandon,sha256-utf8,reason_codes·item link
review,reason,FREE_TEXT,ga_disclosure_destroy|ga_draft_abandon,sha256-utf8,rule·target hash·approver·time
disclosure_item,field_values,FREE_TEXT,ga_draft_abandon,sha256-jcs,item no·product·insurer·grades (value → '{}')
```

`erasedBy`에 여러 함수를 `|`로 적는 형식은 이미 있다(`legal_hold.reason_text`). `PiiColumnTableTest`가 네 번째 함수(`ga_draft_abandon`)를 본다(지시문 G7).

---

## 3. ② 상태표 변경 — `state-table` 블록 diff

지시문의 "`DRAFT|REASONED|VALIDATED`"에서 `VALIDATED`는 이 시스템의 상태가 아니다(검증은 명령이고 상태를 바꾸지 않는다). 그래서 **봉인 전 상태 전부**(DRAFT·COMPARED·GRADED·REASONED)로 읽는다. 지시문 문언의 오기로 보고한다.

```diff
 DRAFT,COMPARE,COMPARED
 DRAFT,VOID,VOID
+DRAFT,ABANDON,ABANDONED
 COMPARED,REPLACE_ITEMS,COMPARED
 ...
 COMPARED,VOID,VOID
+COMPARED,ABANDON,ABANDONED
 ...
 GRADED,VOID,VOID
+GRADED,ABANDON,ABANDONED
 ...
 REASONED,VOID,VOID
+REASONED,ABANDON,ABANDONED
```

- `ABANDONED`는 종단 상태다. 나가는 전이가 없고, 번호·체인·채번과 무관하다.
- 상태기계(`DisclosureStatus`·전이 표 코드)와 `StateTableTest`(블록 ↔ 코드 양방향)가 함께 움직인다.
- 봉인 전 `VOID`(DRAFT→VOID)와의 차이: VOID는 사람이 문서를 "무효"로 기록한 것이고 사유 텍스트가 남는다. ABANDONED는 버려진 초안의 묘비이고 자유 텍스트를 지운다. 둘 다 그대로 둔다.

---

## 4. ③ `contracts/contract-link/v1/contract-link-batch.schema.json` 초안

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ga-disclosure.example/contracts/contract-link/v1/contract-link-batch.schema.json",
  "title": "계약 연결 배치(인바운드) — 어댑터가 실제 피드 형식(§14 새 항목)을 이 모양으로 바꾼다",
  "type": "object", "additionalProperties": false,
  "required": ["schemaVersion", "source", "batchId", "items"],
  "properties": {
    "schemaVersion": { "const": 1 },
    "source":  { "type": "string", "pattern": "^[A-Z][A-Z0-9_]{0,31}$" },
    "batchId": { "type": "string", "pattern": "^[A-Za-z0-9._:-]{1,64}$" },
    "items": { "type": "array", "minItems": 1, "maxItems": 5000, "items": {
      "type": "object", "additionalProperties": false,
      "required": ["policyNo", "contractDate", "insurerCode"],
      "properties": {
        "policyNo":      { "type": "string", "pattern": "^\\S{1,64}$" },
        "applicationNo": { "type": "string", "pattern": "^\\S{1,64}$" },
        "contractDate":  { "type": "string", "pattern": "^\\d{4}-\\d{2}-\\d{2}$" },
        "insurerCode":   { "type": "string", "pattern": "^[A-Z0-9_]{1,32}$" },
        "customerRef":   { "type": "string", "pattern": "^CR-[0-9a-f]{32}$" },
        "productKey":    { "type": "string", "pattern": "<엔진 계약의 상품 키 패턴 그대로 — 40자>" }
      } } }
  }
}
```

- 형식 검사는 `format`이 아니라 `pattern`이다(엔진 계약 E3 규약과 같다).
- 증권·청약 번호의 실제 형식은 모르므로 **공백 없는 1~64자**만 요구한다(지어내지 않는다).
- 항목 수 상한 5,000은 한 트랜잭션 묶음의 크기다. 큰 피드는 어댑터가 나눈다.
- **매칭 순서**(`ImportContractLinks`, 항목마다)
  - ① `applicationNo`가 있으면 `disclosure.application_no` 정확 일치.
  - ② 없거나 0건이면 `policy_no` 정확 일치(활성 `contract_link`, 이미 연결된 증권의 정정).
  - 후보에서 VOID·SUPERSEDED·파기·ABANDONED는 뺀다.
  - 남은 것이 0건이면 `UNMATCHED`, 2건 이상이면 `AMBIGUOUS_MATCH`다. 둘 다 건너뛰고 `contract_link_unmatched`에 남긴다.
  - 1건이 봉인 전이면 `NOT_SEALED`로 건너뛰고 보고한다(Q5).
  - `customerRef`가 오고 확인서의 가명과 다르면 `CUSTOMER_MISMATCH`로 건너뛴다(보고 행만 남기고 저장하지 않는다).
- **1건 매칭이면 한 트랜잭션에서 다음을 한다.**
  - 활성 연결 확인: 같은 내용이면 NOOP, 다르면 새 행을 넣고 이전 행에 `superseded_by`를 단다.
  - `disclosure.policy_no`·`contract_date`에 투영한다.
  - `retention_until`을 재계산한다. 앵커에 `CONTRACT_DATE`가 생기고, Phase 4 `RetentionAnchors` 그대로 연장만 한다. 계산값이 더 짧으면 그대로 두고 보고서에 `NOT_EXTENDED`로 적는다. 단축 시도는 GD094가 막는다.
  - 감사 `CONTRACT_LINKED`(링크 ID·확인서·source_ref·이전/이후 보존기한. 증권번호는 `sha256-utf8`)를 남긴다.
  - 아웃박스 `PolicyLinked`를 적재한다.
  - 연장되면 산출물 잠금 재적용 대상이 된다(`retention_applied_until < retention_until` — 기존 RECONCILE 경로).
- **항목은 서로 독립이다.** 한 항목의 거부가 배치를 막지 않는다. 결과는 항목별 보고(`LINKED`·`NOOP`·`CORRECTED`·`UNMATCHED`·`AMBIGUOUS_MATCH`·`NOT_SEALED`·`CUSTOMER_MISMATCH`)이고, 보고서는 작업 보고서(암호화) + 응답 요약(건수만)이다.
- **입구는 둘이다.**
  - `POST /internal/v1/contract-links`(서비스 주체 `CONTRACT_FEED`, 본문 = 이 스키마, 202 + 작업 — 작업 종류 `CONTRACT_LINK_IMPORT`). 이 작업 종류를 하나 더 둔다(§2.5의 목록에 추가).
  - CLI `contract-links import --file --format csv|json`. CSV 어댑터는 헤더 `policyNo,applicationNo,contractDate,insurerCode,customerRef,productKey`를 이 스키마로 바꿔 같은 검증을 지난다.
- **기존 `POST /internal/v1/disclosures/{no}/policy-link`(x-ga-phase 6B)는 계약에서 제거한다(권장, Q3).** 번호로 직접 붙이는 경로는 매칭 순서·다수 매칭 판정을 건너뛰고, 같은 사실이 두 입구로 들어온다. 단건은 `items` 1개짜리 배치다.

---

## 5. ④ 징구율 — 정의는 미확정, 후보 2안

**규제 정의를 찾지 못했다.**
- 설계서 §1(규제 환경)에는 GA 담당부서가 "징구율과 기재 정확성"을 주기 점검할 의무가 있다는 문장만 있고(§1 표 1행), 산식은 없다.
- 공개 자료도 같다. 보험신보 「보험GA 준법·내부통제 체크리스트 북 — 보험상품 비교·설명」(2026-06-29)은 "확인서 징구율 및 기재 내용의 정확성을 주기적으로 점검해 미징구 또는 오류 발생 시 즉각 보완 조치", "시스템을 통해 확인서 징구율 등을 파악할 수 있는지?"라고만 쓰고 분모·분자를 정하지 않는다.
- GA협회 업무지침 원문은 찾지 못했다.
- 그래서 **§14 새 항목 #17 "징구율 정의 미확정"**으로 두고, 코드는 룰 `collectionRate.formula`(닫힌 enum)로 두 안을 다 구현한다. 기본 번들의 값은 승인 회신을 따르고 `TODO(confirm#17)`를 단다.
- 지금 번들의 `kpi.collectionRate: "COMPLETED_LINKED / SUBJECT"`는 자유 문자열이다(Phase 1 예시). V14와 함께 `collectionRate.formula`로 바꾸고 `kpi`는 지운다. 번들은 아직 운영 배포 전이라 제자리 수정이 허용된다(`released-bundles.txt` 비어 있음).

**두 안의 공통 틀**(지시문이 고정한 것):
- 기준월은 계약일(`contract_date`)의 KST 달이다.
- 입력은 기준월에 계약일이 있는 **활성 `contract_link`**다.
- 연결 확인서가 파기됐거나(`destroyed_at`) ABANDONED이면 분모·분자 **양쪽에서** 뺀다.
- 묶음은 연결 확인서의 작성 시점 `org_path`별 + 테넌트 전체(`'/'`)다.

| 안 | `formula` | 분모 | 분자 | 근거 | 장단 |
|---|---|---|---|---|---|
| A | `LINKED_COMPLETED_BY_CONTRACT_DATE` | 기준월 활성 연결 수 | 그중 연결 확인서가 COMPLETED이고 `completed_at`(KST 날짜) ≤ 계약일 | 설계서 §6.8의 기존 정의 "완료 확인서가 연결된 대상 계약 / 대상 계약"을 연결 이력으로 옮긴 것, 체크리스트의 "징구"(계약 체결 전에 확인서를 받음) | 지시문 틀("분모·분자는 `contract_link` 활성 행에서")과 정확히 맞는다. **확인서 없이 체결된 계약(미징구)은 분모에 없다** — 연결이 생기지 않으니까 |
| B | `TARGET_INCLUDING_UNMATCHED` | A의 분모 + 같은 달 `contract_link_unmatched`(UNMATCHED만, 같은 증권은 한 번) | A와 같음 | 같은 체크리스트의 "미징구 … 보완"(미징구 계약이 분모에 있어야 보인다) | 미징구가 수치에 드러난다. 대신 미매칭 행은 조직을 모르므로 **테넌트 전체 행에만** 들어가고, 미매칭에는 비교·설명 대상이 아닌 계약도 섞인다(대상 판정 §14 #6 미정). 지시문 틀을 넘는다(분모에 미매칭) — 승인이 필요하다 |

- **권장은 A를 기본값으로 하는 것**이다. B는 enum에 두되 대상 계약 판정(§14 #6)이 정해질 때까지 쓰지 않는다. 미징구는 수치 대신 미매칭 목록(준법 큐가 아닌 보고서)으로 본다.
- **스냅샷**
  - 작업 `COLLECTION_RATE_SNAPSHOT`(스케줄러·준법·운영자)이 **전월**(KST, 실행 시각 기준)을 계산한다.
  - `inputs_hash`는 묶음마다 SHA-256(JCS(입력 확인서 번호 정렬 배열 + 분자 확인서 번호 정렬 배열))이다. 봉인 전 확인서는 입력이 될 수 없으므로 번호가 늘 있다.
  - 같은 (테넌트, 달, 조직, 룰 버전)이 이미 있으면 **거부**한다(409 `SNAPSHOT_EXISTS`, 새 행 없음).
  - 정정은 룰 버전을 올려 새 `rule_version_id`로 한다.
  - 묘비 제외는 계산 시점 기준이다. 저장 뒤의 파기는 행을 바꾸지 않는다(append-only, GD135).
- `GET /api/v1/collection-rates?from&to&orgPath`
  - 새 행위 `COLLECTION_RATE_READ`: 준법 TENANT, 관리자 ORG — 스냅샷의 `org_path`가 주체 조직의 세그먼트 접두 아래인 행만, `'/'` 행은 준법만.
  - 응답에 개인정보는 없다. 같은 달에 룰 버전이 여럿이면 다 낸다(정의 버전 표기 — 설계서 §8 준법 콘솔 "정의 버전").

---

## 6. ⑤ 청약 게이트 — `POST /internal/v1/gate`와 mTLS

**설계서 §4.4·계약 stub과 지시문이 다르다.**
- 설계서 §4.4와 계약의 stub은 `GET /internal/v1/disclosures/gate?customerRef&productKey&asOf`이고, `required`·`gateMode` 등 8필드를 돌려준다.
- 지시문은 `POST /internal/v1/gate {applicationNo|policyNo, customerRef} → {decision, disclosureNo?, pendingRoles?}`이다.
- **지시문을 따르고 stub을 대체할 것을 권장한다(Q4).**
  - 청약 시점의 매칭 키는 상품 키가 아니라 청약번호다. 한 고객이 같은 상품군을 두 번 상담할 수 있다.
  - `gateMode`(BLOCK/WARN/OFF)는 호출자의 진행 정책이라 이 시스템이 판정에 섞을 이유가 없다(§14 #6).
  - `required`(대상 판정)는 미정(§14 #6)이므로 지금 답할 수 없다.

```yaml
/internal/v1/gate:
  post:
    operationId: checkSubscriptionGate
    requestBody: { required: true, content: { application/json: { schema: { $ref: '#/components/schemas/GateRequest' } } } }
    responses: { '200': { content: { application/json: { schema: { $ref: '#/components/schemas/GateDecision' } } } } }
GateRequest:  { type: object, additionalProperties: false, required: [customerRef],
                oneOf: [ { required: [applicationNo] }, { required: [policyNo] } ],
                properties: { applicationNo: {pattern: '^\S{1,64}$'}, policyNo: {pattern: '^\S{1,64}$'}, customerRef: {pattern: '^CR-[0-9a-f]{32}$'} } }
GateDecision: { type: object, additionalProperties: false, required: [decision, reason, disclosureNo, pendingRoles, ruleVersionId],
                properties: { decision: {enum: [ALLOWED, BLOCKED]},
                              reason: {enum: [SATISFIED, NO_DISCLOSURE, CUSTOMER_MISMATCH, AMBIGUOUS, PENDING]},
                              disclosureNo: {oneOf: [null, DisclosureNo]}, pendingRoles: {type: array, items: {enum: [CUSTOMER, AGENT, MANAGER]}},
                              ruleVersionId: {oneOf: [null, string]} } }
```

- **판정**
  - 매칭은 계약 연결과 같은 후보 규칙이다(VOID·SUPERSEDED·파기·ABANDONED 제외). 0건이면 `BLOCKED/NO_DISCLOSURE`, 2건 이상이면 `BLOCKED/AMBIGUOUS`다.
  - 1건이면 `customerRef`가 그 확인서의 가명과 다를 때 `BLOCKED/CUSTOMER_MISMATCH`다.
  - 같으면 `GateFunction.evaluate(status, 고정 룰 signerSet, 서명한 역할, 고정 룰 gateRequiresManager)` **그대로** 판정한다. `satisfied`이면 `ALLOWED/SATISFIED`, 아니면 `BLOCKED/PENDING`(또는 봉인 전·종료 상태는 `NO_DISCLOSURE`)이다.
  - 응답에 개인정보는 없다(번호·역할·룰 버전만).
  - `GateApiIT`가 같은 입력 전수(상태 × 서명 부분집합 × `gateRequiresManager`)를 `GateFunction`과 대조한다.
- **감사**: 요청마다 `GATE_DECISION` 1행을 남긴다. 판정과 같은 트랜잭션이고 BLOCKED도 남긴다.
  - detail은 식별자 종류, **식별자의 `sha256-utf8`**(원문은 남기지 않는다 — 감사 로그는 파기되지 않는 해시 체인이므로 `pii-columns`의 `policy_no` 감사 표현과 같게), 판정·사유, 확인서 ID·번호, 룰 버전이다.
  - 인가 거부(404)는 기존 `AUTHZ_DENIED`가 남긴다.
- **인가**: 행위 `GATE_CHECK`는 `GATE_CLIENT` TENANT만이다. 다른 주체(사람·피드·스케줄러)는 404다. 응답 시간 패딩은 하지 않는다.
- **한도**: 룰 `gate.perMinutePerPrincipal`(정수, 사규 덮어쓰기). 초과는 429 `RATE_LIMITED`이고 감사하지 않는다(유스케이스 전). 실값은 §14 #10(레이트 리밋 실값)으로 `TODO(confirm#10)`, 번들 예시값은 600이다.
- **mTLS(Q11)**
  - 종단은 인그레스(Phase 8)다. 앱은 JWT 체인을 그대로 쓰고, **추가로** 설정 `ga.api.client-cert.subject-header`(예: `X-Client-Cert-Subject`)가 켜져 있으면 게이트·계약 연결 경로에서 그 헤더 값이 JWT `sub`(곧 `identity_link` 주체)와 같아야 한다. 없거나 다르면 404다.
  - 헤더는 인그레스가 덮어써야 믿을 수 있으므로 **`prod` 프로파일에서 이 설정이 없으면 기동이 실패한다**(`DemoKeysGuard`와 같은 기동 가드).
  - 개발·시험은 설정을 켜고 헤더를 직접 보낸다.
  - 헤더를 읽는 것은 `api.security` 필터 하나다(규칙 (e)).

---

## 7. ⑥ 준법 큐 룰 — `complianceQueue`

```json
"complianceQueue": {
  "types": {
    "<TYPE>": { "assignedRole": "COMPLIANCE|MANAGER", "slaHours": null, "visibleToAgent": false,
                "resolutionCodes": [ { "code": "…", "label": "…" } ], "requiresEvidence": false }
  }
}
```

- **스키마**
  - `types`의 키 집합은 닫혀 있다. 스키마 `propertyNames` enum이 위 11개와 같고, `minProperties`/필수 키로 11개 전부를 요구한다.
  - `slaHours`는 `integer ≥ 1 | null`이다. **기본은 null** — 지어내지 않는다.
  - `tenantOverridable`은 유형별 객체 단위로 덮어쓴다.
- **유형 표**: 코드에서 추출했고 `FlagTypeTableTest`가 대조한다. 해소 코드는 어휘 예시이며 `TODO(confirm#18)`(새 §14 항목: 준법 해소 사유 어휘)를 단다.

| 유형 | 올리는 곳 | 담당 | 수동 해소 | 해소 코드(예시) | 근거 필요 |
|---|---|---|---|---|---|
| `GRADE_INCONSISTENT` | `DisclosureService`(엔진 응답 검증 실패) | COMPLIANCE | 예 | `ENGINE_CORRECTED`, `FALSE_POSITIVE` | 아니오 |
| `VALIDATION_OVERRIDE` | `DisclosureService`·`SealService`·`LifecycleService` | MANAGER | **아니오** — 승인·봉인·문서 상태로만 닫힌다(기존) | `[]` | ─ |
| `RULE_SUPERSEDED_DRAFT` | `SealService`·`LifecycleService` | MANAGER | 아니오 — 재기준·문서 상태 | `[]` | ─ |
| `IDENTITY_FAILED` | `SignSessionService` | COMPLIANCE | 예 | `CUSTOMER_CONTACTED`, `NO_ACTION` | 아니오 |
| `SIGNATURE_DEVICE_REUSE` | `SignService`(대리 서명 의심) | COMPLIANCE | 예 | `LEGITIMATE`, `ESCALATED` | 아니오 |
| `PAPER_SCAN_REVIEW` | `SignService` | MANAGER | 아니오 — 스캔 검토 유스케이스 | `[]` | ─ |
| `SIGN_EXPIRED` | `ExpireService` | COMPLIANCE | 예 | `REISSUED`, `CLOSED_NO_CONTRACT` | 아니오 |
| `CHAIN_BROKEN` | `TenantVerifier` | COMPLIANCE | 예 | `VERIFIED_MATCH` | **예** — `{verifyRunJobId}` |
| `NOTIFY_FAILED` | `NotificationDispatcher` | COMPLIANCE | 예 | `RESENT`, `CUSTOMER_CONTACTED` | 아니오 |
| `RULE_DRIFT` | `RuleBundleReconciler` | COMPLIANCE | 예 | `REDISTRIBUTED` | 아니오 |
| `RULE_ACTIVATION_MISSED` | `RuleActivationJob` | COMPLIANCE | 예 | `ACTIVATED`, `NOT_REQUIRED` | 아니오 |

- `visibleToAgent`는 전부 false다(수용심사 §2 ①).
- 설계사 가시성의 의미: true인 유형은 설계사가 **자기 확인서**의 그 플래그를 `GET /api/v1/disclosures/{id}/flags`에서 본다. 이를 위해 `FLAG_READ`에 AGENT OWN 칸을 열되, 저장소가 `visible_to_agent = true`로 거른다. 목록 `GET /api/v1/flags`는 계속 관리자·준법 전용이다.
  - **이 칸을 여는 것은 인가 표를 넓히므로 Q8로 묻는다.** 권장은 6B에서 열지 않는 것이다. 모든 값이 false인 동안 칸을 열 이유가 없고, 열어도 응답이 빈 목록이 되어 404와 구별된다. G3의 "AGENT는 못 봄(404/누락)"은 칸 없음 = 404로 증명한다.
- **유스케이스**(`disclosure-compliance`, 진입점 `@UseCaseEntry`)
  - `ListFlags`는 첫 커밋의 `FlagQueryService`에 필터 `assignedRole`·`dueBefore`를 더한다.
  - `AssignFlag(flagId, assignee)`: 준법은 테넌트, 관리자는 자기 담당 유형·조직이다. 열린 플래그만, 감사는 `FLAG_ASSIGNED`.
  - `ResolveFlag(flagId, code, evidence)`의 순서는 다음과 같다.
    - 코드가 그 유형의 `resolutionCodes`에 없으면 422 `RESOLUTION_CODE_UNKNOWN`이다.
    - 수동 해소 불가 유형이면 422 `NOT_MANUALLY_RESOLVABLE`이다.
    - 근거가 필요한데 없거나 모양이 틀리면 422 `EVIDENCE_REQUIRED`다.
    - 이미 닫혔으면 409 `ALREADY_RESOLVED`다. 재개는 없고, 다시 생기면 새 플래그다(부분 유일 인덱스는 열린 것만 막는다).
    - 성공하면 `resolution = 'COMPLIANCE_RESOLVED'`(기존 시스템 해소 어휘에 하나 추가), `resolution_code`, `resolution_evidence`를 쓰고 감사 `FLAG_RESOLVED`(코드·근거)를 남긴다.
- **`CHAIN_BROKEN` 해소 조건**: `evidence.verifyRunJobId`의 작업이 다음을 모두 만족해야 한다. 아니면 422 `CHAIN_EVIDENCE_REJECTED`이고, 세부 사유는 감사에만 남긴다.
  - 같은 테넌트이고, 종류가 `VERIFY_TENANT`이며, 상태가 `SUCCEEDED`다.
  - `started_at > flag.raised_at`이다. 플래그 이전에 **시작한** 실행은 안 된다.
  - 그 작업의 `report_sha256`와 같은 `reportSha256`을 가진 `VERIFY_RUN` 감사 행이 있고 그 `result = "MATCH"`다.
  - 보고서를 복호화하지 않고, 해시 체인으로 묶인 감사 행으로 판정한다.
- **SLA**
  - 생성 때 `due_at = raised_at + slaHours`이고, null이면 NULL이다.
  - 작업 `FLAG_SLA_SWEEP`은 `due_at < now AND resolved_at IS NULL AND sla_breached_at IS NULL`인 플래그에 `sla_breached_at`을 쓰고 감사 `FLAG_SLA_BREACHED`를 남긴다(건마다). 새 플래그는 만들지 않는다. 스케줄은 Phase 8이다.
- **플래그 생성 시점의 룰 복사**
  - 모든 올림 경로는 `ComplianceFlagRepository.raiseFor` 하나를 지난다(Phase 4 승인 Q13). 그 앞에 워크플로의 `FlagPolicyResolver`가 테넌트의 ACTIVE 룰을 오늘(KST) 기준으로 읽어 `assignedRole`·`visibleToAgent`·`due_at`을 넘긴다.
  - 룰을 해석할 수 없는 경우(예: `RULE_ACTIVATION_MISSED` — ACTIVE 룰이 없음)에는 fail-closed 기본값 `COMPLIANCE/false/NULL`을 쓰고 감사 detail에 `policy: DEFAULT`를 적는다.

---

## 8. ⑦ 보존 재계산 — `RETENTION_RECOMPUTE`

- **제출**: 준법(HTTP `POST /api/v1/jobs/RETENTION_RECOMPUTE`) 또는 운영자 CLI `retention recompute --rule-version <id> [--apply]`다. 매개변수는 `{ruleVersionId, apply: false}`이고 **기본은 dry-run**이다. 행위 `RETENTION_RECOMPUTE`는 준법 TENANT, 운영자 ANY다. 스케줄러에는 없다 — 규제 변경 대응은 사람이 결정한다.
- **입력 검사**: 지정 룰 버전이 이 테넌트에 배포된 GLOBAL 버전이고 상태가 `ACTIVE`·`APPROVED`가 아니면 422 `RULE_VERSION_NOT_USABLE`이다. RETIRED·DRAFT는 쓰지 않는다.
- **대상과 계산**
  - 대상은 봉인 이후 상태이고 `destroyed_at IS NULL`인 확인서다. 파기된 확인서는 제외해 보고서에 건수만 남긴다. ABANDONED는 봉인 전이라 원래 대상이 아니다.
  - 확인서마다 `RetentionAnchors`(Phase 4 그대로)에 그 확인서의 앵커 날짜(봉인일·완료일·계약일 중 룰 `retentionAnchors`가 고른 것)와 **지정 버전의** `retentionYears`·`retentionDays`를 넣어 후보를 계산한다.
  - 후보가 현재값보다 길 때만 `EXTENDED`이고, 같거나 짧으면 `UNCHANGED`다. 단축은 계산은 해도 **쓰는 코드가 없다** — 쓰기 메서드는 "더 길 때만 UPDATE"(`WHERE retention_until < :candidate`) 하나이고, GD094가 한 번 더 막는다.
- **적용(`apply = true`)**
  - 확인서마다 한 트랜잭션에서 `retention_until`을 UPDATE하고 감사 `RETENTION_RECOMPUTED`(이전·이후·룰 버전·작업 ID)를 남긴다.
  - 커밋 뒤 그 확인서의 산출물·증거 잠금을 기존 reconcile 코드(`LockedObject` 경로)로 다시 건다. 실패하면 `retention_applied_until`이 뒤처진 채 남아 다음 `RECONCILE` 작업이 잡는다.
- **dry-run**: 쓰기 0이고 감사도 작업 행 외에는 없다. 보고서에 같은 표를 적는다.
- **보고서**: 작업 보고서(암호화, 기존 규약)로 남긴다. 스키마 `contracts/verify/v1/retention-recompute-report.schema.json`(pattern 형식)이고 확인서별 `{disclosureNo, before, candidate, outcome}`와 건수다.
- **멱등**: 재실행은 이미 연장된 건을 `UNCHANGED`로 본다. 작업 잠금은 기존 `JobLockGateway`(종류별)를 쓴다.

---

## 9. ⑧ 고객 등록 API — **별도 심사 절**

개인정보를 HTTP로 **받는** 첫 경로다. 지금까지 개인정보는 파일(CLI `customer import`)로만 들어왔다.

### 9.1 경로

- `POST /api/v1/customers`(AGENT만, 행위 `CUSTOMER_REGISTER`에 AGENT SELF 칸 추가 — 인가 표를 넓힌다. 이 절의 승인 대상이다).
- 본문 `{name, phone?, birthDate?}`, 응답 201 `{customerRef, created}`. `Location` 없음 — 고객을 읽는 경로가 없다.
- **만들지 않는 것**: `GET /api/v1/customers/search`, `GET /api/v1/customers/{ref}`.
  - `ApiRouteSetTest`(신설)가 매핑 전체 집합을 계약 경로 집합과 대조하고, `/customers`로 시작하는 GET이 없다는 것을 단언한다.
  - 화면은 등록 영수증의 가명을 쓴다.

### 9.2 입력 검증

- DTO는 `CustomerRegisterRequest(String name, String phone, String birthDate)`이고 `toString`은 전부 가린다(규칙 (f)).
- 모르는 필드는 400이다. 본문 상한은 **4 KiB**(라우트별 상한 — 캡처 필터에 경로별 상한 표).
- 값 검증은 Phase 2 값객체(`CustomerName`·`PhoneNumber`·`BirthDate`)가 한다. NFC·trim·제어문자 거부, 전화 `01[016789]…`, 날짜 `yyyy-MM-dd`이고 미래 날짜는 거부한다.
- 오류 응답은 `MALFORMED_REQUEST` + `{field}`뿐이고 값은 싣지 않는다(기존 규약). 주민번호·주소·계좌 필드는 스키마에 없다(`additionalProperties: false`).
- 이 경로의 바인딩 실패 메시지가 값을 담지 않는지는 별도 시험으로 단언한다. Jackson 3 예외 메시지에 원문 조각이 들어가는 경우가 있어, 어드바이스가 메시지를 버리는지 본다.

### 9.3 저장 경로

- 매퍼가 값객체 → `Sensitive` → `NewCustomer`로 바꾸고, `RegisterCustomer.execute(caller, key, customer)`(Phase 2·3A 경로: 암호화·가명 발급·`registration_key` 유일)로 저장한다.
- **등록 키** = `api:` + hex(SHA-256(주체 ‖ 0x00 ‖ Idempotency-Key))[0..40].
  - 같은 주체의 같은 키 재시도는 같은 고객이다(`created: false`) — 멱등 재생과 별개로 DB가 한 번 더 보장한다.
  - 다른 주체의 같은 키는 다른 고객이다.
- 중복 판정은 Phase 2 규약 그대로 등록 키뿐이다. 이름·전화로 중복을 찾지 **않는다** — 찾으면 그것이 열거 경로다.

### 9.4 감사

`CUSTOMER_REGISTER`(기존): 등록 키·결과(CREATED/NOOP)·키 ID·연락처 유무·생년월일 유무다. 값은 없다.

### 9.5 멱등 저장소

- 응답 튜플은 `{body: {customerRef, created}}`이고 개인정보가 없다.
- **요청 해시가 문제다(Q12).** 지금 요청 해시는 SHA-256(JCS{…, body})이고, body에 이름·전화·생년월일이 들어간다. 키가 없는 해시는 사전 대입으로 되돌릴 수 있다 — 전화번호 공간은 10⁸, 생년월일은 3만여, 흔한 이름은 수천이다. Phase 5에서 지운 값의 해시를 다룬 것과 같은 문제다.
- 권장: **이 라우트의 요청 해시를 HMAC-SHA256(서버 키, 같은 입력)으로** 한다.
  - 키는 커서 키와 같은 방식이다. `ga.api.request-hash-key-file`, 저장소 밖, 소유자 전용, 없으면 생성, 권한이 넓으면 기동 실패.
  - 전 라우트에 적용하면 규약이 하나가 되지만, 배포 때 진행 중 키(TTL 24h)가 422로 바뀐다. 그래서 **전 라우트 적용**을 권장하고 배포 노트에 적는다.
  - 대안은 이 라우트만 HMAC이다.
  - 수용심사가 "request_hash는 본문 해시이므로 괜찮다"고 했지만, 개인정보 본문에서는 그렇지 않아 다시 묻는다.
- 본문 원문은 어디에도 저장하지 않는다. 캡처 필터의 요청 속성은 요청 수명 동안만 있다. 이것은 `CustomerRegisterIT`가 DB 전체 덤프(`PlaintextLeakScanIT` 방식)로 재확인한다.

### 9.6 한도

- 룰 `customers.registerPerMinute`(정수, 사규 덮어쓰기)이고 예시값 30에 `TODO(confirm#10)`를 단다.
- 집계는 **DB**로 한다. 같은 트랜잭션에서 주체별 advisory xact lock을 잡고, 그 주체의 지난 60초 `CUSTOMER_REGISTER` 감사 행을 센다. 인스턴스 메모리가 아니라서 다중 인스턴스에서도 같은 한도다(6A 질문 6의 문제를 피함).
- 초과는 429 `RATE_LIMITED`이고 저장·감사는 없다. 멱등 키는 저장하지 않는 응답이라 해제된다.

### 9.7 누출 스캔(G9)

- 센티널 고객(이름·전화·생년월일에 센티널 문자열)을 HTTP로 등록하고 다음을 확인한다.
  - ① 응답 바이트
  - ② 앱 로그(DEBUG 포함 — 6A의 TRACE 사고와 같은 경로)
  - ③ DB 전체 덤프: 감사·멱등·아웃박스·작업, 고객 테이블은 암호문
  - ④ PostgreSQL 서버 로그
  - ⑤ 시험 출력(`scanPlaintextLeaks`)
- 위에서 센티널은 0이어야 한다. 실패 경로(400·429·404)에서도 같다.
- `PlaintextLeakScanIT`(app 쪽 `PublicPlaintextLeakScanIT`의 형제 `ApiPlaintextLeakScanIT`)를 확장한다.

### 9.8 카탈로그 검색

- `GET /api/v1/catalog/products?group&q&insurer`(AGENT·MANAGER·COMPLIANCE, 새 행위 `CATALOG_READ` TENANT — 대상 없음)이다.
- `ProductCatalogPort.searchProducts`를 그대로 쓰고 `asOf`는 오늘(KST)이다. `group`은 필수다(포트가 상품군을 요구한다).
- 응답은 상품 키·이름·보험사·상품군·유효기간이고 개인정보는 없다.

---

## 10. ⑨ 엔진 E4에 요구할 것

- **6B 기능이 엔진에 새로 요구하는 것은 없다.**
  - 계약 연결·게이트·징구율은 엔진을 부르지 않는다.
  - 계약 연결의 `productKey`는 선택이고, 이 시스템의 카탈로그 키 형식(엔진 계약 패턴, 40자)으로 검증만 한다. 증권 단위 수수료 재계산 같은 요구는 없다.
- E4에 남는 것은 이미 아는 셋이다. ① 마이그레이션 번호 전역 단조 문서화 ② V104 `product_key` 폭 40 ③ 엔진 계약 `format` → `pattern`.

---

## 11. 모듈 배치·순서

| 단계 | 내용 | 시험 |
|---|---|---|
| 1 | (했다) 첫 커밋 | 위 §1 |
| 2 | V14 + `db-error-codes` + `pii-columns` + 상태표 블록 + 상태기계 코드 | `V14GuardIT`(GD130~135), `StateTableTest`, `PiiColumnTableTest`, `JobStateTableTest` 무변경 |
| 3 | 룰 스키마: `complianceQueue`·`collectionRate.formula`·`draft.*`·`contractLink.unmatchedRetentionDays`·`gate.perMinutePerPrincipal`·`customers.registerPerMinute`(§9 승인 시), `kpi` 제거, 번들 재해시 | `ContractSchemaTest`, `FlagTypeTableTest` |
| 4 | 준법 큐(`disclosure-compliance` 유스케이스 + `FlagPolicyResolver` + HTTP·CLI) | `ComplianceQueueRulesIT`, `ChainBrokenResolutionIT`, `FlagVisibilityIT` 확장 |
| 5 | 계약 연결(스키마·어댑터 2·유스케이스·작업·HTTP·CLI) | `ContractLinkIT`, `ContractLinkBatchSchemaTest`, `CsvAdapterTest` |
| 6 | 초안 폐기(`ga_draft_abandon`·`AbandonGateway`·유스케이스·배치·이벤트) | `AbandonDraftIT` |
| 7 | 징구율(순수 산식 `disclosure-compliance` + 스냅샷 작업 + HTTP) | `CollectionRateFormulaTest`(순수, 시드 고정 1,000건), `CollectionRateIT` |
| 8 | 게이트(유스케이스·HTTP·mTLS 헤더 가드) | `GateApiIT`, `ClientCertGuardIT` |
| 9 | 보존 재계산 | `RetentionRecomputeIT` |
| 10 | (§9 승인 뒤) 고객 등록·카탈로그 검색 | `CustomerRegisterIT`, `ApiPlaintextLeakScanIT`, `ApiRouteSetTest` |
| 11 | CLI·데모(2회 NOOP)·보고서·태그 | `Phase6BCliIT`, 데모 로그 |

- 계약: `disclosure-internal` 3.0.0이다. 경로 제거(gate stub·policy-link)가 있어 메이저를 올린다 — 구현된 적이 없는 경로이니 2.2.0도 가능하다(Q4에 함께). `disclosure-api` 1.2.0이다.
- 설계서는 v1.14로 이어서 단계마다 같은 커밋에서 고친다.

### 위반 주입 계획(지시문 G11 최소 + 추가)

- **지시문 G11 최소 8건**
  - `CHAIN_BROKEN` 해소 조건 제거
  - 연결 시 보존 단축 허용(코드의 `WHERE retention_until < :candidate` 제거 → GD094가 잡는지, 시험이 잡는지 둘 다)
  - 스냅샷 재계산 허용(유일 제약 제거)
  - 게이트 감사 생략
  - `ABANDONED`가 DELETE
  - `visible_to_agent` 무시
  - 재계산에 단축 경로
  - 고객 등록 본문을 멱등 저장
- **추가**
  - 플래그 유형 룰 키 하나 삭제
  - `GateFunction` 대신 상태만으로 판정
  - 매칭에서 SUPERSEDED를 후보로 남김
  - 미매칭 행에 고객 가명 저장
  - mTLS 헤더 가드 끔(prod)
  - 징구율 분모에 ABANDONED 포함
  - 폐기 함수가 `field_values`를 남김

---

## 12. 질문(권장안 먼저)

1. **V13 번호.** 첫 커밋의 멱등 해제가 V13을 썼다(가드 함수 교체가 필요했다). 6B 본 DDL은 V14다. — 권장: 그대로.
2. **`disclosure.policy_no`·`contract_date`를 활성 연결의 투영으로.** — 권장: 같은 트랜잭션 투영. Phase 4·5 로직을 바꾸지 않는다. `subject_policy`는 그대로 둔다(미사용).
3. **`/disclosures/{no}/policy-link` 제거.** — 권장: 제거하고 단건은 배치 1개. 대안: 유지하되 내부적으로 같은 유스케이스(번호를 매칭 키로).
4. **게이트 계약.** 지시문 `POST /internal/v1/gate`로 §4.4·stub을 대체하고 `gateMode`·`required`를 뺀다. 계약 버전은 3.0.0. — 권장: 그대로. 대안: 2.2.0(미구현 경로 제거라 호환 영향 없음).
5. **봉인 전 확인서에 매칭된 계약.** — 권장: `NOT_SEALED`로 건너뛰고 미매칭 표에 남긴다. 연결하면 보존 앵커가 없는 확인서에 증권이 붙는다.
6. **징구율 정의.** — 권장: 안 A 기본(`LINKED_COMPLETED_BY_CONTRACT_DATE`), 안 B는 enum만. 둘 다 `TODO(confirm#17)`.
7. **스냅샷 유일성.** 지시문의 `(tenant_id, period_month, org_path)` 유일과 "룰 버전을 올리면 새 행"이 함께 성립하지 않는다. — 권장: 유일 키에 `rule_version_id` 포함.
8. **`FLAG_READ` 설계사 칸.** — 권장: 6B에서는 열지 않는다(모든 `visibleToAgent` false). 룰이 true를 쓰기 시작할 때 칸을 연다 — 그때 인가 표 변경으로 다시 승인.
9. **`AssignFlag`의 담당자 컬럼.** 지시문 DDL에 없다. — 권장: `assignee TEXT` 추가. 대안: 담당 역할만(개인 배정 없음).
10. **폐기 함수의 롤.** — 권장: 새 실행 롤 `disclosure_abandoner`(EXECUTE `ga_draft_abandon`만) + 허용 목록 FQN `infra.retention.AbandonGateway`. 대안: 기존 `DestroyerGateway`·`disclosure_destroyer`에 함수 추가(롤 수는 줄지만 설계사 요청 경로가 파기 롤을 쓴다).
11. **mTLS.** JWT + 인그레스가 넣는 인증서 주체 헤더 = `sub` 일치(설정, prod 필수). — 권장: 그대로. 대안: 헤더만으로 인증(JWT 없이) — 체인이 둘이 된다.
12. **(§9) 요청 해시를 HMAC으로.** — 권장: 전 라우트 HMAC(키 파일). 대안: 고객 등록 라우트만.
13. **(§9) 고객 등록의 인가 칸.** `CUSTOMER_REGISTER`에 AGENT SELF(인가 표 넓힘). — 권장: 그대로. 관리자·준법은 등록하지 않는다.
14. **폐기 시 `disclosure_item.field_values`.** NOT NULL이라 NULL이 아니라 `'{}'`로 비운다. — 권장: 그대로(제약 완화 없음).
15. **`draft.abandonAfterDays`의 값.** 지어내지 않는다 — 룰 키는 `integer ≥ 1 | null`이고 null이면 배치가 아무것도 하지 않는다. 데모 번들만 값을 둔다. `draft.abandonReasons`의 코드(예: `CUSTOMER_DECLINED`·`DUPLICATE`·`ENTRY_ERROR`)는 텍스트 없이 코드만 둔다. 둘 다 `TODO(confirm#13)`. — 권장: 그대로.
16. **지시문 문언 정정 기록.** `§14 #10 계약 피드`(실제 #10은 실값 목록 — 계약 피드는 새 #16), `§13(초안 보존)`(실제 §14 #13), `VALIDATED` 상태(없음). — 이 계획이 정본이 되도록 승인 문언에 반영을 요청한다.

새 §14 항목은 다음과 같다(V14 커밋에서 설계서에 더한다).
- #16 계약 피드의 출처·형식·주기
- #17 징구율 정의
- #18 준법 해소 사유 어휘
- #19 청약·증권 번호 형식
