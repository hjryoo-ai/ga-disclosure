# Phase 4 계획 — 서명·관리자 확인·만료·증거 패키지 (승인 대기)

기준: `docs/phase-04-지시문.md` v1.0, `docs/phase-03B-수용심사.md` §2·§3, 설계서 v1.8 §4.4·§4.5·§5·§6.1·§6.5·§6.6·§9. 브랜치 `work/phase-4`(main `69192f9` = 3B PR #6 병합에서 분기).

지시문이 계획 맨 앞에 요구한 두 가지(3B 일탈 9건 표, 질문 7번째)가 §0이다. 이어서 ①~⑤를 §1~§5에 둔다. 실측이 필요한 ③(서명본 증분 갱신)은 계획 단계에서 스파이크로 확인했다. 문언끼리 충돌하거나 기존 코드·DB와 맞지 않아 결정이 필요한 지점은 §9 질문에 모았다.

---

## 0. 앞머리

### 0.1 3B 보고서의 "설계서와 다르게 한 9건"

판정 기준(수용심사 단서): §5 불변식·§6.4 봉인 규약을 **넓히는가**.

| # | 지점 | 3B에서 한 것 | §5·§6.4를 넓히는가 |
|---|---|---|---|
| D1 | 고정 룰 로드 검사 | `RuleResolver.load`(고정 ID 로드)가 "상담일에 시행 중"이 아니라 **시작일 ≤ 상담일**만 확인한다. 소급 GLOBAL 배포가 고정 버전의 `apply_to`를 상담일 이전으로 닫아도 그 초안이 계속 로드된다 | **⚠ 검사 하나를 완화했다 — 판단 요청.** §5 불변식은 건드리지 않는다. §6.4 1항(봉인은 상담일 재해석 = 고정일 때만)도 그대로다. 봉인 조건 ①·②가 그런 초안의 봉인을 막고 `RULE_SUPERSEDED_DRAFT` 플래그를 올리며, 탈출은 재기준뿐이다. 넓어진 것은 §6.2 해석 규칙의 한 검사다. 봉인 전 명령(COMPARE·APPLY_SNAPSHOT·SET_RECOMMENDATIONS)은 이제 옛 고정 룰로 계속 실행된다. 이전에는 룰 해석 실패(명령 오류)로 모든 명령이 막혀 막다른 상태가 됐다. 되돌리면 소급 배포된 초안이 재기준 외 어떤 명령도 못 받는다. 그 상태는 이미 재기준이 해소한다. 유지를 권장한다 |
| D2 | 모듈 의존 Seal → Rules | 렌더러와 R-FIELD-REQUIRED가 같은 `BindingResolver`를 쓴다 | 아니다(모듈 배치. 역방향은 ArchUnit이 막는다) |
| D3 | 봉인 거부 시 플래그 감사 | `RULE_SUPERSEDED_DRAFT` 플래그를 올리되 별도 `FLAG_RAISE` 행 대신 거부 감사 1행의 `detail.flag`에 싣는다 | 아니다(S6 "거부 시 감사 1행"을 따른 것. 기록 정보는 같다) |
| D4 | 버킷 확인 시점 | 기동 때가 아니라 저장소 첫 사용 직전 | 아니다(§9 운영 규약. 어떤 쓰기보다 먼저 실패하므로 커밋 전 잠금 금지는 그대로) |
| D5 | 정정 사유 저장 | 컬럼 없음, 감사에 해시·길이만 | 아니다(좁힘). V8에서 해소(§1, 수용심사 §2-2) |
| D6 | 실패 주입 대역 | `FailingPorts.Store`·`Records` 둘로 나눔 | 아니다(테스트 구조) |
| D7 | CLI 종료 코드 | 업무 거부 2, 오류 1 | 아니다 |
| D8 | AWS SDK 2.55.9 | 계획 2.55.8 → 착수 시 최신 | 아니다 |
| D9 | 3A 로컬 볼륨 | 서식 제자리 재해시로 `down -v` 필요 | 아니다(불변 규칙의 정상 동작) |

결론: §5·§6.4를 넓힌 항목은 없다. D1은 §6.2 해석 검사의 완화이므로 표시해 둔다. 되돌리라는 판단이면 Phase 4 선행 소과제로 처리한다(되돌린 경우의 막다른 상태 해소안은 §9 Q12).

### 0.2 Phase 4 질문 7번째

3B 보고서 §10의 질문은 7개였고, 심사 표(§3)는 6개를 다뤘다. 빠진 하나는 보고서 §10-5 **"정정 사유 원문(D5)"**이다. 이것은 수용심사 **§2-2가 이미 결정했다**(V8에 `reason_code` + `reason_text`, 무효·정정 같은 형태, 원본 행 메타 write-once). 따라서 열린 질문은 없다. 번호 대응은 다음과 같다: 보고서 1~4 = 심사 1~4, 보고서 5 = 심사 §2-2, 보고서 6 = 심사 5, 보고서 7 = 심사 6.

---

## 1. V8 DDL 초안 (①) — `V8__sign.sql`

기존 `V*` 파일은 고치지 않는다. 함수는 `CREATE OR REPLACE`로 교체하고, `signature`·`sign_session`은 지금까지 쓴 곳이 없어(행 0) `ALTER`로 형태를 바꾼다. 오류 코드는 GD100번대를 쓴다.

### 1.1 `disclosure` — 사유 코드·텍스트, 완료 시각 (수용심사 §2-2)

```sql
ALTER TABLE disclosure
  ADD COLUMN void_reason_code TEXT,  ADD COLUMN void_reason_text TEXT,
  ADD COLUMN supersede_reason_code TEXT, ADD COLUMN supersede_reason_text TEXT;
-- 메타 목록 교체(ga_disclosure_guard_update): 위 4개를 메타에 추가(봉인 후에도 VOID·SUPERSEDE 전이 때 한 번 쓴다).
-- 이관(dev·CI DB만 존재): void_reason → void_reason_text, code = 'MIGRATED_V8'; SUPERSEDED 원본은 code = 'MIGRATED_V8', text NULL.
-- CHECK 교체: (status='VOID') = (voided_at IS NOT NULL) = (void_reason_code IS NOT NULL),
--             (status='SUPERSEDED') = (superseded_by_id IS NOT NULL) = (supersede_reason_code IS NOT NULL),
--             코드 형식 ^[A-Z][A-Z0-9_]{0,39}$, 텍스트는 NULL이거나 공백 아님, completed_at은 COMPLETED면 NOT NULL.
-- void_reason은 NULL 허용으로 바꾸고 더는 쓰지 않는다 → V9에서 DROP(전방 호환 2단계).
```

- **write-once(GD100)**: `void_reason_code/text`·`supersede_reason_code/text`·`completed_at`은 NULL→값 한 번만 쓴다. `superseded_by_id`(GD004)와 같은 규칙이다.
- 사유 코드가 룰 데이터 `voidReasons`·`supersedeReasons`의 닫힌 목록에 있는지는 애플리케이션이 고정 룰로 검사한다. DB는 형식만 본다(DB가 룰 본문을 해석하지 않는다는 3B 원칙).

### 1.2 `sign_session` — 고객 서명 세션 (§2)

```sql
ALTER TABLE sign_session
  ALTER COLUMN token_hash SET NOT NULL,                       -- SHA-256 소문자 hex(원문 미저장)
  ADD COLUMN issued_by TEXT NOT NULL, ADD COLUMN issued_at TIMESTAMPTZ NOT NULL,
  ADD COLUMN sent_at TIMESTAMPTZ,                             -- REMOTE_LINK 발송 시각(대리 서명 탐지 기준점)
  ADD COLUMN signed_doc_hash TEXT NOT NULL, ADD COLUMN signed_pdf_hash TEXT NOT NULL,   -- 발급 시 고정
  ADD COLUMN identity_failures SMALLINT NOT NULL DEFAULT 0,
  ADD COLUMN identity_passed TEXT[] NOT NULL DEFAULT '{}',    -- 통과한 수단(결과만 — 입력값 없음)
  ADD COLUMN view_evidence JSONB,                             -- {openedAt, scrollComplete, durationSeconds}
  ADD COLUMN revoked_at TIMESTAMPTZ, ADD COLUMN revoke_reason TEXT;
-- CHECK: signer_role = 'CUSTOMER'(설계사·관리자는 SSO로 서명 — §2, Q3), channel ∈ {TOUCH_PAD, REMOTE_LINK, PAPER_SCAN}
--        (CERTIFIED_ESIGN은 v2), status ∈ {OPEN, USED, EXPIRED, REVOKED}, USED ⇔ used_at, REVOKED ⇔ revoked_at ⇔ revoke_reason
--        ∈ {IDENTITY_FAILED, DOCUMENT_VOIDED, DOCUMENT_SUPERSEDED, DOCUMENT_EXPIRED, REISSUED}, 해시 hex64, sent_at은 REMOTE_LINK만.
CREATE UNIQUE INDEX ux_sign_session_open ON sign_session (tenant_id, disclosure_id, signer_role) WHERE status = 'OPEN';
CREATE UNIQUE INDEX ux_sign_session_token ON sign_session (tenant_id, token_hash);
```

- **세션 트리거(GD101)**
  - INSERT는 `status = OPEN`, 부모가 `SEALED|PARTIALLY_SIGNED`, 고정 해시 = 부모 `canonical_hash`·`pdf_hash`일 때만 허용한다.
  - UPDATE: 식별·고정 컬럼은 불변이다. `OPEN` 동안에는 `identity_failures` +1, `identity_passed` 원소 추가, `view_evidence`·`sent_at` NULL→값만 허용한다.
  - `status`는 `OPEN → {USED, EXPIRED, REVOKED}` 한 번뿐이고, 닫힌 세션의 행은 바뀌지 않는다.
  - DELETE·TRUNCATE는 거부한다.

### 1.3 `signature` — 두 해시 귀속 (수용심사 §3-1)

```sql
ALTER TABLE signature
  ADD COLUMN signed_pdf_hash TEXT NOT NULL,                   -- = disclosure.pdf_hash
  ADD COLUMN session_id UUID,                                 -- 고객 서명의 세션, 설계사·관리자는 NULL
  ADD COLUMN view_evidence JSONB,
  ADD COLUMN acknowledged_flags UUID[] NOT NULL DEFAULT '{}', -- 관리자 확인의 사유 확인 체크(§6.5)
  ADD COLUMN scan_match JSONB,                                -- PAPER_SCAN: 설계사가 입력한 번호·해시 접두와 대조 결과
  ALTER COLUMN identity_check SET NOT NULL,                   -- [{type, result, at}] 결과만
  DROP COLUMN evidence_key, DROP COLUMN evidence_hash;        -- 객체는 signature_evidence(§1.4, Q2)
-- CHECK: method ∈ {DRAWN, UPLOADED_SCAN, SSO_APPROVAL}, channel ∈ {TOUCH_PAD, REMOTE_LINK, PAPER_SCAN, SSO}(Q3),
--        (signer_role = 'CUSTOMER') = (session_id IS NOT NULL) = (signer_subject IS NULL),
--        (channel = 'SSO') = (signer_role <> 'CUSTOMER'), (method = 'UPLOADED_SCAN') = (channel = 'PAPER_SCAN'), 해시 hex64.
```

- **삽입 트리거 교체(`ga_signature_guard_insert`)**
  - 기존: 부모 상태 `SEALED|PARTIALLY_SIGNED`(GD021), `signed_doc_hash = canonical_hash`(GD022).
  - 추가: `signed_pdf_hash = pdf_hash`(GD102).
  - 추가: 세션이 있으면 같은 확인서·역할의 `OPEN` 세션이고, 세션의 고정 해시 = 서명의 두 해시(GD103).
  - 추가: 역할이 고정 GLOBAL 룰 `signerSet`에 있거나, `managerConfirmMode = OPTIONAL`이고 역할 = MANAGER(GD104).
- append-only(GD030)는 그대로다. 세션은 서명 INSERT **뒤** 같은 트랜잭션에서 `USED`가 된다(트리거가 OPEN을 보는 순서).

### 1.4 `signature_evidence` — 서명 증거 객체 (신설, Q2)

```sql
CREATE TABLE signature_evidence (
  tenant_id TEXT, signature_id UUID, kind TEXT,              -- STROKES(JSON) | IMAGE(PNG) | SCAN(PNG·JPEG)
  disclosure_id UUID NOT NULL,
  storage_key TEXT NOT NULL,                                 -- = {tenant}/{disclosure}/SIG/{signature_id}/{kind}/{cipher_sha256}(CHECK)
  sha256 TEXT NOT NULL, bytes BIGINT NOT NULL, cipher_sha256 TEXT NOT NULL, cipher_bytes BIGINT NOT NULL,
  key_id TEXT NOT NULL,                                      -- 같은 확인서의 살아 있는 문서 키(GD093과 같은 검사)
  created_at TIMESTAMPTZ NOT NULL, retention_applied_at TIMESTAMPTZ, retention_applied_until DATE,
  PRIMARY KEY (tenant_id, signature_id, kind)
);
```

- 암호화는 **문서 DEK 재사용**이고, AAD = JCS `{disclosureId, kind, signatureId, tenantId, v:1}`이다(수용심사 §3-4). 키를 파기하면 서명 증거도 함께 읽을 수 없다.
- 트리거(GD105): 서명 행이 있어야 하고 같은 확인서여야 한다. 키 검사는 GD093과 같다. 보존 기록 외 UPDATE와 DELETE는 거부한다.

### 1.5 보존 재적용 추적 — 연장만 (수용심사 §3-3)

- 3B는 `document_artifact.retention_applied_at`(NULL→값 1회)만 둔다. 완료 시 기한을 연장하면 객체마다 **다시** 걸어야 한다.
- `document_artifact`·`signature_evidence`에 `retention_applied_until DATE`를 추가한다. 갱신은 **증가만** 허용한다(GD093 교체 + GD105).
- `artifacts reconcile` 대상은 `retention_applied_until IS NULL OR < disclosure.retention_until`로 넓힌다. `retention_applied_at`은 첫 적용 시각으로 남긴다.
- `retention_until` 연장 전용 트리거(GD094)는 3B 그대로다.

### 1.6 `outbox_event` — 아웃박스 (신설)

- 아웃박스 테이블이 아직 없다. 계약 `contracts/events/v1/envelope.schema.json`은 테넌트 내 **갭 없는 `seq`**를 요구한다.

```sql
CREATE TABLE outbox_head (tenant_id TEXT PRIMARY KEY REFERENCES tenant, seq BIGINT NOT NULL);   -- +1만(GD106), 체인 머리와 같은 방식
CREATE TABLE outbox_event (
  tenant_id TEXT, seq BIGINT, event_id UUID NOT NULL, type TEXT NOT NULL, version INT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL, aggregate_kind TEXT NOT NULL, aggregate_id TEXT NOT NULL, payload JSONB NOT NULL,
  published_at TIMESTAMPTZ,                                   -- 피드(Phase 6)가 NULL→값 1회
  PRIMARY KEY (tenant_id, seq), UNIQUE (tenant_id, event_id)
);
```

- 잠금 순서는 3B 순서 뒤에 붙인다: 확인서 → 카운터 → 체인 머리 → **아웃박스 머리**.
- 적재 전에 payload를 계약 스키마로 검증한다(테스트).

### 1.7 그 밖

- `compliance_flag.type`에는 DB CHECK가 없다(V1). 새 유형은 애플리케이션 닫힌 어휘에 추가한다: `SIGNATURE_DEVICE_REUSE`, `IDENTITY_FAILED`, `SIGN_EXPIRED`, `PAPER_SCAN_REVIEW`.
- RLS: 새 테이블 3개(`signature_evidence`, `outbox_head`, `outbox_event`)에 V2와 같은 정책을 둔다(`sign_session`·`signature`는 V2에 이미 있다). `TenantPredicateScan`·`RlsIsolationIT`의 테이블 수 24 → 27.
- `db-error-codes.md`: GD100~GD106.

---

## 2. 세션·토큰 수명 주기 (②)

### 2.1 누가 세션을 쓰는가

- **고객 서명만 토큰 세션**을 쓴다(TOUCH_PAD·REMOTE_LINK·PAPER_SCAN).
  - TOUCH_PAD·PAPER_SCAN의 토큰은 설계사 기기 앱이 쥐고, 고객에게 보내지 않는다.
  - REMOTE_LINK 토큰만 `NotifyPort`로 나간다.
- **설계사·관리자는 SSO 행위자(`Actor`)로 직접 서명**한다(채널 `SSO`). 신원 귀속은 OIDC에 있다(§6.5). 설계사는 `identity_link`로 해석한 `agent_id` = 확인서 `agent_id`일 때만 서명할 수 있다.

### 2.2 토큰

- 형식은 `{tenantId}~{base64url(32바이트)}`이다(Q4). 테넌트 접두는 공개 엔드포인트(Phase 6)가 RLS 바인딩 전에 테넌트를 알기 위한 것이고, 비밀은 뒤 256비트다.
- 저장은 `token_hash = SHA-256(토큰 전체 ASCII)`뿐이다.
- 난수는 포트 `TokenSource`로 받는다(infra = `SecureRandom`, 테스트 = 시드 고정). `disclosure-sign`은 환경 무의존을 유지한다.
- TTL
  - REMOTE_LINK는 룰 `remoteLinkTtlHours`.
  - TOUCH_PAD·PAPER_SCAN은 새 룰 키 `sessionTtlMinutes { TOUCH_PAD, PAPER_SCAN }`(§6, tenantOverridable).
  - 세션 만료는 `min(issued_at + TTL, 서명 기한 끝)`이다.

### 2.3 세션 상태 전이표 (보고서 ①의 정본 후보 — `state-table`과 같은 기계 판독 CSV)

```session-state-table
state,event,result
OPEN,OPEN_VIEW,OPEN
OPEN,IDENTITY_PASS,OPEN
OPEN,IDENTITY_FAIL,OPEN|REVOKED
OPEN,CAPTURE,USED
OPEN,TTL_ELAPSED,EXPIRED
OPEN,REISSUE,REVOKED
OPEN,DOCUMENT_VOID,REVOKED
OPEN,DOCUMENT_SUPERSEDE,REVOKED
OPEN,DOCUMENT_EXPIRE,REVOKED
```

- 닫힌 상태(USED·EXPIRED·REVOKED)는 어떤 사건도 받지 않는다(표 밖 = 거부, 세션 불변).
- `IDENTITY_FAIL`은 `identity_failures + 1`이 룰 `identityCheck.maxFailures`에 닿으면 `REVOKED(IDENTITY_FAILED)` + 플래그 `IDENTITY_FAILED`다.
- `TTL_ELAPSED`는 접근 시 판정하되 상태는 바꾸지 않고 업무 거부(`SESSION_EXPIRED`)로 응답한다. 상태 기록은 만료 배치(§7.4)와 재발급(`REISSUE` 직전)이 한다. 지시문의 "실패는 세션 상태를 바꾸지 않는다"를 따른 것이다.
- 열람·확인 **실패 시도**는 세션 컬럼이 아니라 감사 `SIGN_SESSION_DENIED`(사유 코드만)로 센다. 모르는 토큰은 세션이 없으므로 테넌트 접두로 그 테넌트에 1행을 남긴다.
- 문서 상태(§6.1)와 같은 방식으로 설계서 §6.5에 이 블록을 두고, 테스트가 파싱해 코드(EnumMap)와 양방향 전수 대조한다.

### 2.4 본인확인 (`VerifyIdentity`)

- 룰 `identityCheck[channel]`의 수단을 **전부** 통과해야 서명할 수 있다.
  - `LINK_POSSESSION`: 토큰으로 세션을 연 사실.
  - `BIRTH_DATE`: 저장된 생년월일을 복호화(감사 `CUSTOMER_VIEW`, 사유 `IDENTITY_CHECK`)해 Phase 2 `BirthDate.matches`(상수 시간)로 비교한다.
  - `AGENT_FACE_TO_FACE`: 세션 발급자(설계사)가 같은 확인서의 담당 설계사이고, 대면 확인 체크를 **설계사 행위자로** 기록한 것.
  - `SCROLL_COMPLETE`: `view_evidence.scrollComplete = true`.
- 입력값(생년월일 문자열)은 메서드 지역 변수로만 다니고 로그·감사·예외 메시지·DB·`toString`에 남지 않는다.
  - 결과만 `identity_passed`·`signature.identity_check`에 남는다.
  - `PlaintextLeakScanIT`에 센티널 생년월일을 추가한다(G4).

---

## 3. 서명본 증분 갱신 (③) — 스파이크로 확인함

**방법(수용심사 §3-2)**
1. 봉인 PDF 원본 바이트를 `Loader.loadPDF`로 연다.
2. 서명 외관 페이지(서명 레코드 표·서명 이미지)를 3B와 같은 openhtmltopdf 구성(PDF/A-2b, 동봉 폰트·ICC)으로 만든다. 그 문서를 **한 번 저장해 다시 읽은 뒤**(폰트 서브셋이 저장 때 확정된다) `LayerUtility.importPageAsForm`으로 원본 문서에 폼 XObject로 복제한다.
3. 새 페이지에 그린다.
4. 정보 `/ModDate`와 XMP `ModifyDate`·`MetadataDate`를 **마지막 서명의 `signed_at`**(고정 오프셋 +09:00)으로 바꾼다. XMP는 3B와 같이 고정 템플릿으로 통째 다시 쓴다.
5. `/ID`: 첫 원소는 원본 그대로 두고, 둘째 원소는 PDFBox가 증분 저장 때 다시 만든다. 그래서 `doc.setDocumentId(seed)`로 시드를 준다. `seed = SHA-256(pdf_hash ‖ JCS(서명 레코드 요약))`의 앞 8바이트를 long으로 쓴다.
6. `saveIncremental` — 원본 바이트 뒤에 갱신분만 붙는다.

**스파이크 결과(2026-10-01, 골든 case-01 + 서명 외관 1쪽, 투명 PNG 서명 이미지 포함, 커밋하지 않음)**

| 확인 | 결과 |
|---|---|
| 원본 38,037바이트가 서명본의 접두 | 일치 |
| 같은 JVM 2회 | 바이트 동일 |
| 다른 JVM 2회(Gradle 재실행) | SHA-256 `cba07217…282f` 동일 |
| veraPDF PDF/A-2b | 원본·서명본 모두 `compliant=true failedChecks=0` |

- 스파이크 중 두 가지가 실패했고, 둘 다 고쳐서 확인했다.
  - (a) `setDocumentId` 없이는 증분 저장이 `/ID` 둘째 원소를 시각 기반으로 다시 만들어 매번 바뀌었다.
  - (b) 저장하지 않은 서명 페이지 문서를 바로 복제하면 폰트 서브셋이 아직 만들어지지 않아 veraPDF 6.2.11.4.1(폰트 미임베드)로 실패했다.
- **PDF/A 유지 근거**
  - PDF/A-2(ISO 19005-2)는 증분 갱신을 금지하지 않는다.
  - 마지막 개정의 정보 사전과 XMP가 일치하고, 새 페이지의 폰트·색 공간이 기존 OutputIntent(sRGB) 아래에서 임베드돼 있으면 된다.
  - PDF/A-2는 투명도를 허용한다(PDF/A-1과 다름).
  - CI `pdfa-verify`에 서명본 골든을 추가해 매 빌드가 이를 검사한다(G7).
- **암호학적 PAdES 서명이 아니다.** 증거력은 체인·증거 패키지에 있다는 문장을 설계서 §6.5에 넣는다(수용심사 §3-2).
- **위반 주입(G14)**: 증분 갱신 대신 전체 재생성 → `SignedPdfGoldenTest`의 접두 단언이 실패해야 한다.

---

## 4. 증거 패키지 매니페스트 스키마 (④) — `contracts/seal/v1/evidence-manifest.schema.json`

- 패키지 빌더는 JCS·SHA-256이 필요해 `disclosure-seal`(`seal.evidence`)에 둔다. Sign은 Phase 1 규칙상 `platform-canonical`을 쓰지 않는다(Q6).

```json
{
  "manifestVersion": 1,
  "tenantId": "DEMO1", "disclosureId": "…", "disclosureNo": "DEMO1-2026-000002", "version": 1,
  "hashes": { "canonical": "<64>", "pdf": "<64>", "signedPdf": "<64>", "chain": "<64>", "chainSeq": 2 },
  "pinned": { "ruleVersionId": "DISC-2026-07", "ruleBundleHash": "<64>", "tenantRuleVersionId": "DEMO1-HOUSE-2026",
              "tenantRuleBundleHash": "<64>", "templateId": "STANDARD", "templateVersion": 1, "templateBundleHash": "<64>" },
  "snapshot": { "snapshotId": "…", "gradingPolicyVersionId": "…", "rankingPolicyVersionId": "…", "tieBreak": "SHARED_RANK",
                "generatedAt": "…" },
  "sealedAt": "…", "completedAt": "…", "retentionUntil": "2031-10-01",
  "signatures": [
    { "file": "signatures/1-CUSTOMER.json", "signatureId": "…", "role": "CUSTOMER", "channel": "TOUCH_PAD", "method": "DRAWN",
      "signedAt": "…", "signedDocHash": "<64>", "signedPdfHash": "<64>", "sessionId": "…",
      "identityCheck": [ { "type": "AGENT_FACE_TO_FACE", "result": "PASS", "at": "…" } ],
      "evidence": [ { "kind": "STROKES", "sha256": "<64>", "cipherSha256": "<64>", "bytes": 1234 } ] }
  ],
  "audit": { "file": "audit.jsonl", "fromSeq": 101, "toSeq": 162, "rows": 19, "lastEntryHash": "<64>" },
  "anchor": null,
  "files": [ { "path": "canonical.json", "sha256": "<64>", "bytes": 2784 } ]
}
```

- **엔트리**: `manifest.json`(JCS 바이트), `canonical.json`, `disclosure.pdf`, `disclosure-signed.pdf`, `signatures/{순번}-{역할}.json`(결과·시각·기기·IP·해시 — 입력값·이미지 없음), `audit.jsonl`(이 확인서 대상 감사 행 전부, `entry_hash` 포함, seq 순).
  - `files`는 manifest를 뺀 엔트리를 경로 순으로 담는다. 그래서 매니페스트 해시가 나머지 전부를 묶는다.
- **스트로크·이미지·스캔 원본은 넣지 않는다.** `signatures[].evidence`에 해시만 둔다(지시문).
- **결정론**
  - 엔트리 순서는 `manifest.json` 다음 경로 순.
  - 압축 없이 **STORED**다. Deflate 출력은 플랫폼 zlib에 따라 달라질 수 있다(Temurin은 Linux에서 시스템 zlib를 쓴다). PDF는 이미 압축돼 있어 손해가 작다.
  - 엔트리 시각은 `setTimeLocal(1980-01-01T00:00)`(DOS 시각, 시간대 무관), extra 필드·주석은 없다.
- **감사 범위**: 대상 = `target_id = disclosure_id` 또는 `detail.disclosureId = disclosure_id`인 행. 범위는 첫 행부터 완료 트랜잭션에서 패키지를 만드는 직전 행까지다(완료 감사 행 자체는 패키지 뒤에 쓰인다 — 순환 방지, 매니페스트 `toSeq`가 경계).
- 앵커 참조는 Phase 5 전까지 `null`이다(지시문).

---

## 5. 대리 서명 탐지 산식 (⑤)

| 지표 | 산식 | 룰 파라미터(`proxySignatureDetection`) |
|---|---|---|
| 기기 재사용 | 이번 고객 서명의 기기 지문 `f`에 대해 `D = |{ customer_ref(s) : s ∈ 같은 테넌트 CUSTOMER 서명, channel = REMOTE_LINK, device.fingerprint = f, KST 날짜(signed_at) = KST 날짜(이번) }|`(이번 포함). `D ≥ N`이면 플래그 | `sameDeviceDistinctCustomersPerDay = N`(예 2 → 같은 기기로 서로 다른 고객 2명 이상) |
| IP 재사용 | 같은 식, 키 = `ip` | 새 키 `sameIpDistinctCustomersPerDay`(선택, 없으면 IP 지표 끔 — 이동통신 NAT로 고객이 IP를 공유한다, Q8) |
| 발송→서명 시간 | `t = signed_at − session.sent_at`(초). `t < M`이면 플래그 | `minSecondsFromSendToSign = M` |

- **대상은 REMOTE_LINK 고객 서명뿐이다.** TOUCH_PAD는 설계와 같이 설계사 기기 1대로 여러 고객이 서명하는 것이 정상이다. 넣으면 모든 대면 영업이 플래그가 된다(§6.5 문언도 REMOTE_LINK).
- 감지는 서명을 막지 않는다. `compliance_flag(SIGNATURE_DEVICE_REUSE)`의 대상은 서명(`SIGNATURE`/`{signature_id}`)이고, 감사 `FLAG_RAISE`에 지표·값·임계치를 남긴다. 지문·IP 원값은 남기지 않는다.
- "일"은 Asia/Seoul 달력일이다(이동 24시간이 아니라 재현 가능한 경계).
- 순수 함수 `ProxySignatureDetector.evaluate(이번 서명, 같은 날 같은 지문·IP의 고객 수, 발송 시각, 파라미터)`는 `disclosure-sign`에 둔다. 집계 쿼리는 infra가 한다.

---

## 6. 룰·서식 데이터 변경

`released-bundles.txt`가 비어 있으므로(운영 배포 없음) `DISC-2026-07`·`DISC-2027-01`·`STANDARD.v1`을 제자리에서 다시 해시한다. 3B와 같이 기존 로컬 볼륨은 `down -v`가 필요하다.

| 키 | 형태 | 비고 |
|---|---|---|
| `voidReasons`, `supersedeReasons` | `[{code, label, requiresText?}]` | 수용심사 §2-2. GLOBAL 전용이다 |
| `lifecycleReasonTextMaxLength` | 정수 | 사유 텍스트 상한. `reasonTextMaxLength`(추천사유)와 분리한다 |
| `retentionAnchors` | `["SEAL","COMPLETION","CONTRACT_DATE"]` 부분집합 | 수용심사 §3-3. GLOBAL 전용이다 |
| `channels` | 값 `boolean` → `{enabled, requiresManagerReview?}` | `PAPER_SCAN.requiresManagerReview` 기본 true(지시문). G2의 "`channels.PAPER_SCAN=false`"는 `enabled=false`로 읽는다(Q5) |
| `sessionTtlMinutes` | `{TOUCH_PAD, PAPER_SCAN}` | tenantOverridable |
| `agentSignMethod` | `DRAWN|SSO_APPROVAL` | 설계사 터치 서명 또는 승인 클릭(§6.5 "테넌트 파라미터") → tenantOverridable |
| `proxySignatureDetection.sameIpDistinctCustomersPerDay` | 정수, 선택 | Q8 |
| `gateRequiresManager` | 그대로 | tenantOverridable에 추가하고, `tenant.params`의 같은 키는 읽지 않는다(Q7) |

- 서식 `STANDARD.v1` `layout`에 `signaturePage { title, roleLabels{CUSTOMER,AGENT,MANAGER}, channelLabels{…}, methodLabels{…}, identityLabels{…}, resultLabels{PASS,FAIL} }`를 추가한다. 역할·채널 이름은 코드의 닫힌 어휘이고, 문구는 데이터다. `NoFieldCodeLiteralsTest`를 서명 외관 렌더러에도 적용한다.
- 스키마(`rule-version.schema.json`·`form-template.schema.json`) 갱신과 `RuleAsDataIT` 기대 diff 갱신이 함께 간다.

---

## 7. 유스케이스와 트랜잭션

배치 원칙:
- 순수 규칙은 `disclosure-sign`에 둔다: 세션 상태표, 토큰 형식·해시, 본인확인 정책, 대리 서명 탐지, 게이트 산식, 보존 앵커 산식.
- 렌더·패키징은 `disclosure-seal`에 둔다: `SignedPdfAppender`(renderer), `EvidencePackageBuilder`(evidence).
- 트랜잭션 유스케이스는 `disclosure-workflow`의 `SignService`·`CompletionService`·`ExpireService`이고, 포트 구현은 infra다.
- 애그리게이트에 `SIGN`·`COMPLETE`·`EXPIRE`를 구현한다(상태표 정본 그대로, W1 "구현된 명령" 목록 확장).

### 7.1 고객 서명 `CaptureSignature(token, strokes, image, device, ip)` — 한 쓰기 트랜잭션

1. 토큰에서 테넌트 파싱 → 바인딩.
2. 세션 `FOR UPDATE` → 확인서 `FOR UPDATE`.
3. 검사(단락 없음, 업무 거부 목록): `SESSION_NOT_OPEN`·`SESSION_EXPIRED`·`IDENTITY_INCOMPLETE`·`ORDER_VIOLATION`·`HASH_CHANGED`(세션 고정 해시 ≠ 부모)·`DEADLINE_PASSED`·`CHANNEL_DISABLED`.
4. 서명 ID 발급 → 스트로크(JSON)·이미지(PNG, 형식·크기 검사)를 문서 키로 암호화 → 업로드(잠금 없음).
5. `signature` INSERT(트리거 GD021·022·102~104) → `signature_evidence` INSERT → 세션 `USED`.
6. 대리 서명 탐지 → 플래그.
7. 애그리게이트 `SIGN` → `PARTIALLY_SIGNED` 또는 완료 조건 충족 시 §7.3을 **같은 트랜잭션**에서 실행.
8. 감사 `SIGNATURE_CAPTURED` → 아웃박스 `SignatureCaptured` → 커밋.
9. 커밋 뒤 증거 객체에 잠금(현재 `retention_until`)을 건다.

### 7.2 설계사·관리자·종이 스캔

- **`AgentSign(actor)`**: 행위자 → `identity_link` → `agent_id` = 확인서 `agent_id`. 방법은 룰 `agentSignMethod`이고, DRAWN이면 스트로크·이미지가 필요하다.
- **`ManagerConfirm(actor, acknowledgedFlags)`**
  - `managerConfirmMode = OFF`이면 업무 거부 `MANAGER_CONFIRM_DISABLED`다.
  - 확인서에 걸린 플래그(열림·닫힘 전부, Q9) 전부를 `acknowledgedFlags`에 담아야 한다. 아니면 `ACKNOWLEDGEMENT_MISSING`.
  - `method = SSO_APPROVAL`이다. 역할·조직 범위 인가는 Phase 6이고, 지금은 `actor`와 감사만 남긴다.
- **`UploadPaperScan(token, scan, enteredNo, enteredHashPrefix)`**
  - 설계사가 입력한 각주 번호·해시 접두 12자를 `disclosure_no`·`canonical_hash[0:12]`와 대조한다. OCR은 하지 않는다. 불일치는 업무 거부 `SCAN_MISMATCH`이고, 감사에는 일치 여부만 남긴다.
  - `requiresManagerReview`이면 플래그 `PAPER_SCAN_REVIEW`를 연다.
  - 완료 조건에 "열린 `PAPER_SCAN_REVIEW` 없음"을 더한다. 해소 경로는 MANAGER가 signerSet에 있으면 그 확인(acknowledge), 없으면 `ReviewPaperScan(actor = exceptionApproval.role)`이다(Q10). 해소 뒤 `COMPLETE` 명령이 완료를 시도한다. 상태표 `PARTIALLY_SIGNED,COMPLETE`의 쓰임이 이것이다.

### 7.3 완료 `Complete`(마지막 서명의 트랜잭션 또는 `COMPLETE` 명령)

1. COMPLETE 단계 검증(`R-SIGNER-SET`: 집합·순서·기한) + 열린 `PAPER_SCAN_REVIEW` 없음.
2. 아니면 PARTIALLY_SIGNED에 머문다(업무 거부 아님 — 다음 서명을 기다린다).
3. 서명본 PDF(§3) → 매니페스트·패키지(§4) → 문서 키로 암호화·업로드 → `document_artifact` SIGNED_PDF·EVIDENCE_ZIP.
4. `retention_until = max(현재, max(앵커 날짜 + retentionYears))`(앵커 = 룰 `retentionAnchors` 중 있는 것: SEAL = 봉인일, COMPLETION = 완료일 KST; CONTRACT_DATE는 Phase 6 정책 연결이 같은 함수 호출).
5. `COMPLETED`·`completed_at` → 감사 `DISCLOSURE_COMPLETED` → 아웃박스 `DisclosureCompleted`.
6. 커밋 뒤 **모든** 산출물·증거 객체에 새 기한으로 `PutObjectRetention`(연장)을 건다. 실패 시 `reconcile`이 처리한다(§1.5).

### 7.4 만료 `ExpireJob(asOf)` · 무효·정정 연동

- **서명 기한 끝**: `D = 봉인일(KST) + signDeadlineDays`의 23:59:59.999999 KST다. `Disclosure.signDeadline()`과 `signatures()`가 이 값과 서명 목록을 내도록 구현한다(지금은 비어 있는 자리표시 — R-SIGNER-SET이 이 둘을 읽는다).
- **ExpireJob**: `asOf > D`인 `SEALED|PARTIALLY_SIGNED` → `EXPIRED`, 세션 전부 `REVOKED(DOCUMENT_EXPIRED)`, 플래그 `SIGN_EXPIRED`, 감사. 같은 실행이 TTL이 지난 `OPEN` 세션을 `EXPIRED`로 쓸어 담는다. 확인서마다 트랜잭션 1개이고, CLI는 `disclosure expire --as-of --tenants`.
- **3B `voidDisclosure`·`supersede` 확장**
  - 사유 코드 + 선택 텍스트(룰 목록·상한 검사)를 받는다.
  - 같은 트랜잭션에서 `OPEN` 세션 전부 `REVOKED(DOCUMENT_VOIDED|DOCUMENT_SUPERSEDED)`.
  - 기존 서명은 보존하고, 새 버전으로 이월하지 않는다(수용심사 §3-5).
  - CLI: `--reason-code` + 선택 `--reason-file`.

### 7.5 게이트 산식 (`GateFunction`, 순수)

- 입력: 상태, 고정 `signerSet`, 서명한 역할 집합, `gateRequiresManager`.
- 출력: `status`(COMPLETED|PENDING|NONE), `pendingRoles`(signerSet 순서), `gateSatisfied`.
  - COMPLETED면 `gateSatisfied = true`이고 `pendingRoles = []`다.
  - `gateRequiresManager = false`이면 MANAGER 외 signerSet 전원이 서명했을 때부터 true다.
  - VOID·EXPIRED·SUPERSEDED는 `NONE`·false다(어느 확인서를 고를지는 Phase 6 API).

### 7.6 통지·CLI·데모

- **`NotifyPort.sendSignLink(customerRef, url)`**: 콘솔 구현은 표준 출력에만 쓰고 로거는 쓰지 않는다. 전화번호는 `phoneForNotification`(감사 `CUSTOMER_PHONE_READ`, 사유 `SIGN_LINK`)이고, 콘솔에는 마스킹된 번호만 찍는다.
- **CLI**: `sign session|open|verify|capture|scan|agent|manager|review-scan`, `disclosure complete|expire`.
  - 본인확인 입력·스트로크·이미지는 **파일로만** 받는다(`--inputs-file`, `--strokes-file`, `--image-file` — 허구 데이터).
  - 토큰은 PII가 아니라 인자로 받는다.
- **데모**: 3자 서명 완료(TOUCH_PAD → AGENT → MANAGER), 원격 링크(콘솔 토큰을 스크립트가 받아 이어 실행), 종이 스캔(관리자 확인), 만료 1건. 2회째는 NOOP이다. 만료 사례는 상담일·봉인을 과거로 둔 별도 테넌트 시계가 아니라 `--as-of` 인자로 만든다.

---

## 8. 완료 기준 ↔ 테스트

| # | 테스트 | 핵심 |
|---|---|---|
| G1 | `SignatureBindingIT` | 상태 10종 × {doc 해시 일치/불일치} × {pdf 해시 일치/불일치} 전수 INSERT(GD021·022·102), 세션 고정 해시 불일치(GD103), 역할 ∉ signerSet(GD104), 정정본에 옛 토큰 → 거부 |
| G2 | `SignRulesAsDataIT` | 같은 빌드로 룰 데이터 4종 교체: `PARALLEL`, `managerConfirmMode=OFF`(+signerSet 2자), `channels.PAPER_SCAN.enabled=false`, `identityCheck` 수단 교체 → 동작 변경, 코드 diff 0 |
| G3 | `SignSessionIT` | 재사용·만료·취소·해시 불일치·모르는 토큰 거부(세션 불변·`SIGN_SESSION_DENIED`), `maxFailures` 도달 → REVOKED + 플래그, 토큰 원문 DB·로그 0(센티널 토큰 스캔) |
| G4 | `PlaintextLeakScanIT` 확장 | 센티널 생년월일로 본인확인 성공·실패 → 로그·감사·예외·DB·결과 XML에 0 |
| G5 | `SignatureEvidenceEncryptionIT` | 버킷 원시 바이트에 PNG 시그니처(`89 50 4E 47`)·좌표 문자열 없음, 복호화 일치, AAD 교차 실패, 키 파기 후 불가 |
| G6 | `CompletionIT` | SEQUENTIAL 순서 위반 거부, 마지막 서명과 같은 트랜잭션에서 COMPLETED(중간 실패 주입 시 서명도 롤백), OFF 테넌트 2자 완료, 종이 스캔 검토 전 미완료 |
| G7 | `SignedPdfGoldenTest` + CI | 접두 동일, 2회 동일, 골든 SHA-256(로컬 생성·CI 일치), CI `pdfa-verify`에 서명본 골든 추가 |
| G8 | `EvidencePackageTest` + `EvidenceManifestSchemaTest` | 스키마 통과, 엔트리 해시 전부 일치, 2회 바이트 동일, 스트로크·이미지 원본 미포함 |
| G9 | `ExpireJobIT` | 기한 끝 D 당일·다음날 경계, 세션 일괄 REVOKED, 만료 문서 서명 거부 |
| G10 | `LifecycleIT` 확장 | 서명 진행 중 VOID·SUPERSEDE → OPEN 세션 REVOKED, 기존 서명 보존, 새 버전 서명 0, 사유 코드·텍스트 컬럼 write-once |
| G11 | `ProxyDetectionTest`(단위) + IT 1건 | 같은 지문·같은 KST 날 고객 2명 → 플래그, 날짜 경계 다음날 → 없음, TOUCH_PAD 제외, `t < M` → 플래그, 임계치 데이터 교체 |
| G12 | `RetentionAnchorIT` | 완료 시 연장, 앵커 목록 교체로 동작 변경, 단축 시도 GD094, 재적용 `retention_applied_until` 증가만 |
| G13 | `GateFunctionTest` | `gateRequiresManager` 양쪽 × 상태 × 서명 조합 |
| G14 | 빌드 로그·보고서 | 주입: 트리거 PDF 해시 대조 제거, 본인확인 입력값을 감사에 기록, 세션 1회 사용 해제, 증분 대신 재생성 + V8 트리거별·세션 상태표 주입 |

- 추가로 `SessionStateTableTest`(설계서 블록 ↔ EnumMap 양방향)와 `OutboxContractTest`(적재 payload가 계약 스키마 통과, seq 갭 0)를 둔다.

---

## 9. 질문 (권장안 먼저)

**Q1. D1(고정 룰 로드 완화) 유지 여부.** 권장: 유지(§0.1). 되돌린다면 Q12.

**Q2. 서명 증거 객체를 `signature` 컬럼(`stroke_key`·`scan_key` …)이 아니라 별도 테이블 `signature_evidence`에 둔다.**
- 이유
  - TOUCH_PAD는 스트로크·이미지 두 객체다.
  - 객체마다 보존 재적용 추적(`retention_applied_until`)이 필요하다.
  - gc·reconcile이 `document_artifact`와 같은 형태로 처리한다.
  - `signature`가 append-only라 객체별 적용 기록을 그 행에 쓸 수 없다.
- 대안: 지시문대로 `signature`에 컬럼을 두고, 적용 기록은 별도 테이블에 둔다.

**Q3. 설계사·관리자 서명의 채널 = `SSO`(새 값).** 설계 §6.5가 SSO 귀속을 말하지만 채널 어휘(`TOUCH_PAD|REMOTE_LINK|PAPER_SCAN|CERTIFIED_ESIGN`)에 맞는 값이 없다. 이벤트 계약 `signatureChannel` enum에 `SSO`를 추가한다(소비자 없음, `CHECKSUMS` 갱신). 대안: 설계사 터치 서명은 `TOUCH_PAD`, 승인 클릭은 별도 값.

**Q4. 토큰 형식 `{tenantId}~{256비트}`.** 공개 엔드포인트가 RLS 바인딩 전에 테넌트를 알아야 한다. 대안: 테넌트 무관 토큰 조회 테이블을 직접 접근 허용 목록 항목(전용 롤)으로 둔다. 허용 목록이 늘고, "테넌트 데이터를 읽지 않는다" 조건과 부딪힌다.

**Q5. `channels` 값을 객체 `{enabled, requiresManagerReview}`로 바꾼다.** 지시문의 `channels.PAPER_SCAN.requiresManagerReview`와 G2의 `channels.PAPER_SCAN=false`가 같은 키를 boolean·객체로 동시에 요구한다. 대안: `channels`는 boolean 그대로 두고 `paperScan.requiresManagerReview`를 별도 키로.

**Q6. 증거 패키지 빌더를 `disclosure-seal`에 둔다.** 설계 §3.3은 "sign = 증거 패키징"이지만 매니페스트에 JCS·SHA-256이 필요하고, Phase 1 규칙이 Sign → platform-canonical을 막는다. 대안: 그 규칙을 넓힌다(규칙 의미 변경이라 승인 대상).

**Q7. `gateRequiresManager`의 출처를 룰 데이터 하나로.** 지금 룰 body와 `tenant.params`(V1 기본값) 두 곳에 있고, 코드는 룰만 읽는다. 권장: 룰 키를 `tenantOverridable`에 넣어 테넌트가 사규로 정하게 하고(D-9 "테넌트가 정한다"), `tenant.params`의 키는 쓰지 않는다고 설계서에 적는다.

**Q8. IP 재사용 지표는 선택 키 `sameIpDistinctCustomersPerDay`로 분리하고 기본 끔.** 이동통신 NAT·사내망에서 서로 다른 고객이 한 IP를 공유한다. 지시문 "기기 지문·IP 재사용 횟수/일"을 하나의 임계치로 묶으면 오탐이 크다.

**Q9. 관리자 확인의 "사유 확인 체크" 대상 = 그 확인서에 걸린 플래그 전부(열림·닫힘).**
- 봉인 시 오버라이드 플래그는 `APPROVED`로 닫혀 있다. 그러나 관리자가 확인할 예외 사실은 그대로다(산출불가·임시등록·오버라이드·종이 스캔·대리 서명 의심).
- 대안: 대상 플래그 유형 목록을 룰 데이터로 둔다.

**Q10. 종이 스캔 관리자 확인의 경로.**
- `review` 테이블은 봉인 전에만 INSERT된다(GD080). 그래서 봉인 후 종이 스캔 검토는 `review`에 쓸 수 없다.
- 권장: 플래그 `PAPER_SCAN_REVIEW`의 해소(해소자·사유·감사)로 기록한다.
  - MANAGER가 signerSet에 있으면 관리자 확인의 acknowledge가 해소한다.
  - 없으면(`OFF`) `exceptionApproval.role`의 `ReviewPaperScan`이 해소한다.
- 완료는 해소 뒤 `COMPLETE` 명령이다.

**Q11. `managerConfirmMode = OPTIONAL`의 의미.** signerSet에 MANAGER가 없으므로 고객·설계사 서명으로 바로 완료된다. 완료 뒤에는 서명 INSERT가 불가하다(§5 불변식).
- 권장: OPTIONAL = PARTIALLY_SIGNED 동안 관리자가 확인할 수 **있다**(R-SIGNER-SET이 이미 "집합 밖 역할 서명 허용", GD104도 같이). 완료 뒤 확인은 없다.
- 대안: OPTIONAL이면 마지막 필수 서명 뒤 관리자 확인 대기 시간을 룰로 둔다(복잡도 증가).

**Q12. (Q1에서 D1을 되돌릴 때만) 소급 GLOBAL 배포로 고정 룰이 상담일에 더는 시행 중이 아닌 초안.** 그 초안에는 재기준 외 모든 명령을 업무 거부(`RULE_SUPERSEDED`)로 응답하게 하고, 로더 검사는 원래대로 둔다.

**Q13. 아웃박스 이벤트 범위.** 지시문은 `SignatureCaptured`·`DisclosureCompleted`만 요구한다. 권장: 이 둘만 적재하고, `DisclosureSealed`·`Voided`·`Superseded`·`ComplianceFlagRaised`는 피드를 여는 Phase 6에서 같은 테이블로 추가한다(피드 전에는 소비자가 없어 누락이 관찰되지 않는다). 대안: 지금 전부 적재한다.

---

## 10. 순서 (승인 후)

1. **문서**: 계획 갱신(승인 반영). 설계서 v1.9 초안 절(§5 V8·§6.1·§6.5 세션 표·PAdES 아님 문장·§6.6 사유 코드·§9 증거 암호화·§4.5 아웃박스)은 각 코드 커밋과 같은 커밋으로.
2. **V8** + `db-error-codes.md` + 트리거·CHECK 전수 IT + 주입 기록.
3. **룰·서식 데이터**: 스키마·번들 재해시, `EffectiveRule` 접근자, `RuleAsDataIT`.
4. **sign 순수 규칙**: 세션 상태표(+설계서 블록), 토큰, 본인확인 정책, 대리 서명 탐지, 게이트, 보존 앵커.
5. **서명 외관·서명본·증거 패키지**: `SignedPdfAppender`, 골든 서명본, `pdfa-verify` 확장, 매니페스트 스키마·빌더.
6. **저장·암호화**: 증거 객체 암호화(문서 키 재사용), `signature_evidence` 저장소, reconcile·gc 확장.
7. **유스케이스**: 세션·본인확인·고객 서명·설계사·관리자·종이 스캔·완료, 아웃박스.
8. **만료·무효·정정 연동**, 사유 코드 이관.
9. **CLI·데모·통지**, 데모 2회 기록.
10. **보고서** `docs/phase-04-보고서.md`(지시문 추가 4항목 포함), PR, CI 1차 증거(`gh run view`), 태그 `phase-4`.
