# Phase 6A 계획 — 인가·REST·고객 공개 서명·이벤트 피드·작업 엔드포인트·통지 아웃박스 (승인 대기)

기준: `docs/phase-06A-지시문.md` v1.0, `docs/phase-05-수용심사.md` §2(R1~R3)·§3(D9·D12)·§4(결정 1·2·3·7·8), 설계서 v1.12 §3·§4·§7·§9·§12. 브랜치 `work/phase-6A`(main `8a59cae` = PR #8 병합에서 분기).

- 수용심사 처리(이미 끝남)
  - PR #8 병합: merge commit `8a59cae`. 태그 `phase-5`(`1d9fc3c`)가 main에서 도달 가능한 것을 확인했다.
  - 첫 문서 커밋 `9b10297`: 심사 원문, 6A 지시문 원문, 설계서 v1.12(D9 — 파기 재실행 멱등의 근거는 배치가 단계 전에 읽는 상태).
  - **R3은 "있음"이다.** PR #8 코멘트로 테스트 이름을 보냈다: `VerifyTenantIT.anUntouchedTenantMatchesAndOnlyTheRunIsRecorded`(마지막 감사 행 = `VERIFY_RUN`, `reportSha256` = 보고서 해시), `Phase5CliIT.anchorReceiptAndBothVerifyCommands`(CLI 뒤 `VERIFY_RUN` 정확히 1행). "감사 없는 검증 실행" 발견은 "없으면" 조건이라 만들지 않는다. G12에 두 테스트를 인용한다.
- 지시문의 "시작 전 반영"에서 남은 것(R1·R2·`legalHoldReleaseReasons`)은 R1이 V12에 들어가므로 승인 뒤 **첫 코드 커밋**(§12 2단계)에서 V12 전체와 함께 한다. 이미 커밋한 `V*`는 고치지 않으므로 V12는 한 커밋에서 완성해야 한다.
- 지시문이 요구한 ①~⑦은 §2~§8에 둔다. 문언끼리 또는 기존 코드·계약과 충돌해 결정이 필요한 지점은 §13 질문에 모았고, 질문마다 권장안을 먼저 둔다.

**계획 단계에서 찾은 사실 중 설계에 영향이 큰 것**

1. **HTTP 표면이 0이다.**
   - `disclosure-api`에는 `package-info` 둘뿐이다. 저장소 어디에도 컨트롤러·`@RestControllerAdvice`가 없다.
   - `DisclosureApplicationIT.noApplicationControllersExist`가 "컨트롤러 없음"을 단언한다. Phase 0의 자리표시 단언이므로 6A가 대체한다(§9.4). 약화가 아니라 만료다.
   - 보안 라이브러리는 `platform-spring`에 `compileOnly`로만 있다. 그래서 `OidcResourceServerConfiguration`(`@Profile("oidc")`)은 어떤 앱에서도 로드되지 않는다.
2. **`disclosure`에 `org_path`가 없다.** `org_path`는 `identity_link`에만 있다. MANAGER 범위 산식 "`disclosure.org_path`가 `principal.orgPath` 접두"를 쓰려면 컬럼이 필요하다(Q1).
3. **`identity_link.agent_id`·`org_path`가 NOT NULL이다.** COMPLIANCE·SCHEDULER·FEED_CONSUMER 주체는 설계사가 아니어서 `agent_id`가 없다(Q2).
4. **토큰 생성 시점이 지시문과 다르다.**
   - 지시문 §7은 "발송 직전 토큰 생성·세션에 해시 저장"을 요구한다.
   - 지금 `sign_session.token_hash`는 NOT NULL이고(V8:118), 불변 트리거의 고정 컬럼이며(V8:157·V9:243), 발급 트랜잭션에서 정해진다.
   - "발급 시 미정 → 발송 시 1회 기록"으로 V12가 가드를 바꿔야 한다(Q3).
5. **발송 경로가 Phase 2 `phoneForNotification`를 쓰지 않는다.** 지금 `SignSessionService.send`는 같은 볼트 읽기와 감사(`CUSTOMER_PHONE_READ`)를 직접 한다. 지시문대로 디스패처가 `phoneForNotification` 경로를 탄다.
6. **이벤트 피드 계약은 이미 정본에 있고 포털 규약을 따른다.**
   - `afterSeq`·`nextSeq`·`headSeq`를 쓴다. `ContractSchemaTest.eventFeedResponseFollowsPortalConvention`이 이를 단언한다.
   - 지시문은 `after={서명된 커서}`를 요구한다(Q5).
   - `outbox_event.published_at`를 쓰는 본 코드는 아직 없다. 가드는 NULL→값 1회만 허용한다(V8, GD106).
7. **잡 단일 실행 장치가 없다.**
   - advisory lock은 감사·아웃박스 추가용 트랜잭션 잠금 두 개뿐이다.
   - 앵커는 REPEATABLE READ + 유일 제약, 파기는 단계 멱등으로 겹침을 견딘다.
   - 작업 단위 잠금은 트랜잭션 여러 개에 걸치므로 **세션 잠금 + 전용 커넥션**이 필요하다. 그 커넥션은 직접 DB 접근 허용 목록의 새 항목이고, 규칙 5에 따라 전용 롤을 쓴다(Q8).
8. **GLOBAL 룰은 테넌트마다 복제되어 있다.** 공개 경로의 패딩 하한(`publicSign.minResponseMillis`)을 **없는 테넌트** 요청에도 같게 적용하려면 테넌트 없이 읽을 값이 필요하다(Q6).
9. **R1 CHECK에 걸리는 기존 테스트가 있다.** `AnchorGuardIT`이 `created_at = now()`와 고정 날짜로 앵커를 직접 넣는다. 테스트 데이터를 CHECK에 맞게 고친다. 단언은 그대로 두고 "CHECK 거부" 단언을 더한다.
10. **평문 누출 스캔의 사각이 있다.** `OperatorCliIT`·`Phase5CliIT`는 `System.out`을 버퍼로 돌린다. 그래서 그 동안의 출력이 결과 XML에 가지 않고 `scanPlaintextLeaks`가 보지 못한다. 6A의 앱 IT는 버퍼·로그를 직접 센티널 스캔한다(§9.5).

---

## 1. 반영 지시와 룰 키 (2단계 커밋)

### 1.1 R1 — 앵커 소급 기입 폐지
- **V12**: `ALTER TABLE anchor ADD CONSTRAINT ck_anchor_date_is_creation_day CHECK (anchor_date = (created_at AT TIME ZONE 'Asia/Seoul')::date)`.
  - 검증형 CHECK이다(`NOT VALID` 아님). 기존 행이 어기면 마이그레이션이 실패한다.
  - V9의 `audit_anchor` 행 0 단언과 같은 태도다. 소급 행이 있는 DB는 보고하고 사람이 처리한다(Q13).
- **`AnchorJob`**
  - `--date`가 시계의 KST 날짜와 다르면 테넌트마다 `DATE_NOT_TODAY`로 거부한다.
  - 미래 날짜 `DATE_IN_FUTURE`(Phase 5 D)는 `DATE_NOT_TODAY`에 흡수한다.
  - `DATE_NOT_AFTER_LATEST` 분기는 도달할 수 없으므로 지운다. GD110(날짜 역행)은 DB에 그대로 남는다.
- **`verify tenant`에 운영 발견 `ANCHOR_MISSING_DAY`를 더한다.**
  - 앵커는 내용 변화와 무관하게 매일 만들어진다(앵커 감사 행이 머리를 바꾼다). 그래서 "빠진 날"은 첫 앵커 날짜부터 **어제(KST, 검증 시계)**까지 앵커가 없는 달력 날짜다. 오늘은 아직 돌지 않았을 수 있어 빼고, 끝의 공백은 포함한다.
  - 공백 구간마다 발견 1건: `where {afterAnchorSeq, fromDate, toDate}`, `detail {days}`. `where`는 이미 열린 객체(string·integer·null)라 스키마 변경은 `findingCode` enum 하나다.
  - `OPERATIONAL` 집합에 넣는다. `CHAIN_BROKEN`이 아니다(D8).
  - 결과를 MISMATCH로 만드는지는 `ANCHOR_UNSTAMPED`와 같게 한다(Q10).
- **데모 "두 날"**: `seed.sh`의 `anchor run --date 어제`를 지우고, 데모 시계 오프셋 `--ga.demo.clock-offset=-P1D`로 첫 앵커를 만든 뒤 실제 시계로 오늘 앵커를 만든다. `created_at`이 오프셋 시계의 시각이므로 CHECK와 맞는다.
- **설계서**: §6.7(L867 A단계 문장, L844 TSA 실패 문장의 "같은 날짜 둘째 배치" 확인), 부록 B(L1201·L1209 두 날 앵커 문장), `merkle-spec` 영향 없음.

### 1.2 R2 — 데모 번들 차집합 테스트
- `DemoShortBundleTest`를 차집합 단언으로 바꾼다.
  - 두 본문 각각에서 최상위 키 집합과 값을 비교한다.
  - **값이 다른 키의 집합 = {`retentionYears`, `retentionDays`}** 이고, 어느 한쪽에만 있는 키는 0이다.
  - 지금 테스트는 두 키를 지운 뒤 같음만 본다. 그래서 "보존 키가 같아졌다"(데모가 규제값으로 돌아감)를 잡지 못한다. 차집합은 그것도 잡는다.
- 비교 대상: 규제 번들 중 **데모 시계 기준일에 시행되는 것**(DISC-2026-07). DISC-2027-01과의 비교도 같은 테스트에 둔다. 규제 번들 둘이 보존 외 키에서 다르면 그 차이가 데모 번들 쪽에도 같아야 하므로, 데모는 2026-07과 맞추고 2027-01과의 차집합은 "2026-07 ↔ 2027-01 차집합 ∪ 보존 키"로 단언한다.
- 주입: 규제 번들의 키 하나를 바꾸고 데모 번들은 그대로 둔다 → 실패.

### 1.3 R3 — 확인 끝(위 머리말). 코드 변경 없음.

### 1.4 `legalHoldReleaseReasons` (D12, `TODO(confirm#15)` 제거)
- 룰 스키마: `legalHoldReasons`와 같은 모양에서 `requiresText`를 뺀 `{code, label}`이다. 해제에는 텍스트 컬럼이 없다.
  - `minItems 1`, 코드 패턴 `^[A-Z][A-Z0-9_]{0,39}$`.
  - GLOBAL 전용: `tenantOverridable` 거부 enum에 추가한다.
- 번들 값(예시 데이터, 네 번들 모두): `CASE_CLOSED`, `INQUIRY_CLOSED`, `COMPLAINT_RESOLVED`, `PLACED_IN_ERROR`. 기존 테스트가 쓰는 `CASE_CLOSED`를 포함한다.
- `LegalHoldService.release`는 오늘(KST) 룰을 해석해 목록 밖이면 `BAD_RELEASE_REASON`으로 거부한다. 정규식 상수와 TODO를 지운다.
- 설계서 §9·§14 #15를 "확정(6A)"으로, 부록 D에 키를 추가한다.

### 1.5 6A 룰 키 (전부 GLOBAL 전용, 번들 제자리 재해시 — `released-bundles.txt` 비어 있음)

| 키 | 모양 | 예시값 | 쓰는 곳 |
|---|---|---|---|
| `legalHoldReleaseReasons` | `[{code,label}]` | 위 4개 | 보류 해제 |
| `api.idempotencyTtlHours` | 정수 ≥ 1 | 24 | `idempotency_key.expires_at` |
| `api.idempotencyLeaseSeconds` | 정수 ≥ 1 | 120 | 진행 중 키의 인수 시점(§4.2) |
| `publicSign.minResponseMillis` | 정수 ≥ 1 | 400 | 공개 응답 패딩 하한 |
| `publicSign.tenantRatePerMinute` | 정수 ≥ 1 | 600 | 공개 경로 테넌트 분당 한도 |
| `notify.retry` | `{maxAttempts ≥1, initialDelaySeconds ≥1, multiplier ≥1(정수), maxDelaySeconds ≥ initialDelaySeconds}` | `{5, 60, 2, 3600}` | 통지 백오프·소진 |

- 정수만 쓴다(double 금지). `multiplier`가 정수이므로 백오프는 정수 산술이다(§7).
- `EffectiveRule` 접근자는 키가 없으면 `MissingRuleKeyException`이다(기본값 없음, `missingKeysFailWithoutDefaults`에 추가).

---

## 2. V12 DDL (①) — `V12__api_jobs_notify.sql`

번호: V12의 사용자 정의 오류는 **GD120~GD12x**다. 마이그레이션별 10단위 관례에 따른다. CHECK 위반은 표준 23514이다. 롤 존재·멤버십 단언은 V9 방식을 따른다.

### 2.1 R1·4-eyes
```sql
ALTER TABLE anchor ADD CONSTRAINT ck_anchor_date_is_creation_day
    CHECK (anchor_date = (created_at AT TIME ZONE 'Asia/Seoul')::date);
ALTER TABLE legal_hold ADD CONSTRAINT ck_legal_hold_four_eyes
    CHECK (released_by IS NULL OR released_by <> placed_by);
```

### 2.2 `identity_link` — 설계사가 아닌 주체 (Q2)
```sql
ALTER TABLE identity_link ALTER COLUMN agent_id DROP NOT NULL, ALTER COLUMN org_path DROP NOT NULL;
ALTER TABLE identity_link ADD CONSTRAINT ck_identity_link_roles CHECK (
    cardinality(roles) >= 1
    AND roles <@ ARRAY['AGENT','MANAGER','COMPLIANCE','SCHEDULER','FEED_CONSUMER']::text[]);
ALTER TABLE identity_link ADD CONSTRAINT ck_identity_link_agent CHECK (
    NOT ('AGENT' = ANY(roles)) OR agent_id IS NOT NULL);
ALTER TABLE identity_link ADD CONSTRAINT ck_identity_link_org CHECK (
    NOT (roles && ARRAY['AGENT','MANAGER']::text[]) OR org_path ~ '^(/[A-Za-z0-9_-]+)+$');
-- 서비스 주체(SCHEDULER·FEED_CONSUMER)는 사람 역할과 섞지 않는다
ALTER TABLE identity_link ADD CONSTRAINT ck_identity_link_service_alone CHECK (
    NOT (roles && ARRAY['SCHEDULER','FEED_CONSUMER']::text[]) OR cardinality(roles) = 1);
```
- `OPERATOR`는 `identity_link` 역할이 아니다. CLI 채널에서만 생긴다(§3).
- 기존 행은 `{AGENT}`·`{MANAGER}`와 경로 `/HQ…`이므로 CHECK를 통과한다. 데모 시드에 COMPLIANCE·SCHEDULER·FEED_CONSUMER 주체를 추가한다.

### 2.3 `disclosure.org_path` — 작성 시점 조직 스냅샷 (Q1)
```sql
ALTER TABLE disclosure ADD COLUMN org_path TEXT;
ALTER TABLE disclosure ADD CONSTRAINT ck_disclosure_org_path CHECK (org_path IS NULL OR org_path ~ '^(/[A-Za-z0-9_-]+)+$');
```
- 가드(CREATE OR REPLACE, V3·V7·V9 분기 보존)
  - INSERT에서 `org_path IS NULL`이면 **GD124**.
  - UPDATE에서 바꾸면 **GD124**(작성 시 1회 고정).
  - 기존 행은 NULL로 남는다. 백필하지 않는다(Q1). NULL 행은 MANAGER 범위에 들지 않는다. 소유 AGENT와 COMPLIANCE만 닿는다.
- 작성 유스케이스가 `identity_link`에서 해석한 `org_path`를 기록한다(`agent_id`와 같은 경로).
- 접두는 **경로 세그먼트 단위**다: `d.org_path = p OR d.org_path LIKE p || '/%'`. `p`의 `%`·`_`는 패턴 CHECK로 배제되지만 `_`는 허용 문자이므로 `LIKE … ESCAPE`로 이스케이프한다. `/HQ`는 `/HQX`에 맞지 않는다.

### 2.4 `sign_session` — 발송 시 토큰 (Q3)
```sql
ALTER TABLE sign_session ALTER COLUMN token_hash DROP NOT NULL;
ALTER TABLE sign_session ADD CONSTRAINT ck_sign_session_token_at_send CHECK (
    token_hash IS NOT NULL OR (channel = 'REMOTE_LINK' AND sent_at IS NULL));
ALTER TABLE sign_session ADD CONSTRAINT ck_sign_session_sent_has_token CHECK (
    sent_at IS NULL OR token_hash IS NOT NULL);
```
- 가드 `ga_sign_session_guard`를 CREATE OR REPLACE한다(V9의 파기 분기 그대로).
  - `token_hash`를 고정 컬럼에서 빼고 별도 규칙을 둔다: **NULL→값 1회, 같은 문장에서 `sent_at`도 NULL→값, 상태 OPEN일 때만**. 어기면 **GD123**.
  - 그 밖의 변경은 기존 GD101 그대로다.
- TOUCH_PAD·PAPER_SCAN은 지금처럼 발급 때 토큰을 정한다. 현장 기기에 넘기므로 발송 단계가 없다.
- `ux_sign_session_token`(tenant, token_hash) 유일 인덱스는 NULL을 여럿 허용하므로 그대로다.

### 2.5 `idempotency_key`
```sql
CREATE TABLE idempotency_key (
    tenant_id       TEXT        NOT NULL REFERENCES tenant(tenant_id),
    actor_subject   TEXT        NOT NULL,
    key             TEXT        NOT NULL CHECK (key ~ '^[A-Za-z0-9_-]{16,128}$'),
    request_hash    TEXT        NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),   -- SHA-256(JCS{method, routeTemplate, pathVars, body})
    claim_seq       SMALLINT    NOT NULL DEFAULT 1 CHECK (claim_seq >= 1),           -- 임차 인수 횟수(§4.2)
    claimed_at      TIMESTAMPTZ NOT NULL,
    response_status SMALLINT    CHECK (response_status BETWEEN 200 AND 599),
    response_ref    JSONB,                                                          -- 닫힌 영수증 튜플(Q4), 개인정보 없음
    response_hash   TEXT        CHECK (response_hash ~ '^[0-9a-f]{64}$'),
    created_at      TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (tenant_id, actor_subject, key),
    CONSTRAINT ck_idem_done CHECK ((response_status IS NULL) = (response_hash IS NULL)
                                   AND (response_status IS NULL) = (response_ref IS NULL)),
    CONSTRAINT ck_idem_expiry CHECK (expires_at > created_at)
);
```
- 가드 **GD120**
  - 완료 행(`response_status IS NOT NULL`)은 불변이다.
  - 진행 중 행은 `claim_seq +1`·`claimed_at`만 바꾸거나(인수) 완료 3컬럼을 한 번 쓴다.
  - `request_hash`·`key`·`actor_subject`는 불변이다.
  - DELETE는 `OLD.expires_at < now()`일 때만 허용하고, TRUNCATE는 거부한다.
- RLS와 테넌트 정책, 앱 롤 GRANT SELECT·INSERT·UPDATE·DELETE.
- 키는 **테넌트 × 주체 × 키**로 범위를 정한다. 다른 주체가 같은 키를 써도 서로 보이지 않는다.

### 2.6 `async_job`
```sql
CREATE TABLE async_job (
    tenant_id      TEXT        NOT NULL REFERENCES tenant(tenant_id),
    job_id         UUID        NOT NULL,
    kind           TEXT        NOT NULL CHECK (kind IN ('ANCHOR','EXPIRE','RECONCILE','DESTROY','DESTROY_DRY_RUN',
                                                        'VERIFY_TENANT','NOTIFY','IDEMPOTENCY_PURGE')),   -- 뒤의 둘은 Q12
    status         TEXT        NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    requested_by   TEXT        NOT NULL,
    channel        TEXT        NOT NULL CHECK (channel IN ('HTTP','CLI')),
    requested_at   TIMESTAMPTZ NOT NULL,
    started_at     TIMESTAMPTZ,
    finished_at    TIMESTAMPTZ,
    result_ref     TEXT,                      -- {tenant}/reports/{job_id}
    report_sha256  TEXT CHECK (report_sha256 ~ '^[0-9a-f]{64}$'),   -- 평문 보고서 해시(열람 대조)
    report_key_wrapped BYTEA,                 -- 보고서 DEK(테넌트 KEK로 감쌈), 개인정보 아님
    report_kek_id  TEXT,
    error_code     TEXT CHECK (error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    PRIMARY KEY (tenant_id, job_id),
    CONSTRAINT ck_job_times CHECK (... 상태별 시각 결속 ...),
    CONSTRAINT ck_job_result CHECK ((status = 'SUCCEEDED') = (result_ref IS NOT NULL)
                                    AND (status = 'FAILED') = (error_code IS NOT NULL))
);
CREATE UNIQUE INDEX ux_async_job_active ON async_job (tenant_id, kind) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX ix_async_job_recent ON async_job (tenant_id, requested_at DESC, job_id);
```
- 상태 전이 가드 **GD121**(§6 표)
  - INSERT는 QUEUED이고 시각·결과가 비어 있어야 한다.
  - 허용 전이는 표의 넷뿐이다. 종단 행은 불변이고, DELETE·TRUNCATE는 거부한다.
- `ux_async_job_active`는 advisory lock의 **벨트**다. 잠금 장치가 고장 나도 같은 테넌트·종류의 활성 작업은 둘이 될 수 없다.
- 보고서는 `document_artifact`가 아니라 테넌트 저장소 `reports/` 접두에 둔다(지시문).
  - 암호화는 산출물과 같은 형식(AES-256-GCM, `0x01‖nonce‖암호문‖tag`)이다.
  - 키는 **보고서마다 DEK**를 테넌트 KEK로 감싸 이 행에 둔다. 감싸기 AAD = JCS `{jobId, kekId, tenantId, v:1}`, 본문 AAD = JCS `{jobId, kind:"REPORT", tenantId, v:1}`.
  - 문서 키(`document_key`)는 확인서에 묶여 있어 쓰지 않는다.
  - 보고서 객체에는 Object Lock을 걸지 않는다. 보존 의무가 없는 운영 산출물이다.
- `pii-columns`에 `async_job.report_key_wrapped`를 `RETAINED`(키 바이트, 개인정보 아님)로 추가한다. `PiiColumnTableTest`의 BYTEA 대조가 요구한다.

### 2.7 `notification_outbox`
```sql
CREATE TABLE notification_outbox (
    tenant_id        TEXT        NOT NULL REFERENCES tenant(tenant_id),
    notification_id  UUID        NOT NULL,
    kind             TEXT        NOT NULL CHECK (kind IN ('SIGN_LINK')),
    recipient_ref    TEXT        NOT NULL,           -- customer_ref (가명) — 전화번호 아님
    payload_ref      UUID        NOT NULL,           -- sign_session.session_id — 토큰 아님
    attempts         SMALLINT    NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at  TIMESTAMPTZ NOT NULL,
    status           TEXT        NOT NULL CHECK (status IN ('PENDING','SENT','DEAD','CANCELLED')),
    last_error_code  TEXT        CHECK (last_error_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    created_at       TIMESTAMPTZ NOT NULL,
    sent_at          TIMESTAMPTZ,
    PRIMARY KEY (tenant_id, notification_id),
    FOREIGN KEY (tenant_id, recipient_ref) REFERENCES customer_ref (tenant_id, customer_ref),
    FOREIGN KEY (tenant_id, payload_ref) REFERENCES sign_session (tenant_id, session_id),
    CONSTRAINT ck_notify_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);
CREATE UNIQUE INDEX ux_notification_session ON notification_outbox (tenant_id, payload_ref) WHERE kind = 'SIGN_LINK';
CREATE INDEX ix_notification_due ON notification_outbox (tenant_id, next_attempt_at) WHERE status = 'PENDING';
```
- 가드 **GD122**
  - INSERT는 PENDING, `attempts` 0이다.
  - 전이는 PENDING→{SENT, DEAD, CANCELLED} 1회다. PENDING 안에서는 `attempts +1`·`next_attempt_at` 전진·`last_error_code`만 바꿀 수 있다.
  - 식별·참조 컬럼은 불변이고, DELETE·TRUNCATE는 거부한다.
- **토큰 원문·전화번호를 담을 컬럼이 없다.** 담을 곳이 없으므로 저장할 수 없다. `PlaintextLeakScanIT`(DB 덤프)과 G10 테스트가 행을 스캔한다.
- `pii-columns`에 `notification_outbox.recipient_ref`를 `RETAINED`(가명, 발송 이력)로 추가한다. 아웃박스 이벤트와 같은 판단(결정 7)이다.

### 2.8 롤 — 작업 잠금 전용 롤 (Q8)
- `init-roles.sql`: `disclosure_job_lock` — LOGIN, NOINHERIT, NOBYPASSRLS, 테이블 GRANT 0. 데이터베이스 CONNECT만 갖는다.
  - advisory lock 함수는 PUBLIC 실행이고, 잠금 공간은 롤과 무관하게 DB 전체다.
- V12는 롤 존재와 GRANT 0(어느 테이블에도 권한 없음)을 단언한다.
- 직접 DB 접근 허용 목록 **네 번째 항목** `com.ga.disclosure.infra.jobs.JobLockGateway`.
  - 테넌트 데이터를 읽지 않는다. 실행하는 SQL은 `pg_try_advisory_lock`·`pg_advisory_unlock`뿐이다.
  - 전용 롤을 쓴다. 설계서 §9 허용 목록과 ArchUnit `DB_INFRASTRUCTURE`에 FQN을 등재한다.

### 2.9 `db-error-codes.md`
GD120 `idempotency_key`, GD121 `async_job`, GD122 `notification_outbox`, GD123 `sign_session` 발송 시 토큰, GD124 `disclosure.org_path`. 같은 커밋에서 V11이 GD113을 `legal_hold`에도 쓰는 것을 GD113 행에 덧붙인다(Phase 5 누락분).

---

## 3. 인가 모델 (②)

### 3.1 타입 (`disclosure-workflow.authz`, Spring 무의존)

| 타입 | 내용 |
|---|---|
| `Caller(TenantId tenant, String subject, Channel channel)` | 토큰에서 오는 **전부**. `Channel ∈ {HTTP, CLI, SIGN_TOKEN}`. 역할 없음 |
| `Action` (닫힌 enum) | 유스케이스 이름과 1:1(§3.3) |
| `Target` (sealed) | `None` · `Disclosure(id)` · `Session(id)` · `Hold(id)` · `Job(id)` · `JobKind(kind)` · `Feed` |
| `Principal(subject, Set<Role> roles, Optional<AgentId>, Optional<OrgPath>)` | `identity_link` 해석 결과. **포트 안에서만** 만들어진다 |
| `Actor(subject, role)` (기존) | 감사에 적는 주체. `require`의 반환값 = 허가를 준 역할 |
| `AuthorizationDenied` | 거부 예외 하나. 사유(`NO_LINK`·`ROLE`·`SCOPE`·`NOT_FOUND`)는 감사에만 남긴다 |
| `AuthorizationPort.require(Caller, Action, Target) → Actor` | 인가 진입점 |

- 해석은 매 요청이고 캐시가 없다. `identity_link`를 바꾸면 다음 요청부터 반영된다.
- 범위 판정은 순수 함수 `ScopePolicy.permits(principal, action, facts)`이다(테이블 주도 단위 테스트). 어댑터(`disclosure-app.authz.IdentityLinkAuthorization`)는 사실(`identity_link` 행, 대상 확인서의 `agent_id`·`org_path`, 보류·작업의 종류)만 읽는다.
- 대상이 없으면(RLS로 안 보이는 다른 테넌트 자원 포함) 사유 `NOT_FOUND`로 `AuthorizationDenied`이다. 권한 없음과 **같은 예외·같은 404**다.

### 3.2 채널별 해석

| 채널 | 해석 |
|---|---|
| HTTP | `identity_link(tenant, subject)` 행이 없으면 거부(`NO_LINK`). 역할은 행의 `roles`. **JWT 클레임은 `sub`·테넌트 클레임 외 읽지 않는다** |
| CLI | 역할 `OPERATOR`, 범위 검사 통과(지시문). 감사 역할 = `OPERATOR`(Q9). `OPERATOR`는 HTTP에서 생길 수 없다 |
| SIGN_TOKEN | 공개 서명. 토큰이 곧 자격이고 대상은 토큰이 가리키는 세션 하나다. 포트는 `Target.Session`이 토큰의 세션과 같은지만 본다 |

### 3.3 역할 × 행위 × 범위 (기계 판독 블록 `authz-matrix` — 설계서 §9에 두고 `AuthzMatrixTest`가 `ScopePolicy`와 양방향 대조)

범위 기호
- `OWN` = `disclosure.agent_id = principal.agentId`
- `ORG` = `disclosure.org_path`가 `principal.orgPath`의 세그먼트 접두(§2.3)
- `TENANT` = 테넌트 전체
- `—` = 불가

| Action (유스케이스) | AGENT | MANAGER | COMPLIANCE | SCHEDULER | FEED_CONSUMER | 감사 |
|---|---|---|---|---|---|---|
| `DISCLOSURE_CREATE` (`createDraft`) | 자기(연결 필수) | — | — | — | — | 기존 |
| `DISCLOSURE_READ` (상세·목록) | OWN | ORG | TENANT | — | — | COMPLIANCE는 `DISCLOSURE_VIEW` 1행/요청 |
| `ITEMS_REPLACE`·`COMPARE`·`GRADES_REQUEST`·`RECOMMENDATIONS_SET`·`VALIDATE` | OWN | — | — | — | — | 기존 |
| `SEAL` | OWN | — | — | — | — | 기존 |
| `VOID` | OWN | ORG | — | — | — | 기존 |
| `SUPERSEDE`·`REBASE` | OWN | — | — | — | — | 기존 |
| `EXCEPTION_APPROVE` | — | ORG | — | — | — | 기존 |
| `ARTIFACT_VIEW` (PDF·SIGNED_PDF·EVIDENCE_ZIP) | OWN | ORG | TENANT | — | — | `ARTIFACT_VIEW` |
| `SIGN_SESSION_ISSUE` | OWN | — | — | — | — | 기존 |
| `FACE_TO_FACE_CONFIRM`·`PAPER_SCAN_UPLOAD` | OWN(토큰 + 설계사) | — | — | — | — | 기존 |
| `AGENT_SIGN` | OWN | — | — | — | — | 기존 |
| `MANAGER_CONFIRM`·`PAPER_SCAN_REVIEW` | — | ORG | — | — | — | 기존 |
| `COMPLETE` | OWN | ORG | — | — | — | 기존 |
| `LEGAL_HOLD_PLACE` | — | — | TENANT | — | — | 기존 |
| `LEGAL_HOLD_RELEASE` | — | — | TENANT, **해제자 ≠ 설정자**(유스케이스 + DB CHECK) | — | — | 기존 |
| `LEGAL_HOLD_READ` | — | — | TENANT | — | — | `LEGAL_HOLD_VIEW` |
| `RECEIPT_EXPORT` | — | — | TENANT | — | — | `ANCHOR_RECEIPT_EXPORTED` |
| `JOB_SUBMIT:VERIFY_TENANT` | — | — | TENANT | TENANT | — | `JOB_QUEUED` |
| `JOB_SUBMIT:{ANCHOR,EXPIRE,RECONCILE,DESTROY,DESTROY_DRY_RUN,NOTIFY,IDEMPOTENCY_PURGE}` | — | — | — | TENANT | — | `JOB_QUEUED` |
| `JOB_READ` | — | — | TENANT | TENANT | — | — |
| `REPORT_VIEW` (VERIFY_TENANT·DESTROY_DRY_RUN·DESTROY 보고서) | — | — | TENANT | — | — | `REPORT_VIEW` |
| `EVENT_FEED_READ`·`EVENT_FEED_ACK` | — | — | — | — | TENANT | ACK만 `EVENT_FEED_ACK` |
| `SIGN_OPEN`·`SIGN_VIEW_RECORD`·`SIGN_VERIFY_IDENTITY`·`SIGN_CAPTURE`·`SIGN_STATUS` | SIGN_TOKEN 채널만(자기 세션) | | | | | 기존·`SIGN_SESSION_STATUS` 없음(읽기) |

- **파기 실행·앵커 실행은 사람 역할에 없다.** COMPLIANCE는 dry-run·파기 보고서를 **열람만** 한다.
- `VOID`의 MANAGER는 설계서 §1.3의 "무효 승인"을 따른다. `COMPLETE`(`SignService.complete` — 서명자 집합 충족·스캔 검토 종결을 확인하는 명시적 명령)는 마지막 서명자가 관리자인 경우가 많아 MANAGER(ORG)에도 연다. 판정은 유스케이스가 하므로 누가 눌러도 조건은 같다.
- 서비스 주체(SCHEDULER·FEED_CONSUMER)는 테넌트마다 클라이언트 자격(`sub` = 클라이언트 ID, 테넌트 클레임 = 테넌트)과 `identity_link` 행 하나를 가진다.
- 거부 감사 `AUTHZ_DENIED`
  - 인가 트랜잭션은 롤백되므로 별도 트랜잭션에 쓴다.
  - detail은 `{action, targetKind, targetId, reason}`이다. 대상 ID는 감사에 있고 응답에는 없다.
  - 테넌트 행이 있을 때만 쓴다. 내부 HTTP는 바인딩에서 테넌트 존재를 이미 확인했으므로 항상 남는다(§8).

### 3.4 강제 (`AuthorizationCoverageTest`, ArchUnit)
- 어노테이션 `@UseCaseEntry`(workflow, 순수 Java)
  1. `workflow..`의 **public 메서드 중 `TenantId`·`Caller`·원시 토큰을 받는 것**은 `@UseCaseEntry`이거나 `@NotAnEntry(reason)`이다. 닫힌 FQN 열거이고 폐기 항목은 실패한다.
  2. `@UseCaseEntry` 메서드는 본문(같은 메서드의 람다 포함 — `lambda$<name>$N` 합성 메서드까지 따라간다)에서 `AuthorizationPort.require`를 **직접** 호출한다.
  3. `disclosure-api`의 컨트롤러는 `workflow..`에서 `@UseCaseEntry` 메서드만 호출한다.
- 앱 경로(`app.cli..`)도 같은 진입점을 부른다. CLI는 `Caller(…, CLI)`를 만든다.
- 시그니처 변경(Q9): 진입점의 `(TenantId tenant, Actor actor, …)`를 `(Caller caller, …)`로 바꾼다. 테넌트는 `caller.tenant()`에서 오고, 감사 행위자는 `require`의 반환값이다. 기계적 리팩터이며, 테스트는 호출 형태만 바뀌고 단언은 그대로다.

---

## 4. 내부 REST와 OpenAPI 변경 (③)

### 4.1 경로 (`contracts/api/v1/disclosure-internal.openapi.yaml`, 전부 `/internal/v1`, Q15)

| 메서드·경로 | Action | 응답 |
|---|---|---|
| `POST /disclosures` | DISCLOSURE_CREATE | 201 영수증 `{disclosureId, status}` |
| `GET /disclosures?after&limit&status` | DISCLOSURE_READ | 200 `{items[DisclosureSummary], next}` (범위로 걸러진 목록) |
| `GET /disclosures/{id}` | DISCLOSURE_READ | 200 `DisclosureDetail`(상태·항목·스냅샷 요약·검증 결과·서명 현황·`asOf`·`ruleVersionId`, **고객 개인정보 없음** — `customerRef` 가명만) |
| `POST /disclosures/{id}/items`·`/compare`·`/grades`·`/recommendations`·`/validate`·`/seal`·`/void`·`/supersede`·`/rebase` | 각 Action | 200 영수증 또는 422 거부 목록 |
| `POST /disclosures/{id}/exception-approvals` | EXCEPTION_APPROVE | 200 영수증 |
| `GET /disclosures/{id}/artifacts/{kind}` | ARTIFACT_VIEW | 200 바이트 그대로(`application/pdf`·`application/zip`, 워터마크 없음) |
| `POST /disclosures/{id}/sign-sessions` | SIGN_SESSION_ISSUE | 201 `{sessionId, channel, expiresAt, deviceToken?}` — `deviceToken`은 TOUCH_PAD·PAPER_SCAN만(현장 기기), REMOTE_LINK는 통지 대기열 |
| `POST /sign-sessions/face-to-face`·`/sign-sessions/paper-scan` | FACE_TO_FACE_CONFIRM·PAPER_SCAN_UPLOAD | 토큰은 본문 |
| `POST /disclosures/{id}/agent-signature`·`/manager-confirmation`·`/paper-scan-review`·`/complete` | 각 Action | 200 영수증/422 |
| `POST /legal-holds`·`POST /legal-holds/{holdId}/release`·`GET /legal-holds?after&limit` | LEGAL_HOLD_* | 201/200 |
| `GET /disclosures/{id}/anchor-receipt` | RECEIPT_EXPORT | 200 `anchor-receipt-export` 스키마 바이트 그대로 |
| `POST /jobs/{kind}` | JOB_SUBMIT | **202** `Job` + `Location` |
| `GET /jobs/{jobId}`·`GET /jobs?kind&after&limit` | JOB_READ | 200 `Job` |
| `GET /jobs/{jobId}/report` | REPORT_VIEW | 200 보고서 바이트 그대로(verify-report·destruction-report 스키마) |
| `GET /events?after&limit`·`POST /events/ack?upTo=` | EVENT_FEED_* | §5 |
| (기존) `GET /disclosures/gate`·`POST /disclosures/{no}/policy-link` | — | **6B**. 계약에 `x-ga-phase: 6B`로 표시하고 구현하지 않는다 |

### 4.2 규약
- **인증 오류**: 토큰 없음·검증 실패·테넌트 클레임 없음·모르는 테넌트는 401이다. 본문은 `{code:"UNAUTHENTICATED", …}` 하나다. `WWW-Authenticate`는 `Bearer`만이고 사유 파라미터를 넣지 않는다.
- **오류 모델 `Problem {code, message, details}`**
  - `message`는 코드별 고정 영문 문장(설명용)이다. 입력값·자원 값을 넣지 않는다.
  - `details`는 닫힌 모양만 허용한다: `{rejections:[{code, ruleId?}]}`, `{field}`(형식 오류가 난 필드 이름).
  - 상태 결정은 HTTP가 아니라 **도메인이 붙인 범주**로 한다. `CommandRejectedException`·Outcome 거부에 `Category {CONFLICT, INVALID}`를 workflow가 붙인다. HTTP는 `CONFLICT→409`, `INVALID→422`, `AuthorizationDenied`·`NotFound→404`로 기계 변환만 한다.
  - `AuthorizationDenied`와 진짜 404는 같은 본문 `{code:"NOT_FOUND", message:"Resource not found.", details:{}}`이고 같은 헤더다.
  - 형식이 틀린 요청은 400 `MALFORMED_REQUEST`(`details.field`)다. 409에는 `ConcurrentWriteConflict`, `JOB_ALREADY_RUNNING`, `IDEMPOTENCY_IN_PROGRESS`가 해당한다.
- **멱등**: 쓰기 POST(내부)는 `Idempotency-Key` 필수이고 없으면 428 `IDEMPOTENCY_KEY_REQUIRED`다. 처리는 `api` 인터셉터 → workflow `IdempotencyService`(포트)다.
  1. 요청 해시 = SHA-256(JCS `{method, routeTemplate, pathVariables, body}`). 원문 본문은 저장하지 않는다.
  2. 인터셉터가 **청구**한다(별도 트랜잭션 INSERT).
     - 같은 키·같은 해시의 완료 행이 있으면 **재생**이다. 저장된 `response_ref`로 응답 바이트를 다시 만들고, 그 해시가 `response_hash`와 같음을 확인한 뒤 보낸다. 다르면 500 + 로그이고, 다른 본문을 내지 않는다(Q4).
     - 같은 키·다른 해시는 422 `IDEMPOTENCY_KEY_REUSED`이다.
     - 진행 중 행은 `claimed_at + api.idempotencyLeaseSeconds` 전이면 409 `IDEMPOTENCY_IN_PROGRESS`이고, 지났으면 인수한다(`claim_seq +1`). 인수 뒤 유스케이스가 다시 돌아도 업무 상태 가드가 이중 효과를 막는다(봉인·서명·전이는 상태 거부).
  3. 유스케이스를 실행한다.
  4. 완료를 기록한다(별도 트랜잭션). 2xx·409·422를 저장하고, 5xx·401·404·428은 저장하지 않는다. 404를 저장하지 않는 것은 존재 누설 방지를 위해서다 — 키 재사용으로 "예전엔 404였다"를 알 수 없게.
  - TTL은 `api.idempotencyTtlHours`이고, 만료 행 삭제는 작업 `IDEMPOTENCY_PURGE`다.
- **영수증**: 쓰기 응답은 닫힌 영수증 스키마다(ID·번호·상태·해시·코드만, 자유 텍스트 0). `response_ref`가 곧 그 튜플이다.
- **커서**
  - 형식은 `base64url(JCS{v:1, s:stream, q:seq}) "." base64url(HMAC-SHA256(key, tenant ‖ 0x00 ‖ 앞부분)[0..16])`이다. 테넌트를 MAC에 묶어 다른 테넌트의 커서는 400이다.
  - 키는 `ga.api.cursor-key-file`(저장소 밖, 권한 600이 아니면 기동 실패, 개발·데모는 첫 기동에 생성 — 로컬 KEK와 같은 규약)이다. HMAC은 `infra.crypto`의 `CursorCodec`(javax.crypto 허용 패키지)이다.
  - `limit`는 1~100이고 기본 50이다. 피드는 1~1000(기존 계약)이다.
- **스키마 형식은 `pattern`**: 내부·공개 계약의 `format:` 0(기존 3곳 교체). G11의 `format` 0 범위는 이 시스템이 제공하는 두 계약이다. 엔진 계약은 엔진 저장소와 공유하는 소비 계약이라 E4에서 다룬다(Q15).
- **계약 ↔ 라우트 양방향**: `OpenApiContractIT`
  - `RequestMappingHandlerMapping`의 `/internal/**`·`/public/**` 라우트 집합과 계약 경로(6B 표시 제외)가 같음을 단언한다.
  - 모든 IT 응답을 해당 상태의 스키마로 검증한다(응답 캡처 필터, IT 전용).
  - 계약의 요청 예시를 요청 스키마로 검증한다.

### 4.3 OpenAPI diff 요약(보고서 ④에 실제 diff를 둔다)
- 추가: 위 경로 전부, `components.schemas`의 `DisclosureSummary`·`DisclosureDetail`·영수증 9종·`Job`·`JobKind`·`LegalHold`·`Cursor`·`Problem.details` 닫힌 모양, 공통 헤더 `Idempotency-Key`.
- 보안 스킴: 기존 `serviceToken`(bearer)을 `bearerJwt` 하나로 바꾼다. 사용자·서비스 주체 모두 JWT이고 역할은 `identity_link`에서 온다.
- 변경: 피드 파라미터 `afterSeq`→`after`, `nextSeq`→`next`(Q5). gate의 `asOf`·`completedAt`, policy-link의 `contractDate`에서 `format`을 `pattern`으로 바꾼다.
- 신설: `contracts/api/v1/disclosure-public.openapi.yaml`(§5).
- `CHECKSUMS` 갱신.

---

## 5. 고객 공개 서명 경로 (④)

### 5.1 보안 체인
- `SecurityFilterChain` 둘
  - `@Order(1) securityMatcher("/public/**")`: 인증 없음, `STATELESS`, `requestCache`·`securityContext` 저장 끔, CSRF 끔(쿠키가 없다), 익명 끔.
  - `@Order(2) securityMatcher("/internal/**")`: OAuth2 리소스 서버(JWT).
  - 그 밖은 `/actuator/health`만 허용하고 나머지는 `denyAll`이다.
- 의존성(BOM 관리): `disclosure-api`에 `spring-boot-starter-security`·`spring-boot-starter-oauth2-resource-server`·`spring-boot-starter-webmvc`를 둔다. 카탈로그에 스타터 두 줄을 버전 없이 추가하고, `platform-spring`의 `compileOnly` 골격 설정은 지운다. 6A가 `api.security`에 실제 체인을 두므로 플랫폼의 `@Profile("oidc")` 골격은 쓰이지 않는다. 폐기를 보고한다.
- JWT 변환기는 **권한을 하나도 만들지 않는다**(`JwtGrantedAuthoritiesConverter` 대신 빈 컬렉션). 그래서 `scope`·`roles` 클레임이 권한이 될 길이 없다.
- 공개 체인의 필터 `PublicSignGate`는 `HeaderWriterFilter` 뒤, 디스패처 앞에 하나로 둔다. 순서:
  1. 시작 시각 기록(주입 Clock).
  2. **거부 판정 전처리**
     - 쿼리 문자열이 하나라도 있으면 거부.
     - 경로가 알려진 5개 경로가 아니면 거부(토큰을 경로 세그먼트로 보낸 경우 포함).
     - 메서드가 POST가 아니면 거부.
     - 토큰(헤더 `X-Sign-Token` 우선, 없으면 JSON 본문 `token`)이 없거나 형식이 틀리면 거부.
  3. 토큰 접두 테넌트의 분당 한도 초과면 거부(§5.4).
  4. 핸들러 실행(Phase 4 유스케이스). `SignTokenRejected`와 **그 밖의 모든 예외**는 거부로 바꾼다. 예외는 토큰 없이 코드·클래스만 로그에 남긴다.
  5. 응답을 버퍼링한다(`ContentCachingResponseWrapper`, PDF 포함).
  6. **패딩**: `ResponsePadding.pad(start)`가 `max(0, floor − elapsed)`만큼 `Sleeper`로 기다린다. 성공·업무 거부·토큰 거부·예외 모두 같은 지점을 지난다.
  7. 버퍼를 내보낸다.
- 엔드포인트(전부 POST, `contracts/api/v1/disclosure-public.openapi.yaml`)

| 경로 | 유스케이스 | 성공 |
|---|---|---|
| `/public/v1/sign/open` | `SignSessionService.open` | 200 `application/pdf`(감사 `ARTIFACT_VIEW` 사유 `SIGN`) |
| `/public/v1/sign/view` | `recordView` (Q14 — 지시문 목록에 없는 5번째) | 200 `{sessionStatus}` |
| `/public/v1/sign/verify-identity` | `verify` | 200 `{passed, missing[]}` — 남은 시도 수는 내지 않는다 |
| `/public/v1/sign/capture` | `SignService.capture` | 200 영수증, 업무 거부(서명 순서·해시 변경 등)는 422 |
| `/public/v1/sign/status` | **신규** `SignSessionService.status(rawToken)` — 읽기 전용, 열린 세션만 | 200 `{sessionStatus:"OPEN", identityRequired[], identityPassed[], viewed, expiresAt}` |

- 업무 거부 422는 **유효 토큰 보유자에게만** 나간다. 토큰을 가진 사람은 이미 그 세션을 안다. 토큰 거부와는 다른 응답이고 이는 의도다(지시문의 동일 응답 대상은 거부 사유 7종).

### 5.2 동일 거부 (G5)
- 상태 **404**, 본문 `{"code":"SIGN_LINK_UNAVAILABLE","message":"This signing link cannot be used.","details":{}}`를 상수 바이트로 둔다(Q16).
- 헤더: Spring Security 기본 헤더 집합 + `Cache-Control: no-store` + `Referrer-Policy: no-referrer` + `Content-Type`·`Content-Length`.
  - 모든 공개 응답이 같은 체인을 지나므로 헤더 이름 집합이 같다.
  - 값 비교에서 `Date`만 뺀다(서버가 시각을 쓴다).
  - `Set-Cookie`는 0이다.
- **사유 7종과 추가 2종을 같은 바이트로 단언한다.**
  - 7종: ① 형식이 틀린 토큰, ② 없는 테넌트 접두, ③ 틀린 비밀, ④ 만료, ⑤ 취소(무효·정정·재발급), ⑥ 사용됨, ⑦ 본인확인 실패 한도 도달.
  - 추가: 토큰을 쿼리로, 토큰을 경로로.
  - G7의 한도 초과도 같은 바이트다.
- 토큰 비교는 Phase 4 그대로다(SHA-256 해시로 조회, `MessageDigest.isEqual`).
- 거부 감사는 Phase 4 D3 그대로 **테넌트 행이 있을 때만** `SIGN_SESSION_DENIED`이다. 토큰 원문·해시를 감사에 넣지 않고 사유 코드만 남긴다.

### 5.3 패딩 하한의 출처 (Q6)
- `publicSign.minResponseMillis`는 GLOBAL 전용이지만 테넌트마다 복제되어 있다. 없는 테넌트 요청에는 읽을 룰이 없다.
- 권장안: 프로세스 전역 스냅샷 `PublicSignPolicy`
  - 기동 시와 주기마다(배포 설정 `ga.public-sign.policy-refresh`, 기본 PT1M) 테넌트 목록(`TenantDirectoryReader`, 운영자 롤)의 각 테넌트를 바인딩해 오늘(KST) 유효 룰을 읽는다.
  - **하한 = 전 테넌트 값의 최댓값**이다. 모든 공개 응답(있는 테넌트·없는 테넌트·성공)이 같은 하한을 쓰므로 하한 자체가 존재를 누설하지 않는다.
  - 테넌트 분당 한도는 그 테넌트의 값이다(없는 테넌트는 어차피 거부).
  - 첫 적재 실패는 기동 실패다. 테넌트 0개면 공개 경로는 전부 거부(패딩 0)이다.
- 결정론 테스트: `ResponsePadding`·`Sleeper`·Clock을 주입한다. IT는 기록형 `Sleeper`로 **모든 응답에서 훅이 1회 호출되고 요청된 대기 = 하한 − 경과**임을 단언한다. 통계 테스트는 하지 않는다.

### 5.4 한도
- 테넌트·토큰 해시 단위 시도 한도는 Phase 4 `identityCheck.maxFailures` 그대로다.
- 테넌트 분당 한도 `publicSign.tenantRatePerMinute`
  - 고정 1분 창(주입 Clock의 분), 인스턴스 메모리 카운터, **알려진 테넌트만 키**로 둔다. 없는 테넌트 접두로 맵을 키울 수 없다.
  - 성공 포함 모든 요청을 센다. 초과도 같은 거부 바이트다.
  - 다중 인스턴스에서 한도는 인스턴스별이다. IP 한도와 함께 Phase 8 인그레스 문서의 요구사항에 적는다.

### 5.5 링크 규약과 로그
- 고객 링크는 `https://{host}/s#{token}`이다. 프래그먼트는 서버·액세스 로그에 가지 않는다.
  - `ga.sign.link-base-url` 기본값을 `…/s#`로 바꾼다.
  - 설계서 §9에 "Phase 7 화면은 프래그먼트에서 토큰을 꺼내 본문(`X-Sign-Token`)으로 보낸다"를 둔다.
- 액세스 로그는 자체 `AccessLogFilter`(로거 `ga.access`)이다. 톰캣 밸브는 쓰지 않는다.
  - 남기는 것: 메서드, **라우트 템플릿**, 상태, 소요.
  - 원 URI·쿼리 문자열·헤더는 남기지 않는다. 공개 경로는 템플릿만이고, 매칭 실패는 `/public/**`로 기록한다.
- `spring.mvc.log-request-details=false`(기본)를 고정한다. `server.error.include-*`는 never로 두고 화이트라벨 오류는 끈다.

---

## 6. 작업 잠금과 상태 전이 (⑤)

### 6.1 잠금 키
- `pg_try_advisory_lock(hashtextextended('ga.job|' || tenant_id || '|' || kind, 0))`이다. 세션 잠금이고, `JobLockGateway`의 전용 롤 커넥션이 작업이 끝날 때까지 쥔다.
- 네임스페이스 문자열 `ga.job|`은 기존 트랜잭션 잠금(`audit_log:`·`ga.outbox|`)과 같은 키 공간에서 겹치지 않게 한다. 해시가 충돌해도 결과는 **거짓 409**뿐이다(안전 쪽).
- 같은 테넌트의 다른 종류, 다른 테넌트의 같은 종류는 병행한다. 다만 **DESTROY와 DESTROY_DRY_RUN은 같은 키(`DESTROY`)**를 쓴다. 판정 중 파기가 겹치면 dry-run 보고서가 거짓이 되기 때문이다.

### 6.2 `JobRunner` (workflow, CLI와 HTTP 공용)

`submit(caller, kind, params)`:
1. `authz.require(caller, JOB_SUBMIT:kind, JobKind)`.
2. 잠금을 잡는다. 실패면 409 `JOB_ALREADY_RUNNING`이고 행을 만들지 않는다.
3. 같은 테넌트·종류의 QUEUED·RUNNING 행이 있으면 잠금을 잡은 것이 곧 그 프로세스가 죽었다는 뜻이다. 그 행을 `FAILED(INTERRUPTED)`로 닫고 감사 `JOB_INTERRUPTED`를 남긴다.
4. QUEUED 행 INSERT, 감사 `JOB_QUEUED`.
5. HTTP는 202를 돌려주고 실행기(가상 스레드)에 넘긴다. CLI는 같은 실행을 동기로 기다린다.
6. 실행
   - RUNNING 전이.
   - `TenantContext` 재바인딩(ScopedValue — 실행기 스레드에서 다시 묶는다).
   - Phase 4·5 유스케이스를 그대로 호출한다: `ExpireService.run`, `ArtifactService.reconcile`, `AnchorJob.run([tenant])`(Q7), `DestructionJob.run`, `TenantVerifier.run`, `NotificationDispatcher.run`, `IdempotencyPurge.run`.
   - 보고서를 암호화해 `reports/`에 저장한다.
   - SUCCEEDED(`result_ref`·`report_sha256`) 또는 FAILED(`error_code`), 감사 `JOB_FINISHED`.
7. 잠금을 풀고 커넥션을 반납한다(`finally`). 커넥션이 끊기면 DB가 세션 잠금을 자동으로 푼다. 그래서 프로세스가 죽은 뒤 고아는 3의 경로로만 정리된다.

- CLI 기존 명령(`anchor run`·`retention destroy`·`verify tenant`·`disclosure expire`·`artifacts reconcile`)도 `JobRunner`를 지난다. 출력은 기존 줄을 그대로 두고 `JOB <id> <status>` 줄을 더한다(기존 테스트의 `contains` 단언은 유지).
- `--tenants all`은 테넌트마다 작업 1건이고, 잠금은 테넌트 ID 정렬 순서로 하나씩 잡는다. 잠긴 테넌트는 그 테넌트만 실패한다.
- 앵커는 테넌트들을 한 트리로 묶어야 하므로, CLI `anchor run --tenants all`은 잡은 잠금들 아래에서 `AnchorJob.run(테넌트 목록)`을 한 번 부른다.

### 6.3 상태 전이표 (기계 판독 블록 `job-states` — 설계서 §6에 두고 `JobStateTableTest`가 V12 가드를 양방향 대조. 보고서 ③에 그대로)

```job-states
from,to,trigger,sets
-,QUEUED,submit (잠금 획득 후),requested_at
QUEUED,RUNNING,실행기 시작,started_at
RUNNING,SUCCEEDED,유스케이스 정상 종료 + 보고서 저장,finished_at·result_ref·report_sha256·report_key_wrapped·report_kek_id
RUNNING,FAILED,유스케이스 예외·보고서 저장 실패·INTERRUPTED,finished_at·error_code
QUEUED,FAILED,실행 전 고아(INTERRUPTED)·실행기 거부(REJECTED),finished_at·error_code
```
- `QUEUED→FAILED`는 지시문 표(`QUEUED→RUNNING→{…}`)에 없다(Q11). 202를 받은 뒤 실행기가 시작하기 전에 프로세스가 죽은 작업을 닫는 데 필요하다.
- 종단 두 상태(SUCCEEDED·FAILED)에서 나가는 전이는 없다(GD121).

---

## 7. 통지 아웃박스 재시도 산식 (⑥)

### 7.1 적재 (세션 생성 트랜잭션 안)
- `SignSessionService.issue`(REMOTE_LINK)
  - `token_hash = NULL`로 세션을 INSERT한다.
  - `notification_outbox`에 `kind=SIGN_LINK`, `recipient_ref=customerRef`, `payload_ref=sessionId`, `next_attempt_at=now`, `status=PENDING`으로 INSERT한다.
  - 감사 `SIGN_SESSION_ISSUE`.
  - 이 셋은 **한 트랜잭션**이다. 롤백되면 0행이다(G10).
- 지금의 "커밋 뒤 직접 발송"(`send`)은 지운다.

### 7.2 디스패처 (`NotificationDispatcher.run(caller, asOf, limit)`, 작업 종류 `NOTIFY`)
- 후보: `status=PENDING ∧ next_attempt_at ≤ now`, `FOR UPDATE SKIP LOCKED`, `limit`.
- 행마다 **한 트랜잭션**:
  1. 세션을 잠근다.
     - 세션이 OPEN이 아니거나, 만료가 지났거나, 이미 보냈으면 → `CANCELLED`(`last_error_code` = `SESSION_CLOSED`·`SESSION_EXPIRED`), 끝.
  2. `CustomerRefService.phoneForNotification(…, REMOTE_LINK, sessionId)`로 번호를 읽는다(감사 `CUSTOMER_PHONE_READ`).
     - 번호가 없으면 → `DEAD(NO_PHONE)` + 플래그. 재시도해도 생기지 않으므로 즉시 소진이다.
  3. 토큰을 생성한다. 원문은 이 지역 변수와 어댑터 인자에만 존재한다.
  4. 세션에 `token_hash`·`sent_at`을 기록한다(GD123 경로).
  5. `NotifyPort.sendSignLink(phone, link)`를 부른다. 링크는 `base + "#" + token`이다.
  6. `SENT`, `sent_at`, 감사 `SIGN_SESSION_SEND sent=true`.
- 어댑터 예외(또는 결과 실패)면 그 트랜잭션은 **롤백**이다(토큰 해시 기록도 사라진다). 이어 별도 트랜잭션에서:
  - `attempts := attempts + 1`
  - `last_error_code` = 어댑터가 낸 닫힌 코드(`NotifyFailure.code`, 자유 텍스트 금지).
  - `attempts ≥ notify.retry.maxAttempts`이면 `DEAD`, 플래그 `NOTIFY_FAILED`(HIGH, 대상 `SIGN_SESSION`/sessionId — `compliance_flag.type`은 CHECK가 없어 마이그레이션 불요), 감사 `NOTIFY_DEAD`.
  - 그 밖이면 `next_attempt_at := now + delay(attempts)`, 감사 `NOTIFY_RETRY`.
- **산식**(정수 초, 지터 없음 — 결정론):
  ```
  delay(n) = min(maxDelaySeconds, initialDelaySeconds × multiplier^(n−1))     n = 실패 후 attempts (1부터)
  ```
  - 예시값 `{5, 60, 2, 3600}`이면 60·120·240·480초 후 재시도하고, 5번째 실패에서 DEAD이다.
  - 곱셈은 `Math.multiplyExact`로 하고, 넘치면 `maxDelaySeconds`이다.
  - 룰은 **실행 시점(오늘 KST)의 ACTIVE 룰**로 읽는다(파기 절차 파라미터와 같은 규약). 적용한 룰 버전을 감사 detail에 적는다.
- 어댑터 성공 뒤 커밋이 실패하면, 고객은 해시가 저장되지 않은 링크를 받는다. 그 링크는 거부되고, 다음 시도가 새 토큰으로 다시 보낸다. 최소 1회 전달이고, 죽은 링크는 거부 응답일 뿐 오용 경로가 아니다.
- `NotifyPort` 시그니처는 `sendSignLink(Sensitive<PhoneNumber>, SignLink)`이다. `SignLink`는 `toString`을 가린다. 콘솔 어댑터는 유지하고, 실 사업자 어댑터는 인터페이스 문서만 둔다(§14 운영 결정).

---

## 8. 토큰 → 테넌트 바인딩 순서 (⑦)

```
[내부] BearerTokenAuthenticationFilter(JWT 서명·만료·발급자·대상 검증 — 실패 401)
   → TenantBindingFilter
        ① 클레임 sub·tenant_id만 읽는다(다른 클레임 접근은 이 클래스뿐 — ArchUnit: `Jwt` 사용 클래스 = {TenantBindingFilter})
        ② TenantId 형식 검사(실패 401)
        ③ ScopedValue로 TenantContext 바인딩
        ④ 바인딩된 상태에서 tenant 행 존재 확인(RLS 아래 SELECT — 없으면 401)
        ⑤ Caller(tenant, sub, HTTP)를 요청 속성에 두고 chain 계속(바인딩 범위 안)
   → (인터셉터) Idempotency 청구 — 바인딩 안
   → 컨트롤러: DTO 변환 → @UseCaseEntry 호출
   → 유스케이스: 트랜잭션 시작(TenantSessionBinder가 set_config) → authz.require(identity_link 읽기 = RLS 아래) → 업무
[공개] PublicSignGate → 유스케이스가 토큰 접두로 바인딩(Phase 4 그대로) → require(SIGN_TOKEN) → 업무
```
- **RLS 바인딩이 인가보다 먼저다.** 인가가 읽는 `identity_link`·대상 확인서가 RLS 아래에 있으므로, 바인딩 없이 인가할 방법이 없다. 그래서 다른 테넌트 자원은 인가 이전에 "없음"이 되고, 같은 404로 수렴한다.
- `TenantBindingOrderIT`
  - 토큰 테넌트 ≠ 자원 테넌트 → 404이고, 바이트가 없는 자원과 같다.
  - 주입 테스트: 바인딩 전에 조회를 하는 필터를 테스트 구성으로 끼우면 `TenantNotBoundException`(→ 500)으로 실패한다. "바인딩 전 데이터 접근은 불가능"을 단언한다.
- 테넌트 클레임 이름은 `tenant_id`로 고정한다. 포털과 같은 IdP 규약이 필요하며, 포털 문서에 맞출 사항으로 보고한다.

---

## 9. 모듈 배치와 규칙 테스트

### 9.1 배치 (레이어 규칙을 넓히지 않는다)

| 모듈 | 추가 |
|---|---|
| `disclosure-workflow` | `authz`(Caller·Action·Target·Principal·AuthorizationPort·AuthorizationDenied·ScopePolicy·@UseCaseEntry·@NotAnEntry), `jobs`(JobRunner·JobKind·JobStore·JobLockPort·ReportStore), `notify`(NotificationDispatcher·NotificationOutboxStore·NotifyPort 이동), `idempotency`(IdempotencyService·IdempotencyStore·영수증 렌더 포트), `feed`(EventFeed — 읽기·ack), `SignSessionService.status` |
| `disclosure-api` | `api.security`(체인 둘·TenantBindingFilter·PublicSignGate·ResponsePadding·Sleeper·PublicSignPolicy·RateWindow·AccessLogFilter), `api.internal`·`api.publicsign`(컨트롤러), `api.dto`(record), `api.mapper`(기존 허용 패키지), `api.error`(advice 둘 — 내부·공개), `api.idempotency`(인터셉터) |
| `disclosure-infra` | 새 세 테이블 저장소, `jobs.JobLockGateway`(허용 목록), `crypto.CursorCodec`·`crypto.ReportCipher`, `storage` 보고서 키, `persistence.AuthzFactsRepository`(대상 확인서의 `agent_id`·`org_path`, 보류·작업 존재), `IdentityLinkRepository` 확장 |
| `disclosure-app` | 설정 배선, `authz.IdentityLinkAuthorization`(포트 어댑터), `demo.DemoOidc`(데모 프로파일 전용 발급기·공개키), CLI `jobs list/show`·`notify dispatch`·`demo token` |

### 9.2 ArchUnit (`disclosure-app/src/archTest`)
- `AuthorizationCoverageTest` — §3.4의 세 규칙.
- `ApiLayerRulesTest`
  - (a) `@RestController`는 `api.internal..`·`api.publicsign..`에만 둔다.
  - (b) 컨트롤러가 접근할 수 있는 것: `@UseCaseEntry` 메서드, `api.dto..`, `api.mapper..`, `java..`, `org.springframework.web..`·`http..`. 저장소(`TenantScopedRepository` 하위)·`infra..`·`rules..`·`domain` 서비스·`audit..`·`seal..`·`sign..`의 **동작 클래스**는 금지한다. 값 타입(`domain.vo`·식별자)은 매퍼에서만 허용한다.
  - (c) 컨트롤러에 `if`/`switch`가 있는지를 바이트코드로 잡지는 않는다(ArchUnit 한계). 대신 컨트롤러 메서드가 호출하는 `workflow` 메서드가 정확히 1개임을 단언하고, 리뷰 규칙으로 보완한다.
  - (d) `Jwt` 접근은 `TenantBindingFilter`뿐이다.
  - (e) `HttpServletRequest#getQueryString`·`getRequestURI`·`getHeader` 호출은 `api.security..`뿐이다(로그·컨트롤러가 원 URI를 다루지 않게).
- 직접 DB 접근 허용 목록에 `JobLockGateway`를 추가한다. 폐기 항목 검사는 기존 그대로다.
- `DisclosureWriteScanTest` 허용 목록: `sign_session`의 발송 시 해시 기록(디스패처 메서드), `outbox_event`의 `published_at`(피드 ack 메서드)을 `FQN#method`로 추가한다.
- `TenantPredicateScanTest`: 테이블 수 30 → 33으로 바꾼다. 새 SQL은 전부 `tenant_id` 조건이다. advisory lock SQL은 테이블이 없어 대상 밖이다.

### 9.3 평문·토큰 누출 (G6)
- `PlaintextLeakScanIT` 확장(앱 IT로 새로 둔다 — `disclosure-app/src/integrationTest`): 공개 경로 전 흐름을 실행한 뒤 다음을 센티널 14종 + **실행 중 생성한 토큰 원문(동적 센티널)**으로 스캔한다.
  - 루트 TRACE `ListAppender`
  - `ga.access` 로거
  - 표준 출력·오류
  - 공개 응답 본문 전부(PDF 제외 — PDF는 성명을 담는 봉인 산출물이다)
  - 내부 응답 본문 전부
  - 감사·아웃박스·`notification_outbox` 덤프
- 기존 CLI IT(`OperatorCliIT`·`Phase5CliIT`)의 버퍼에도 `PiiSentinels.findIn` 단언을 더한다. 사각 보완이다(머리말 10).

### 9.4 `DisclosureApplicationIT.noApplicationControllersExist`
"Phase 6 전까지 컨트롤러 없음"이라는 자리표시 단언이다. `ApiLayerRulesTest`(a)로 대체한다. 지우는 이유를 커밋 본문과 보고서에 적는다.

---

## 10. 데모 (지시문 §8)

- **데모 OIDC**(`@Profile("demo")`만)
  - 서명 키 `~/.ga-disclosure/demo-oidc.p12`(저장소 밖, 첫 사용에 생성 — TSA 스텁 키와 같은 규약).
  - 공개키 PEM은 `build/demo/demo-oidc.pem`으로 내보낸다.
  - 리소스 서버는 `spring.security.oauth2.resourceserver.jwt.public-key-location`으로 이 PEM을 읽고, 발급자 `ga-demo`·대상 `ga-disclosure`를 검증한다.
  - 운영 프로파일은 `issuer-uri`이다. `DemoKeysGuard`가 데모가 아닌 프로파일의 `ga.demo.*` 키를 거부하는 것은 그대로다.
  - CLI `demo token --tenant T --subject S [--ttl PT15M]`이 JWT를 표준 출력으로 낸다. 클레임은 `sub`·`tenant_id`·`iss`·`aud`·`exp`뿐이다. 역할 클레임은 없다.
- 시드: DEMO1·DEMO2에 `demo-compliance`(COMPLIANCE, 조직 없음), `demo-compliance-2`(4-eyes 해제용), `demo-scheduler`(SCHEDULER), `demo-feed`(FEED_CONSUMER)를 추가한다. `phase6a-seed.json`, 개인정보 없음.
- `disclosure-demo/scripts/http-demo.sh` (bash + curl + jq — jq가 없으면 시작 전에 실패):
  1. 앱을 데모 프로파일 웹으로 띄운다(`bootRun` 백그라운드, 헬스 대기).
  2. `demo token`으로 AGENT·MANAGER·COMPLIANCE·SCHEDULER·FEED 토큰을 받는다.
  3. AGENT: 초안 → 항목 → 비교 → 등급 → 추천사유 → 검증 → 봉인. 고객은 이미 등록된 데모 고객의 `customerRef`(가명)를 쓴다. 쓰기마다 `Idempotency-Key`를 붙인다.
  4. AGENT: REMOTE_LINK 세션을 발급하고, SCHEDULER가 `jobs/NOTIFY`를 실행한다. 콘솔 어댑터가 출력한 링크에서 **`#` 뒤 토큰**을 꺼낸다.
  5. 공개: `open`(PDF 저장) → `view` → `verify-identity`(생년월일은 **파일**에서 읽어 본문으로 — 규칙 6) → `capture`. 서명 이미지는 데모 파일이다.
  6. AGENT 서명 → MANAGER 확인 → COMPLETED.
  7. FEED: 이벤트를 커서로 2회 읽고 ack한다. ack 전 재요청이 같은 이벤트를 다시 주는지 출력한다.
  8. COMPLIANCE: `jobs/VERIFY_TENANT` 202 → `SUCCEEDED`까지 폴링하고 보고서를 내려받는다.
  9. 거부 3종(틀린 토큰·쿼리 토큰·사용된 토큰)의 응답(상태·헤더 − Date·본문)을 파일로 받아 `diff`가 0인지 확인한다.
  10. 2회 실행: 같은 `Idempotency-Key`로 재생하므로 부작용이 0이다(NOOP). 재생 여부를 출력한다.
- 데모 2회는 Phase 5처럼 **격리 컨테이너**에서 돌린다. 사용자 compose 볼륨은 건드리지 않는다. 새 롤(`disclosure_job_lock`) 때문에 기존 볼륨은 `init-roles.sql` 재실행이 필요하다. `down -v`는 사용자 결정이며 README에 적는다.

---

## 11. 완료 기준 ↔ 테스트

| # | 테스트 | 핵심 단언 |
|---|---|---|
| G1 | `AuthorizationCoverageTest`(arch), `AuthzFromIdentityLinkIT` | 진입점 전수가 `require` 직접 호출. `roles:["COMPLIANCE"]`·`scope`·`org_path` 클레임을 넣은 AGENT 토큰이 AGENT로만 동작(타인 확인서 404, 작업 404). `identity_link` 역할을 바꾸면 다음 요청에 즉시 반영 |
| G2 | `AuthzScopeIT` | AGENT 타인·MANAGER 다른 조직(`/HQX` 대 `/HQ` 포함)·COMPLIANCE 다른 테넌트 → 404. 존재하는 자원 거부와 없는 ID의 상태·헤더(− Date)·본문 바이트 동일. `AUTHZ_DENIED` 감사 1행, 응답에 대상 ID 없음 |
| G3 | `TenantBindingOrderIT` | §8 |
| G4 | `IdempotencyIT` | 같은 키·요청 → 같은 바이트, 감사·아웃박스·상태 변화 1회. 같은 키·다른 본문 → 422. 키 없음 → 428. 진행 중 → 409, 임차 경과 뒤 인수. TTL을 룰 데이터로 바꾸면 `expires_at` 변함(코드 diff 0). 404는 저장 안 됨 |
| G5 | `PublicSignUniformResponseIT` | §5.2의 9가지 바이트 동일, 모든 응답(성공 포함)에서 패딩 훅 1회·대기 = 하한 − 경과, `Set-Cookie` 0 |
| G6 | `PublicPlaintextLeakScanIT`(앱) + CLI IT 버퍼 단언 | §9.3 |
| G7 | `PublicSignRateLimitIT` | 한도 N(룰 데이터) 초과 N+1번째가 거부 바이트, 다음 분 창에 회복, 다른 테넌트 무영향, 룰 값 변경 → 한도 변경 |
| G8 | `EventFeedIT` | seq 오름차순, ack 전 재요청 재전달, ack 뒤 기본 시작점 이동, 모든 항목 envelope 스키마 통과, 다른 테넌트 이벤트 0, `DisclosureDestroyed` 포함, 다른 테넌트 커서 400 |
| G9 | `JobRunnerIT` | 같은 테넌트·종류 동시 2건 → 둘째 409(행 미생성). 다른 테넌트 병행. CLI가 쥔 잠금에 HTTP가 409. 프로세스 사망 모사(잠금 커넥션 강제 종료) 뒤 다음 제출이 `FAILED(INTERRUPTED)`로 닫고 새로 연다. GD121 전이 전수(허용 5·불허 나머지). `ux_async_job_active` 벨트. DESTROY와 DRY_RUN 상호 배제 |
| G10 | `NotificationOutboxIT` | 세션 생성 롤백 → 세션·아웃박스 0행. 백오프 산식과 룰 데이터 변경. 소진 → DEAD + `NOTIFY_FAILED` 1건. 토큰 원문이 아웃박스·세션·감사·로그 0. 발송 실패 롤백 시 `token_hash` NULL 유지. GD123 |
| G11 | `ApiLayerRulesTest`(arch), `OpenApiContractIT` | §9.2, 라우트 ↔ 계약 양방향, 전 응답 스키마 통과, 두 계약의 `format` 0 |
| G12 | `AnchorGuardIT`(CHECK 거부 추가), `AnchorJobTest`·`Phase5CliIT`(`DATE_NOT_TODAY`), `VerifyTenantIT`(`ANCHOR_MISSING_DAY` 중간·끝 공백, 오늘 미포함, `CHAIN_BROKEN` 없음), `DemoShortBundleTest`(차집합), R3 두 테스트 인용, `LegalHoldIT`(해제 사유 목록·4-eyes 유스케이스·DB CHECK 23514) | |
| G13 | 전체 check(평문·jqwik·체크섬·B3 스캔), `TZ=UTC` 전체 check 1회 | 아래 주입 |

**위반 주입**(각각 의도한 테스트의 실패를 확인하고 제거한 뒤 커밋 본문·보고서에 기록)
- 지시문 최소 9종
  1. 진입점 하나에서 `require` 호출 제거 → `AuthorizationCoverageTest`
  2. `TenantBindingFilter`에서 `roles` 클레임을 읽어 Principal에 반영 → `AuthzFromIdentityLinkIT`(+ arch d)
  3. `AuthorizationDenied` → 403 → `AuthzScopeIT`
  4. 거부 사유별 본문 코드 → `PublicSignUniformResponseIT`
  5. 패딩 제거 → 같은 IT(훅 단언)
  6. 쿼리 토큰 허용 → 같은 IT
  7. 피드 SQL에서 테넌트 조건 제거 → `TenantPredicateScanTest` + `EventFeedIT`(RLS가 막으면 스캔만 실패함을 기록)
  8. 작업 잠금 획득 생략 → `JobRunnerIT`(벨트 인덱스가 잡는지 기록)
  9. 아웃박스 적재를 세션 트랜잭션 밖으로 → `NotificationOutboxIT`
- 추가 주입
  - 4-eyes CHECK 제거(유스케이스만) → `LegalHoldIT`의 DB 직접 UPDATE 단언
  - R1 CHECK 제거 → `AnchorGuardIT`
  - `ANCHOR_MISSING_DAY`를 `CHAIN_BROKEN` 대상으로 → `VerifyTenantIT`
  - 데모 번들 보존 외 키 변경 → `DemoShortBundleTest`
  - 액세스 로그에 원 URI 기록 → G6
  - 멱등 404 저장 → `IdempotencyIT`
  - 다른 테넌트 커서 수용(MAC에서 테넌트 제외) → `EventFeedIT`
  - 디스패처가 토큰을 아웃박스 컬럼(테스트 전용 확장)에 기록 → G10 덤프 스캔
  - MANAGER 접두를 문자열 접두로 → `AuthzScopeIT`(`/HQX`)

---

## 12. 순서 (승인 후)

| 단계 | 내용 | 설계서 |
|---|---|---|
| 2 | V12 전체 + R1(AnchorJob·verify·데모 두 날) + R2 + `legalHoldReleaseReasons` + 6A 룰 키·번들 재해시 + `db-error-codes` + `pii-columns` 3행 | §5·§6.7·§9·§14 #15·부록 B·D, v1.12 이력 |
| 3 | 인가: `authz` 타입·포트·`ScopePolicy`·어댑터, 진입점 시그니처 리팩터(`Caller`), `AuthorizationCoverageTest`, `authz-matrix` 블록·대조 테스트, `org_path` 기록 | §3.3·§7·§9 |
| 4 | 작업: `JobRunner`·`JobLockGateway`(롤·허용 목록)·보고서 저장·`job-states` 블록, CLI 경유 전환, `jobs list/show` | §6·§9 |
| 5 | 통지 아웃박스: 발송 시 토큰, 디스패처, 백오프, `NOTIFY_FAILED`, `notify dispatch`, 링크 규약 `/s#` | §6.5·§9 |
| 6 | 내부 REST: 보안 체인·바인딩 필터·오류 모델·멱등·커서·컨트롤러·내부 계약·`OpenApiContractIT`·`ApiLayerRulesTest` | §4·§7·§9 |
| 7 | 공개 서명: 체인·`PublicSignGate`·패딩·한도·`status`·공개 계약·G5~G7 | §7·§9 |
| 8 | 이벤트 피드·ack·`docs/event-feed.md`·푸시 어댑터 인터페이스 | §4.5 |
| 9 | 데모 OIDC·`http-demo.sh`·README, opt-in 실 TSA 계약 테스트(별도 태스크 `tsaContractTest` — `check`에 걸지 않아 "스킵"이 생기지 않는다, 결정 8 보강) | 부록 B·§14 #4 |
| 10 | 전체 check + `TZ=UTC` check, 보고서, CI, 태그 `phase-6A`, PR(병합은 심사 회신 뒤) | |

각 단계는 그 단계의 테스트와 같은 커밋이다. 주입 기록은 커밋 본문에 남긴다.

---

## 13. 질문 (권장안 먼저)

**Q1. MANAGER 범위의 `org_path` 출처**
- 권장: V12 `disclosure.org_path`. 작성 시 설계사의 `identity_link.org_path` 스냅샷이고, INSERT 필수·불변(GD124)이다.
  - 기존 행은 NULL이고 백필하지 않는다. 기존 행은 소유 AGENT·COMPLIANCE만 닿는다. 공개 저장소에 운영 데이터가 없고 데모는 다시 시드하므로 비용이 0이다.
  - 근거: 관리자 확인은 **작성 당시 조직**의 책임자가 하는 행위다. 설계사가 다른 지점으로 옮겨도 과거 확인서가 따라가면 안 된다.
- 대안: 범위 산식을 `identity_link`로 실시간 조인(`agent_id` → 현재 `org_path`). 스키마 변경 0이지만 조직 이동 시 과거 문서가 따라간다.

**Q2. 설계사가 아닌 주체의 `identity_link`**
- 권장: `agent_id`·`org_path` NULL 허용 + CHECK(AGENT ⇒ `agent_id`, AGENT·MANAGER ⇒ 경로, 서비스 역할은 단독, 역할 닫힌 집합). §2.2.
- 대안: 서비스 클라이언트 전용 테이블. 해석 경로가 둘이 된다.

**Q3. 발송 시 토큰**
- 권장: 지시문대로. REMOTE_LINK 세션은 `token_hash` NULL로 태어나고, 발송 트랜잭션에서 1회 기록한다(가드 GD123).
- 대안: 발급 시 토큰을 만들되 아웃박스에는 세션 ID만 둔다. 발송 때 원문이 필요하므로 원문을 어딘가에 둬야 해서 지시문 금지와 충돌한다. 기각을 권한다.

**Q4. 멱등 재생 — "해시만 저장"과 "저장 응답 재생"의 양립**
- 권장: 쓰기 응답을 닫힌 **영수증 스키마**(ID·번호·상태·해시·코드)로 한정한다.
  - 영수증 튜플을 `response_ref`(JSONB, 같은 스키마로 검증)에 두고, 재생은 튜플로 바이트를 다시 만들어 `response_hash`와 대조한 뒤 보낸다.
  - 본문 바이트는 저장하지 않고, 개인정보가 들어갈 자리가 스키마상 없다.
- 대안 A: 해시만 저장하고 재생 때 현재 상태로 응답을 재구성한다. 상태가 그 사이 바뀌면 같은 바이트를 낼 수 없어 "재생"이 성립하지 않는다.
- 대안 B: 본문을 그대로 저장한다(지시문 문구와 다름).

**Q5. 피드 커서 대 포털 규약**
- 권장: 지시문대로 `after`(서명된 커서)·`next`로 바꾼다.
  - envelope의 `seq`와 응답 `headSeq`는 남겨 포털 소비 규칙(갭 감지)은 유지한다.
  - 소비자가 아직 없으므로(포털 미구현) 지금 바꾸는 비용이 가장 작다. 포털 설계서 v1.1 §4.1과 어긋남을 보고하고, `docs/event-feed.md`에 적는다.
- 대안: 피드만 정수 `afterSeq` 유지(포털 규약), 서명 커서는 다른 목록에만.

**Q6. 공개 경로 하한·한도의 출처**
- 권장: §5.3 프로세스 전역 스냅샷(하한 = 전 테넌트 GLOBAL 값의 최댓값, 주기 갱신).
- 대안: 두 값을 룰이 아닌 배포 설정으로. 지시문의 "룰 데이터"와 다르다.

**Q7. HTTP `jobs/ANCHOR`의 범위**
- 권장: 토큰 테넌트 하나로 A단계 + 그 테넌트의 미날인 앵커만 B단계(트리 1개 = 테넌트 1개). 전 테넌트를 한 루트로 묶는 플랫폼 배치는 CLI `anchor run --tenants all`(운영자·스케줄러 매니페스트 — Phase 8)로 남긴다.
  - 이유: 테넌트 토큰으로 다른 테넌트의 앵커를 만들 수 없다(규칙 5).
- 대안: HTTP에서 ANCHOR를 빼고 CLI만.

**Q8. 작업 잠금 커넥션**
- 권장: 전용 롤 `disclosure_job_lock`(테이블 권한 0) + 허용 목록 네 번째 항목 `JobLockGateway`.
- 대안: 앱 롤 커넥션을 풀에서 따로 쥔다. 허용 목록 규칙("전용 롤")을 어기므로 기각을 권한다.

**Q9. 진입점 시그니처와 CLI 감사 역할**
- 권장: `(TenantId, Actor)` → `(Caller)`로 바꾼다.
  - CLI의 감사 역할은 항상 `OPERATOR`이고 `--role` 인자는 폐기한다.
  - `--operator`로 준 주체가 설계사 연결을 요구하는 유스케이스(초안 작성·설계사 서명)는 지금처럼 `identity_link`로 확인한다(대리 실행).
- 대안: 시그니처를 두고 `Caller`를 추가 인자로. 테넌트가 두 곳에서 와 불일치 경로가 생긴다.

**Q10. `ANCHOR_MISSING_DAY`의 판정 영향**
- 권장: `ANCHOR_UNSTAMPED`와 같다. 발견이 있으면 MISMATCH(종료 2)이고 `CHAIN_BROKEN`은 아니다. 끝의 공백(어제까지)을 포함한다.
- 대안: 정보성(결과 MATCH 유지). 보고서 스키마에 심각도 개념을 새로 들여야 한다.

**Q11. 작업 상태 `QUEUED→FAILED`**
- 권장: 허용한다(실행 전 고아·실행기 거부).
- 대안: 지시문 표 그대로. 실행 전에 죽은 작업을 닫으려면 RUNNING을 거짓으로 거쳐야 한다.

**Q12. 작업 종류 추가 `NOTIFY`·`IDEMPOTENCY_PURGE`**
- 권장: 추가한다. 지시문 §7의 `NOTIFY`와 §1의 "만료 후 배치 삭제"를 받을 종류다.
- 대안: 지시문 6종 그대로 두고 둘은 CLI 전용. 잠금·추적 밖이 된다.

**Q13. R1 CHECK의 기존 행**
- 권장: 검증형(기존 행 위반 시 마이그레이션 실패).
- 대안: `NOT VALID`(소급 행을 남기고 새 행만 강제).

**Q14. 공개 `view` 엔드포인트(5번째)**
- 권장: 추가한다. 열람 증거(`recordView` — 스크롤 완료·열람 초)는 Phase 4 유스케이스이고 룰이 `SCROLL_COMPLETE`를 요구할 수 있다.
- 대안: `verify-identity` 본문에 합친다.

**Q15. 경로 접두와 `format` 범위**
- 권장: 지시문대로 사용자 API도 `/internal/v1`. 설계서 §7 표(`/api/v1`·`GET /sign/{token}`)를 고쳐 쓴다. `GET /sign/{token}`은 토큰 경로 금지와 정면 충돌한다.
- `format` 0의 범위는 이 시스템이 제공하는 내부·공개 계약이다. 소비하는 엔진 계약(`engine-disclosure.openapi.yaml`, 엔진 저장소와 고정 공유)은 E4에서 같은 교체를 요구사항으로 낸다.

**Q16. 동일 거부의 상태 코드**
- 권장: 404 `SIGN_LINK_UNAVAILABLE`(내부의 존재 누설 금지 404와 같은 계열).
- 대안: 410 또는 400.

**Q17. 6A 밖으로 두는 HTTP**
- 고객 등록(개인정보 수신 API)·카탈로그 검색·준법 큐·게이트를 둔다. 지시문 경로 목록에 없다.
- 권장: 고객 등록·카탈로그 검색은 Phase 7 화면 직전(6B 끝 또는 7 첫 단계)에 계획한다. 고객 등록은 개인정보 API이므로 별도 심사 항목으로 둔다.
- 6A의 HTTP 데모는 CLI로 등록한 데모 고객의 가명을 쓴다.
