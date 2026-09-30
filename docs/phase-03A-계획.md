# Phase 3A 계획 — 워크플로 코어 (승인 요청)

> 지시문 `docs/phase-03A-지시문.md` v1.0, 심사 회신 `docs/phase-02-수용심사.md`. 브랜치 `work/phase-3A`(main `43b2f80`에서 분기), 태그 `phase-3A`.
> 판단이 필요한 항목은 §10에 모았다(Q1~Q7). 나머지는 이 계획대로 진행한다.

## 1. 선행 소과제 A~D

### A. 계약 PR (`work/contract-1.2.0`, 3A 본 작업보다 먼저 병합)

| 파일 | 변경 |
|---|---|
| `contracts/api/v1/engine-disclosure.openapi.yaml` → **1.2.0** | 요청 `productKey`에 `maxLength: 40`과 패턴(Q1), `insurerCode`에 같은 보험사 코드 패턴. E3 요청 §6-1·2·3(추가형): 422 `INVALID_POLICY`, GET 500 `SNAPSHOT_INTEGRITY`, 400 `AS_OF_IN_FUTURE`·`UNKNOWN_PRODUCT_GROUP`. `reason` 설명의 예시에서 `TEMP_PRODUCT`를 빼고 "임시등록 상품은 요청하지 않는다. 소비자가 로컬에서 `UNAVAILABLE(TEMP_PRODUCT, source=LOCAL)`로 표기하고, 응답 집합 = 요청 집합 검사는 요청한 항목에만 적용"을 적는다. E3 요청 §6-4(GET 403 변경)는 의미 변경이라 넣지 않는다. |
| `contracts/catalog/v1/catalog-file.schema.json` | `productKey`·`insurerCode` 같은 규칙 |
| `contracts/CHECKSUMS`, `ContractSchemaTest` | 체크섬 재생성. 41자 키·패턴 위반 키 거부, 40자 키 통과, 새 오류 응답 존재 |
| `docs/설계서.md` §4.1·§4.2 | 위 규칙(같은 커밋 원칙) |

버전은 1.2.0으로 올린다. 요청 스키마를 좁히는 변경이지만 40자를 넘는 키를 보내는 생산자가 없다. 엔진 원장의 상품 키가 이미 40자이고(외부 키 매핑 컬럼만 129자로 잡혀 있음), 데모 키는 최대 14자다. 병합 후 엔진 쪽 후속(UPSTREAM을 이 병합 커밋으로 갱신, 40자 검증, `TEMP_PRODUCT` 데이터 제거)은 E3 보고서 §10.2에 적었고, E3 심사 뒤에 한다.

### B. DEK 최초 생성 멱등화 (Phase 2 D3)

- **원인**: `CustomerVaultRepository.createKey`가 평범한 INSERT다. 동시에 들어온 두 첫 등록이 둘 다 `activeKey()`에서 빈 결과를 보고, 한쪽이 `ux_customer_data_key_active`에 걸려 `23505`로 실패한다.
- **수정**:
  - `INSERT … ON CONFLICT (tenant_id) WHERE status = 'ACTIVE' DO NOTHING RETURNING key_id`를 쓴다. 0행이면 다시 조회해 이긴 쪽의 키를 쓴다.
  - READ COMMITTED에서는 진 쪽이 유일 인덱스에서 이긴 쪽의 커밋을 기다린 뒤 DO NOTHING이 되고, 재조회가 커밋된 행을 본다. 어드바이저리 락은 쓰지 않는다.
  - 진 쪽이 감싼 DEK는 버려진다. KMS 호출 1회가 낭비되는 정도다.
  - 순환(rekey) 경로는 이미 ACTIVE 행을 먼저 RETIRED로 바꾸므로 영향이 없다. 그래도 같은 테스트에 순환 중 등록 케이스를 넣는다.
- **테스트**: `CustomerEncryptionIT.firstRegistrationsAreIdempotentUnderConcurrency` — 새 테넌트에서 50건을 `CountDownLatch`로 동시에 시작한다. 확인 항목은 다음과 같다.
  - 실패 0건.
  - `customer_ref` 50행.
  - ACTIVE 키 정확히 1개.
  - 50행 모두 그 키를 쓴다.
  - 감사 `CUSTOMER_REGISTER` 50행.
- **주입 기록**: `ON CONFLICT`를 빼면 실패해야 한다.

### C. 설계서 v1.7·CLAUDE.md

수용심사 §4의 1~7을 그대로 반영하고, 이 계획에서 생기는 변경을 더한다(§9).

### D. PDF/A 변환기 후보 비교 — 실측 완료 (§7)

## 2. 애그리게이트 경계

| 안(`Disclosure` 애그리게이트) | 밖 | 근거 |
|---|---|---|
| 헤더: id·테넌트·설계사·`customerRef`·상품군·상담일·고정된 룰/서식 버전 ID 4개·`issuerMode`·상태 | 룰·서식 **본문**(`EffectiveRule`, `TemplateResolution`) — 유스케이스가 고정 ID로 로드해 인자로 넘긴다 | 룰은 테넌트 공유 데이터이고 확인서는 ID만 박제한다. 애그리게이트가 저장소를 부르지 않는다 |
| 항목 목록(`item_no` 순서), 항목별 등급 복사본(`ItemGrade`: ENGINE 또는 LOCAL) | 카탈로그 조회 — 유스케이스가 기본값을 채운 `ItemDraft`를 넘긴다 | 카탈로그는 외부 정본의 캐시다 |
| 추천사유(항목별 코드·텍스트) | 고객 PII(`customer_ref`만 가진다) | 규칙 6. 애그리게이트는 복호화 경로가 없다 |
| 스냅샷 요약(snapshotId·정책 2종·`tieBreak`·`basis` 원문·`generatedAt`) | 엔진 호출(`GradeSnapshotPort`) — 애그리게이트는 검증을 마친 `GradeSnapshot`만 받는다 | 스키마·정합성 검증이 끝난 스냅샷만 애그리게이트에 들어온다(W4) |
| 상태와 전이 메서드: `replaceItems`, `compare`, `applySnapshot`, `reason`(3B `seal`) | 예외 승인(`review`) — 별도 append-only 기록. 유스케이스가 조회해 `compare/applySnapshot/reason`에 "승인된 규칙 ID 집합"으로 넘긴다 | 승인은 다른 행위자(관리자)의 사실이며 삭제·수정이 없다. 확인서 행에 넣으면 봉인 불변과 엉킨다 |
| — | 준법 플래그·감사 | 부수 효과는 유스케이스가 같은 트랜잭션에서 기록한다 |

**배치**
- 상태표·명령 열거·`IllegalTransition`·항목·추천·스냅샷 값 타입은 `disclosure-domain`에 둔다.
- `Disclosure` 클래스는 `disclosure-workflow`에 둔다. 모듈 방향이 domain ← rules ← workflow이므로, `ValidationSubject`(rules)를 구현하려면 workflow에 있어야 한다. workflow도 Spring 무의존을 유지한다.
- `isInsurerOnPanel`은 생성 시 받은 `InsurerPanelPort` 함수로 판단하고, `largeGa`는 생성 시 받은 테넌트 플래그를 쓴다.

**상태 변경 경로가 하나뿐임을 강제**
- 상태 필드는 private이고 setter가 없다.
- 저장소 포트 `DisclosureStore`는 `insert(Disclosure)`, `loadForUpdate(id)`, `save(Disclosure)`만 가진다.
- SQL 스캔 테스트가 `UPDATE disclosure`·`UPDATE disclosure_item`·`DELETE FROM disclosure_item` 문장을 `DisclosureRepository.save` 한 곳에서만 허용한다. 허용 목록은 FQN 열거로 둔다.

## 3. 상태표 (EnumMap, 설계서 §6.1에 기계 판독 표로 추가)

명령 = `REPLACE_ITEMS, COMPARE, APPLY_SNAPSHOT, SET_RECOMMENDATIONS, SEAL, SIGN, COMPLETE, EXPIRE, VOID, SUPERSEDE`. 3A는 앞의 넷만 구현하고, 나머지는 표에만 있다.

| 상태 | 허용 명령(→ 결과 상태) |
|---|---|
| DRAFT | REPLACE_ITEMS(DRAFT), COMPARE(COMPARED), VOID |
| COMPARED | REPLACE_ITEMS(COMPARED), APPLY_SNAPSHOT(GRADED), VOID |
| GRADED | REPLACE_ITEMS(→COMPARED, 스냅샷·사유 폐기), APPLY_SNAPSHOT(GRADED, 재산출 — 사유 폐기, Q5), SET_RECOMMENDATIONS(REASONED), VOID |
| REASONED | REPLACE_ITEMS(→COMPARED), APPLY_SNAPSHOT(→GRADED, Q5), SET_RECOMMENDATIONS(REASONED), SEAL(SEALED), VOID |
| SEALED | SIGN(PARTIALLY_SIGNED·COMPLETED), EXPIRE, VOID, SUPERSEDE |
| PARTIALLY_SIGNED | SIGN, COMPLETE, EXPIRE, VOID, SUPERSEDE |
| COMPLETED | VOID, SUPERSEDE |
| EXPIRED | VOID, SUPERSEDE("재발급만 가능") |
| VOID, SUPERSEDED | 없음(종결) |

- **W1**: `DisclosureStateTableTest`는 설계서 §6.1에 새로 넣는 표(상태 10 × 명령 10)를 **파일에서 파싱해** EnumMap과 대조한다. 그리고 표 밖 조합 전수에서 `IllegalTransition(from, command)`를 확인한다(구현된 네 명령). 문서와 코드가 따로 움직이면 테스트가 실패한다.
- **상담일 변경 명령은 없다**: `consultDate`는 생성자에서만 정한다. 이 필드에 쓰는 메서드가 없다는 것을 리플렉션 테스트로 확인한다.

## 4. 유스케이스·트랜잭션·감사 규약 (W7)

유스케이스: `CreateDraft`, `ReplaceItems`, `RequestGrades`, `SetRecommendations`, `Validate(stage)`, `ApproveException`, `RegisterCustomer`.

- **`CreateDraft`**:
  - 상담일로 GLOBAL·TENANT 룰과 서식을 한 번 해석한다.
  - `rule_version_id`·`tenant_rule_version_id`·`template_id`·`template_version`을 헤더에 고정한다(설계서 "봉인 시 확정" → "초안 생성 시 확정"으로 고친다).
  - 이후 유스케이스는 `RuleResolver.load(tenant, asOf, globalId, tenantId?)`·`TemplateResolver.load(tenant, templateId, version)`(새 메서드, 포트에 `findById` 추가)로 **고정 ID를 로드**하고 재해석하지 않는다.
- **전이 조건**: `compare/applySnapshot/reason`은 해당 단계 검증 결과를 받는다. 통과하지 못한 규칙이 하나라도 있으면 전이하지 않는다. `overridable` 실패의 처리는 Q2다.

**감사 실패 기록 규약 (계획에서 정하라는 항목)**

| 종류 | 예 | 처리 |
|---|---|---|
| **업무 거부** — 예상된 결과 | 단계 검증 실패, 엔진 응답의 스키마·정합성 위반 | 업무 트랜잭션이 **정상 커밋**한다. 감사 행(`VALIDATE` 결과 전체 / `GRADE_REJECTED` 위반 목록)과 플래그(`GRADE_INCONSISTENT`)가 남는다. 상태는 불변이고, 호출자에게는 결과 객체(실패 목록)를 돌려준다 |
| **명령 거부·오류** — 예외 | `IllegalTransition`, 룰 해석 실패, 엔진 연결 실패·422·5xx, 예기치 않은 오류 | 업무 트랜잭션은 **롤백**한다. 그 뒤 **별도 트랜잭션**으로 `COMMAND_FAILED` 감사 1행을 남긴다(명령·대상·예외 종류·오류 코드). 메시지는 넣지 않는다 — 규칙 6, 예외 메시지에 입력값이 섞일 수 있다. 실패 기록 자체가 실패하면 원 예외에 suppressed로 붙이고 ERROR 로그를 남긴다 |

- **W7 증명**(`WorkflowAuditIT`):
  - 모든 유스케이스 성공 경로에서 업무 행과 감사 행이 같은 트랜잭션에 있다. 감사 INSERT 직후 예외를 주입하면 업무 행도 없다.
  - 거부 경로에서 상태 불변 + 감사 행이 있다.
  - 예외 경로에서 업무 행·업무 감사 행은 없고, `COMMAND_FAILED` 1행이 있다.
  - 체인 검증도 통과한다.

## 5. 엔진 클라이언트와 테스트 대역

```
GradeSnapshotPort (workflow)
  └─ EngineGradeClient (infra)  — 요청 JSON 생성 → EngineTransport → 응답 바이트
        ├─ ① 계약 스키마 검증(networknt, 계약 YAML의 components.schemas를 클래스패스에서 로드, oneOf 분기 포함)
        ├─ ② 매핑(RatioLabel로만) → GradeSnapshot
        └─ ③ GradeConsistencyCheck(임시등록 제외 집합)       ①·③ 중 하나라도 실패 → GradeRejected(위반 목록)
  EngineTransport
        ├─ HttpEngineTransport: JDK HttpClient(추가 의존성 없음). 서비스 토큰 헤더(값은 로그·예외에 넣지 않음)
        │   base URL = tenant.engine_base_url. 연결 타임아웃·요청 타임아웃·최대 시도는 설정값
        │   재시도는 ConnectException·연결 타임아웃에만. 요청 전송 후의 타임아웃·응답 수신 후 오류는 재시도하지 않는다
        └─ (testFixtures) FakeEngine
```

- **`FakeEngine`**(infra testFixtures):
  - **받은 요청을 계약 요청 스키마로 검증**한다. 임시등록 상품이나 41자 키가 오면 테스트가 실패한다.
  - 정상 응답도 계약 응답 스키마로 **자기 검증한 뒤** 돌려준다. 의도치 않게 틀린 응답을 내면 페이크가 먼저 실패한다.
  - 결함 주입은 명시적 모드로만 한다: UNAVAILABLE에 `rankInSet`, 비단조 순위, 요청에 없는 상품, 빠진 상품, 미허용 `tieBreak`·정책, 422, 연결 거부, 응답 지연.
  - `GradeSnapshotIT`는 `HttpServer`(JDK)에 페이크를 올려 **실제 HTTP 어댑터**까지 통과시킨다. 재시도 규칙도 여기서 증명한다: 연결 거부 → 재시도 / 응답 후 지연 → 재시도 없음(요청 수 카운트).
- **데모 엔진 스텁**:
  - `infra.engine.stub.TableEngineStub`(main, 데모 프로파일에서만 빈으로 올린다)을 페이크가 감싸 재사용한다.
  - 데이터 파일(`demo/engine-table.json`)의 **상품키 → {ratioToAvg 문자열, grade, gradeLabel, gradeOrdinal, rankKey 정수}** 고정표에서 응답을 만든다.
  - **비율 문자열에서 등급을 계산하지 않는다**(규칙 1: 비율 비교·분류 금지). 순위는 표의 정수 `rankKey`로 매긴다. 표에 없는 키는 `UNAVAILABLE(NOT_IN_GROUP)`이다. 지시문의 "고정 비율표에서 등급을 낸다"를 이렇게 해석한다.
- **`GRADE_INCONSISTENT`**:
  - 플래그 대상은 `target_kind='DISCLOSURE'`다. 같은 확인서의 열린 플래그는 하나이고(V5 부분 유일 인덱스 재사용), 거부마다 감사 행이 남는다.
  - 422·연결 실패는 엔진 오류이므로 플래그 없이 `COMMAND_FAILED`로 남는다.
- **트랜잭션**(Q6): 읽기(짧은 트랜잭션) → 트랜잭션 밖에서 엔진 호출 → 쓰기 트랜잭션에서 순서대로 다음을 한다.
  1. `SELECT … FOR UPDATE`.
  2. 보낸 항목 지문(요청 상품키 목록의 해시)이 지금 항목과 같은지 본다. 다르면 `GRADE_STALE` 거부.
  3. 스냅샷을 적용한다.
  4. `GRADE_FETCH` 감사를 남긴다.

## 6. V6 DDL 초안 (`V6__workflow_core.sql`)

```sql
-- disclosure: 스냅샷 헤더 복사본(본문 컬럼 — V3 메타 목록 밖이므로 자동으로 봉인 후 불변)
ALTER TABLE disclosure
    ADD COLUMN grading_policy_version_id TEXT,
    ADD COLUMN ranking_policy_version_id TEXT,
    ADD COLUMN tie_break                 TEXT,
    ADD COLUMN grade_basis               JSONB,
    ADD COLUMN snapshot_generated_at     TIMESTAMPTZ,
    ADD CONSTRAINT ck_disclosure_status CHECK (status IN ('DRAFT','COMPARED','GRADED','REASONED','SEALED',
        'PARTIALLY_SIGNED','COMPLETED','VOID','SUPERSEDED','EXPIRED')),
    ADD CONSTRAINT ck_disclosure_tie_break CHECK (tie_break IS NULL OR tie_break IN ('SHARED_RANK','STRICT')),
    -- 스냅샷 헤더 5개 + snapshotId는 함께 있거나 함께 없다
    ADD CONSTRAINT ck_disclosure_snapshot_header CHECK (
        (grade_snapshot_id IS NULL) = (grading_policy_version_id IS NULL) AND (grade_snapshot_id IS NULL) = (ranking_policy_version_id IS NULL)
        AND (grade_snapshot_id IS NULL) = (tie_break IS NULL) AND (grade_snapshot_id IS NULL) = (grade_basis IS NULL)
        AND (grade_snapshot_id IS NULL) = (snapshot_generated_at IS NULL)),
    ADD CONSTRAINT ck_disclosure_basis_object CHECK (grade_basis IS NULL OR jsonb_typeof(grade_basis) = 'object'),
    -- 산출 전 상태에는 스냅샷이 없다(항목 변경 → COMPARED 회귀 시 폐기의 DB 쪽 이중화)
    ADD CONSTRAINT ck_disclosure_snapshot_state CHECK (grade_snapshot_id IS NULL OR status NOT IN ('DRAFT','COMPARED'));

-- disclosure_item
ALTER TABLE disclosure_item
    ADD COLUMN tie                BOOLEAN,
    ADD COLUMN unavailable_reason TEXT,
    ADD COLUMN grade_source       TEXT,
    ADD CONSTRAINT ck_item_temp_identity CHECK (temp_product = (product_key IS NULL)),                 -- Q4
    ADD CONSTRAINT ck_item_temp_quote    CHECK (temp_product = (quote_doc_no IS NOT NULL)),
    ADD CONSTRAINT ck_item_grade_status  CHECK (grade_status IS NULL OR grade_status IN ('OK','UNAVAILABLE')),
    ADD CONSTRAINT ck_item_grade_source  CHECK (grade_source IS NULL OR grade_source IN ('ENGINE','LOCAL')),
    ADD CONSTRAINT ck_item_ungraded CHECK (grade_status IS NOT NULL OR (grade IS NULL AND grade_label IS NULL
        AND grade_ordinal IS NULL AND rank_in_set IS NULL AND ratio_to_avg IS NULL AND tie IS NULL
        AND unavailable_reason IS NULL AND grade_source IS NULL)),
    ADD CONSTRAINT ck_item_ok CHECK (grade_status IS DISTINCT FROM 'OK' OR (grade IS NOT NULL AND grade_label IS NOT NULL
        AND grade_ordinal >= 1 AND rank_in_set >= 1 AND ratio_to_avg IS NOT NULL AND tie IS NOT NULL
        AND unavailable_reason IS NULL AND grade_source = 'ENGINE')),
    ADD CONSTRAINT ck_item_unavailable CHECK (grade_status IS DISTINCT FROM 'UNAVAILABLE' OR (grade IS NULL
        AND grade_label IS NULL AND grade_ordinal IS NULL AND rank_in_set IS NULL AND ratio_to_avg IS NULL
        AND tie IS NULL AND unavailable_reason IS NOT NULL AND grade_source IS NOT NULL)),
    -- 임시등록이 산출되면 로컬 산출불가뿐, 로컬 출처는 임시등록뿐
    ADD CONSTRAINT ck_item_temp_grade CHECK (NOT temp_product OR grade_status IS NULL
        OR (grade_status = 'UNAVAILABLE' AND grade_source = 'LOCAL' AND unavailable_reason = 'TEMP_PRODUCT')),
    ADD CONSTRAINT ck_item_local_is_temp CHECK (grade_source IS DISTINCT FROM 'LOCAL' OR temp_product);

-- 예외 승인 기록(append-only). 인가는 Phase 6
CREATE TABLE review (
    tenant_id     TEXT NOT NULL,
    review_id     UUID NOT NULL,
    disclosure_id UUID NOT NULL,
    rule_id       TEXT NOT NULL,
    subject_hash  TEXT NOT NULL CHECK (subject_hash ~ '^[0-9a-f]{64}$'),    -- Q3: 승인 대상의 JCS SHA-256
    approved_by   TEXT NOT NULL,
    approved_at   TIMESTAMPTZ NOT NULL,
    reason        TEXT NOT NULL CHECK (btrim(reason) <> ''),
    PRIMARY KEY (tenant_id, review_id),
    FOREIGN KEY (tenant_id, disclosure_id) REFERENCES disclosure (tenant_id, disclosure_id)
);
CREATE INDEX ix_review_target ON review (tenant_id, disclosure_id, rule_id);
-- RLS(V2와 같은 정책), UPDATE·DELETE·TRUNCATE 거부(GD030), 부모가 가변 상태가 아니면 INSERT 거부(GD080, 신규)

-- 고객 등록 멱등 키(Q7)
ALTER TABLE customer_ref ADD COLUMN registration_key TEXT
    CHECK (registration_key IS NULL OR registration_key ~ '^[A-Za-z0-9._:@#-]{1,128}$');
CREATE UNIQUE INDEX ux_customer_ref_registration ON customer_ref (tenant_id, registration_key) WHERE registration_key IS NOT NULL;
```

- 봉인 컬럼(`disclosure_no`·`canonical_hash`·`pdf_hash`·`chain_*`·`sealed_at`)은 3B까지 쓰지 않는다. 상태와 묶는 CHECK는 3B에서 둔다(지금 두면 C7 매트릭스의 본문 UPDATE 케이스와 섞인다).
- **W8**:
  - `ImmutabilityTriggerIT`: BODY 맵에 V6 헤더 5개 컬럼을 추가한다. 분류 누락 검사가 이미 강제한다. 자식 트리거는 컬럼 무관이므로, 새 항목 컬럼은 매트릭스의 UPDATE 케이스를 새 컬럼으로 1건 더 두어 확인한다.
  - `SnapshotColumnCheckIT`: 위 CHECK마다 위반 조합을 전수로 거부하고 허용 조합을 통과시킨다. 항목은 grade_status 3값 × source 3값 × temp 2값 × tie/reason 유무 조합을 생성해 기대값을 독립 계산한다. 헤더는 5개 컬럼의 부분 NULL 조합을 넣는다.
  - review는 GD030·GD080·RLS를 확인한다.

## 7. PDF/A 변환기 후보 비교 (선행 D, 선택은 3B 지시문)

**방법**
- 같은 XHTML 1개를 쓴다: 한글 제목·표·굵은 글씨·자모 검사 문자열("똠방각하 뷁"). 폰트는 NanumGothic Regular/Bold(OFL-1.1, google/fonts)를 `@font-face`로 참조한다.
- 후보마다 **별도 프로세스로 2회** 변환해 SHA-256과 바이트를 대조한다.
- PDF/A 준수는 veraPDF 1.30.2(PDF/A-2b 프로파일)로 검증한다. 폰트 임베드와 텍스트 추출은 PDFBox로 확인한다.
- 실험 코드는 스크래치에 있다. 3A에서 `verification/pdf-candidates/`(독립 빌드, 메인 빌드 미포함, 폰트는 SHA-256 검증 후 내려받기)로 옮겨 재현 가능하게 한다.

| 후보 | 라이선스 | 실행 형태 | PDF/A-2b (veraPDF) | 한글 임베드 | 2회 비교 — 기본 | 2회 비교 — 메타 고정 |
|---|---|---|---|---|---|---|
| **openhtmltopdf 1.1.87** (PDFBox 3.0.7) | LGPL-2.1 + Apache-2.0 | JVM 라이브러리 | **준수**(실패 0) | 서브셋 Type0 2종, 추출 OK | 불일치(XMP `CreateDate`, 트레일러 `/ID`) | **일치** — `2c26ffef…a9c0` ×2 (Info·XMP 날짜 고정 + `PDDocument.setDocumentId`) |
| Flying Saucer 10.5.0 + OpenPDF 3.0.5 | LGPL-2.1 / MPL-2.0 | JVM 라이브러리 | **비준수**(3건: XMP 없음 6.6.2.1, 출력 의도 6.2.4.3, 폰트 6.2.11.4.2) — `PDFA2B`를 설정해도 PDF/A 식별 메타가 생기지 않음 | 임베드 OK | 불일치(`CreationDate`, `/ID`) | 불일치 — `/ID`가 시간 기반이고 공개 API로 고정할 수 없음(후처리 재저장 필요) |
| Gotenberg 8 (Chromium 152 + LibreOffice 26.8) | MIT(서비스) | 별도 컨테이너(HTTP) | **비준수**(기본 1건 6.2.11.5, 메타 주입 시 3건) | 업로드 파일이 평탄화돼 `fonts/` 경로를 못 찾음 → 컨테이너의 Noto CJK로 대체되고 Type1로 재임베드(같은 HTML 조건 불충족) | 불일치(폰트 스트림 64KB 차이) | 불일치(폰트 스트림·`/ID`) |
| **WeasyPrint 70.0** | BSD-3 | Python 프로세스(사이드카) | **준수**(실패 0) | 서브셋 Type0 2종, 추출 OK | **일치** — 날짜를 기록하지 않고 `/ID`가 내용 기반 | 일치(`--pdf-identifier` 지정 시에도) |
| iText 9 pdfHTML | AGPL-3.0 / 상용 | JVM | 미측정 | — | — | — (저장소 MIT와 충돌, 조건 탈락) |

**3B 판단 재료**
1. JVM 안에서 끝나는 후보는 openhtmltopdf 하나다. 결정론을 얻으려면 3곳을 고정해야 한다: 문서 정보 날짜, XMP 날짜, `/ID` 시드. 셋 다 봉인 입력(canonical 해시·확인서 번호)에서 파생하면 "같은 입력 → 같은 바이트"가 된다.
2. WeasyPrint는 기본값으로 결정론·준수를 둘 다 만족한다. 대신 운영에 Python·Pango 런타임과 프로세스 경계가 생긴다(p95 3초 목표·장애 격리 측면의 비용).
3. **미검증 사항**:
   - 다른 OS·JDK에서도 같은 바이트가 나오는지 확인하지 않았다(이번 실험은 macOS arm64·Temurin 25 한 곳). 3B에서 CI(Linux)와 로컬 해시를 대조해야 한다.
   - openhtmltopdf 경로의 sRGB ICC 프로파일은 이번에 JDK 내장값으로 넣었다. 3B에서는 고정 ICC 파일을 리소스로 둬야 JDK 차이를 없앤다.
   - Boot BOM 적용 시 의존성 겹침(commons-logging 등)은 스크래치에 BOM이 없어 확인하지 못했다. 3B에서 `checkBom`으로 확인한다.
4. 폰트는 OFL이라 저장소에 동봉할 수 있다(라이선스 파일 포함).

## 8. CLI·데모

- **고객**:
  - `disclosure-demo/src/main/resources/customers.json` — 명백히 허구인 값(이름 "가상고객01", 전화 010-0000-00xx, 생년월일 1900-01-0x)과 `registrationKey`(`demo:customers.json#C01`)를 둔다.
  - CLI `customer import --tenant T --file <path>`는 **파일 경로만** 받는다(규칙 6 추가 문장).
  - 두 번째 실행에서는 `registration_key` 충돌로 기존 ref를 반환한다(Q7).
- **확인서 흐름**: `demo disclosures --tenant T --file demo/disclosures.json`(데모 프로파일, `TableEngineStub`). 순서는 고객 → 초안 → 항목 3건 → 산출 → 사유 → REASONED이고, 두 케이스를 더 넣는다.
  1. 임시등록 1건 포함.
  2. 고객 요청 보험사 추가 → COMPARED 회귀 → 재산출.
  - 고객·상담일·상품군이 같은 확인서가 있으면 NOOP으로 넘어간다. 이 규칙은 **데모 편의이며 운영 동작이 아니라고** 주석과 README에 적는다.
- `scripts/seed.sh`에 두 명령을 더하고, 2회 실행 NOOP을 보고서 증거로 남긴다.

## 9. 설계서 v1.7 변경 목록 (3A PR 안에서)

- **수용심사 §4 반영**:
  - 1: §4.1·§4.2 — 키 규칙, 임시등록 미전송, 집합 검사 범위.
  - 2: §5 — `grade_source`, `customer_ref` DELETE 주석.
  - 3: §6.4·§9 — 성명 포함, 산출물 암호화, 파기 = 객체 삭제 + 키 파기.
  - 4: §12 — 3A/3B 분할.
  - 5: §4.4·§14 — UPSTREAM 규칙.
  - 6: 부록 B — `customers.json`.
  - 7: CLAUDE.md 규칙 6.
- **이 계획에서 추가**:
  - §5: V6 DDL과 CHECK, `review`, `registration_key`, 룰·서식 버전을 초안 생성 시 확정.
  - §6.1: 상태 × 명령 표(기계 판독, W1이 파싱).
  - §6.2: 감사 실패 기록 규약, `overridable` 처리(Q2).
  - §6.4 1항: "확정" → "초안에 고정된 버전 로드".
  - §4.1: 엔진 클라이언트 재시도·트랜잭션 경계.
  - 부록 B: 엔진 스텁 표 형식(비율 해석 없음).
  - `db-error-codes.md`에 GD080을 추가한다.

## 10. 판단이 필요한 질문

**Q1. 상품 키 패턴이 모든 데모·예시 키를 거부한다.** 결정된 패턴 `^[A-Z0-9]{1,8}:[A-Za-z0-9._-]{1,31}$`은 보험사 부분에 하이픈을 허용하지 않는다. 그런데 다음이 전부 `INS-A` 형식이다.
- 데모 카탈로그 9개 키.
- 설계서 §4.1 예시.
- 계약 예시.
- 엔진 픽스처.
- 이 저장소의 17개 파일.

또 결정된 패턴은 코드 부분이 `.`·`-`로 시작하는 것을 허용해, 지금 규칙보다 넓다.
- **권장**: `^[A-Z0-9][A-Z0-9-]{0,7}:[A-Za-z0-9][A-Za-z0-9._-]{0,30}$`, `maxLength: 40`. 보험사 부분 ≤ 8(하이픈 허용), 코드 부분 ≤ 31(첫 글자 영숫자). `InsurerCode`도 `[A-Z0-9][A-Z0-9-]{0,7}`로 좁힌다. 카탈로그 수입은 이미 키 접두 = `insurerCode`를 검사한다.
- 대안: 결정 패턴 그대로 쓰고 예시 키를 `INSA:PRD-1001` 형식으로 바꾼다. 데모 카탈로그·설계서·계약 예시·엔진 픽스처 수정이 따라온다.

**Q2. `overridable` 실패를 중간 단계에서 막을지.** 지시문은 COMPARE·GRADE·REASON 전이에서도 `review`가 있어야 넘어가게 한다. 그러면 임시등록 상품이 있는 확인서는 관리자 승인 전에 **산출 요청조차 못 한다**. 고객 앞 상담 중에 관리자를 기다리게 되는 셈이다. 이는 부록 A-2의 흐름("봉인은 오버라이드 승인 후")과 §10의 현장 완료 시간 목표와 어긋난다.
- **권장**: 중간 단계에서 `overridable` 실패는 전이를 막지 않는다. 대신 즉시 플래그(`TEMP_PRODUCT`·`GRADE_UNAVAILABLE`)를 올리고 감사에 남긴다. `review` 확인은 SEAL(3B)에서 강제한다. SEAL은 전 규칙을 다시 돌리므로 방어 강도는 같다.
- 이번 Phase는 `review` 테이블·`ApproveException`과 "SEAL 단계에서 승인 없는 overridable 실패 = 통과 불가" 판정 함수까지 두고 W5에서 증명한다.
- 지시문대로(중간 단계 차단) 가면 W5를 그대로 구현하며, 위 불편을 보고서에 기록한다.

**Q3. 승인이 무엇에 귀속되는가.** `review`가 (확인서, 규칙)에만 묶이면, 임시등록 상품 X를 승인받은 뒤 항목을 Y로 바꿔도 승인이 살아 있다.
- **권장**: `subject_hash`를 둔다. 값은 **실패 결과가 지목한 대상**의 JCS SHA-256이다.
  - R-TEMP-PRODUCT: 임시등록 항목들의 {보험사, 상품명, 발행번호}.
  - R-GRADE-UNAVAILABLE: 산출불가 항목들의 {항목, 사유, 출처}.
- 대상이 바뀌면 승인이 자동으로 무효가 된다. 재산출해도 같은 산출불가 집합이면 승인이 유지된다. "서명은 문서 해시에 귀속"과 같은 원리다.
- 이를 위해 `ValidationResult`에 overridable 실패의 대상(정규 JSON)을 싣는 필드를 추가한다(Phase 1 레코드 확장).

**Q4. 임시등록 항목의 상품 키.**
- **권장**: 임시등록 항목은 `product_key = NULL`로 둔다. V1이 이미 NULL 허용이고, "엔진 도메인에 없는 상품"이라는 결정과 맞다. CHECK `temp_product = (product_key IS NULL)`을 둔다.
- 이에 따라 `ValidationSubject.Item.productKey()`를 `Optional<ProductKey>`로 바꾸고 규칙 5곳을 고친다. 모두 임시등록 항목을 키 없이 표시·집계하도록 한다.
- 대안: 로컬 키(`{보험사}:TEMP-{발행번호}`)를 짓는다. 이 방식은 발행번호 길이가 40자 제한을 넘을 수 있고, 카탈로그 키와 형식상 충돌할 수 있다.

**Q5. 재산출 전이.** §6.1에는 스냅샷 교체(재산출)의 전이가 없다. 반면 스냅샷 노후 시 봉인 거부 → 재산출이 필요하고, §7도 "재산출 포함"이라고 적는다.
- **권장**: APPLY_SNAPSHOT을 COMPARED·GRADED·REASONED에서 허용하고 결과는 GRADED로 한다. **추천사유는 폐기**한다. 등급이 바뀌면 설명의 근거가 바뀌고, 이전 사유를 되살려 채우는 것은 규칙 7의 "시스템이 사유를 채우지 않는다"에 걸린다.

**Q6. 엔진 호출을 DB 트랜잭션 안에서 할지.**
- **권장**: 트랜잭션 밖에서 호출한다. 이어서 쓰기 트랜잭션에서 행 잠금 + 항목 지문 대조 + 적용 + 감사를 한다(§5).
- "각 유스케이스는 하나의 트랜잭션"을 **상태 변경·감사가 한 트랜잭션**으로 해석한다. HTTP 대기(최대 타임아웃) 동안 커넥션과 행 잠금을 쥐지 않기 위해서다.

**Q7. 고객 등록 멱등 키.**
- **권장**: `customer_ref.registration_key`(선택, 테넌트 내 유일, PII 아님)를 V6에 둔다. 데모 2회 실행 NOOP의 근거가 되고, Phase 6 등록 API의 `Idempotency-Key`로도 쓴다.
- 대안: 감사 detail의 출처로 조회한다. 조회 비용이 들고, 감사를 업무 키로 쓰게 된다.

## 11. 순서

1. **계약 PR**(선행 A): CI 통과 후 merge commit으로 병합하고 `work/phase-3A`에 반영한다.
2. `work/phase-3A`에서 다음 순서로 진행한다.
   1. B(DEK) — 테스트 먼저, 주입 기록.
   2. V6 + 도메인 상태표·값 타입 + W1.
   3. `Disclosure` 애그리게이트 + W2·W3.
   4. 유스케이스·감사 규약 + W5·W6·W7.
   5. 엔진 클라이언트·페이크 + W4.
   6. `RegisterCustomer`·데모 + W9.
   7. 설계서 v1.7·CLAUDE.md(C).
   8. `verification/pdf-candidates`(D).
   9. 전체 `clean build`, 위반 주입, 데모 2회, 보고서.
3. PR → CI → 보고서에 run ID를 직접 조회해 인용 → 태그 `phase-3A`. 병합은 심사 후에 한다.
