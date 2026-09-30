# Phase 3B 계획 — 봉인·정정·무효 (승인 요청)

기준: `docs/phase-03B-지시문.md` v1.0, `docs/phase-03A-수용심사.md` §3 결정 1~8, 설계서 v1.7 §5·§6.4·§6.6·§9. 브랜치 `work/phase-3B`(main `3eb77f5` = 3A PR #5 병합에서 분기).

지시문이 요구한 여섯 항목(①~⑥)은 §1~§6이다.
- 지시문과 다르게 가야 하는 지점이 하나 있다. **MinIO 이미지가 더는 공개 배포되지 않는다**(§6).
- 문언끼리 충돌하거나 해석이 필요한 지점은 §9 질문으로 모았다.
- 각 질문에 권장안을 적었다. 권장안대로 승인되면 이 문서를 한 커밋으로 갱신하고 §10 순서로 진행한다.

---

## 1. `render.bind` 어휘 (①)

**원칙**
- 코드는 항목 코드를 모른다. 분기 키는 `bind`(닫힌 enum)이다.
- `bind`마다 허용 `scope`와 `source`가 정해져 있다. 스키마가 `allOf`/`if-then`으로 강제한다.
- 렌더러와 `R-FIELD-REQUIRED`는 같은 `BindingResolver`로 값을 찾는다. "존재 판정"과 "인쇄 값"이 갈라질 수 없게 하려는 것이다.

| bind | scope | source | 값(인쇄) | 존재 판정(`R-FIELD-REQUIRED`) |
|---|---|---|---|---|
| `HEADER_DISCLOSURE_NO` | PER_DOCUMENT | SYSTEM | 확인서 번호(봉인 입력, canonical 밖) | 항상 존재. 봉인이 발급한다. 번호는 검증 뒤 채번되므로 검증 시점에는 "발급 예정"이다 |
| `HEADER_CONSULT_DATE` | PER_DOCUMENT | SYSTEM | `consultDate`(`yyyy-MM-dd` 원문) | 항상(NOT NULL 컬럼) |
| `HEADER_AGENT` | PER_DOCUMENT | SYSTEM | `agentId` | 항상 |
| `HEADER_CUSTOMER_NAME` | PER_DOCUMENT | SYSTEM | `customerName`(봉인 때 복호화) | 항상. 복호화 가능 여부는 봉인 조건 ⑥이 따로 본다(§3) |
| `HEADER_PRODUCT_GROUP` | PER_DOCUMENT | CATALOG | `productGroup.name` | 상담일 기준 상품군이 카탈로그에 있으면 |
| `PANEL_INSURERS` | PER_DOCUMENT | SYSTEM | `panel[].insurerName` 목록 | 상담일 패널이 비어 있지 않으면 |
| `ITEM_INSURER_NAME` | PER_ITEM | CATALOG | 항목 `insurerCode`의 패널 이름 | 그 보험사가 상담일 패널에 있으면(R-PANEL과 같은 판정) |
| `ITEM_PRODUCT_NAME` | PER_ITEM | CATALOG | 항목 `productName` | 항상(NOT NULL) |
| `CATALOG_DEFAULT` | PER_ITEM | CATALOG | `fieldValues[code]`. 카탈로그 기본값 복사 또는 임시등록 입력 | `fieldValues`에 코드가 있으면(origin 무관) |
| `AGENT_INPUT` | PER_ITEM | AGENT | `fieldValues[code]` | `fieldValues`에 코드가 있고 origin이 AGENT이면. **추천사유로 대체되지 않는다**(S13) |
| `ENGINE_GRADE_LABEL` | PER_ITEM | ENGINE | OK면 `gradeLabel`, 산출불가면 `render.unavailableText` | 등급 복사본(OK·UNAVAILABLE)이 있으면 |
| `ENGINE_RANK` | PER_ITEM | ENGINE | OK면 `rankInSet`, 산출불가면 `unavailableText` | 같음 |
| `ENGINE_UNAVAILABLE_TEXT` | PER_ITEM | ENGINE | 산출불가면 `unavailableText`와 사유, OK면 빈 칸 | 같음 |
| `RECOMMENDATION` | PER_ITEM | AGENT | 추천 항목: 사유 라벨들과 텍스트, 비추천: 빈 칸 | 추천 항목이면 추천사유가 있어야 한다. 비추천이면 존재(비추천 판단 자체가 값) |

**`STANDARD-v1` 제자리 수정(설계서 §5 주석의 "첫 운영 배포 전 형식 변경" 조건)**
- 기존 9개 항목의 결속:
  - `INSURER_NAME` → `ITEM_INSURER_NAME`
  - `PRODUCT_GROUP` → `HEADER_PRODUCT_GROUP`
  - `PRODUCT_NAME` → `ITEM_PRODUCT_NAME`
  - `PREMIUM`, `SURRENDER_VALUE_EXAMPLE` → `CATALOG_DEFAULT`
  - `COMMISSION_GRADE` → `ENGINE_GRADE_LABEL`
  - `COMMISSION_RANK` → `ENGINE_RANK`
  - `RECOMMENDATION_REASON` → `RECOMMENDATION`
  - `RECOMMENDABLE_INSURERS` → `PANEL_INSURERS`
- `bundleId`가 `STANDARD.v1@{새 해시 12자}`로 바뀐다. 로더가 ID와 본문 해시의 일치를 검사하기 때문이다. `released-bundles.txt`는 비어 있으므로(운영 배포 없음) 동결 테스트와 충돌하지 않는다.
- 헤더 항목 4개(확인서 번호·상담일·설계사·고객 성명)를 서식에 넣을지는 Q2다.
- `layout`에 `title`(문서 제목)과 섹션 `label`(선택)을 추가한다. 제목·섹션명도 서식 데이터이고 렌더러 리터럴이 아니다.

**테스트 픽스처 `STANDARD-v2`**
- `disclosure-infra/src/integrationTest/resources/rule-as-data/`에 있다.
- `TEST_ONLY_FIELD`(AGENT)를 `AGENT_INPUT`으로 결속한다.
- `RuleFreezeIT`에서 추천사유가 모두 있는 REASONED 확인서도 이 항목으로 실패함을 단언한다(S13).
- 3A `FieldValueView`와 출처별 판정은 삭제한다(D2 폐기). 설계서 §6.2의 해당 문단도 `bind` 기준으로 교체한다.

---

## 2. canonical 문서 스키마 초안 (②) — `contracts/seal/v1/canonical.schema.json`

**구성 원칙(수용심사 §3-3)**
- 최상위는 항상 객체다.
- 모든 객체는 `additionalProperties: false`이다.
- 키 이름은 camelCase이다.
- 선택 값은 **키를 생략하지 않고 `null`**로 둔다. 모양이 고정돼야 스키마와 해시 비교가 단순해진다.
- 정수는 `|n| ≤ 2^53−1`이다. JCS는 숫자를 IEEE-754 double로 직렬화하므로, 이 범위를 넘으면 해시 재현이 깨진다. 카탈로그 기본값과 엔진 `basis`에는 정수가 있다(`PREMIUM: 32100`, `groupPopulation`).
- 소수는 없다. 절대 규칙 1과 JCS 모두 이유가 된다.

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://ga.example/contracts/seal/v1/canonical.schema.json",
  "title": "봉인 본문(canonical) v1 — 이 스키마가 봉인 본문의 정본 정의다",
  "type": "object",
  "required": ["canonicalVersion", "tenantId", "disclosureId", "version", "supersedesId", "agentId", "customerRef",
               "customerName", "consultDate", "productGroup", "issuerMode", "pinned", "snapshot", "panel", "items"],
  "additionalProperties": false,
  "properties": {
    "canonicalVersion": { "const": 1 },
    "tenantId":     { "type": "string", "pattern": "^[A-Z0-9][A-Z0-9_]{0,31}$" },
    "disclosureId": { "$ref": "#/$defs/uuid" },
    "version":      { "type": "integer", "minimum": 1, "maximum": 32767 },
    "supersedesId": { "oneOf": [{ "$ref": "#/$defs/uuid" }, { "type": "null" }] },
    "agentId":      { "type": "string", "minLength": 1, "maxLength": 64 },
    "customerRef":  { "type": "string", "pattern": "^CR-[0-9a-f]{32}$" },
    "customerName": { "type": "string", "minLength": 1, "maxLength": 100 },
    "consultDate":  { "$ref": "#/$defs/date" },
    "productGroup": { "type": "object", "additionalProperties": false, "required": ["code", "name"],
                      "properties": { "code": { "type": "string" }, "name": { "type": "string" } } },
    "issuerMode":   { "enum": ["SELF", "ASSOC"] },
    "pinned": { "type": "object", "additionalProperties": false,
                "required": ["ruleVersionId", "tenantRuleVersionId", "templateId", "templateVersion"],
                "properties": { "ruleVersionId": { "type": "string" },
                                "tenantRuleVersionId": { "type": ["string", "null"] },
                                "templateId": { "type": "string" }, "templateVersion": { "type": "integer", "minimum": 1 } } },
    "snapshot": { "type": "object", "additionalProperties": false,
                  "required": ["snapshotId", "gradingPolicyVersionId", "rankingPolicyVersionId", "tieBreak", "basis", "generatedAt"],
                  "properties": { "snapshotId": { "type": "string" }, "gradingPolicyVersionId": { "type": "string" },
                                  "rankingPolicyVersionId": { "type": "string" }, "tieBreak": { "enum": ["SHARED_RANK", "STRICT"] },
                                  "basis": { "$ref": "#/$defs/jsonValueObject" },
                                  "generatedAt": { "type": "string", "pattern": "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z$" } } },
    "panel": { "type": "array", "minItems": 1, "items": { "type": "object", "additionalProperties": false,
               "required": ["insurerCode", "insurerName"],
               "properties": { "insurerCode": { "type": "string" }, "insurerName": { "type": "string" } } } },
    "items": { "type": "array", "minItems": 1, "maxItems": 50, "items": { "$ref": "#/$defs/item" } }
  },
  "not": { "$ref": "#/$defs/forbiddenKeys" },
  "$defs": {
    "item": { "type": "object", "additionalProperties": false,
      "required": ["itemNo", "productKey", "insurerCode", "productName", "groupCode", "tempProduct", "quoteDocNo",
                   "recommended", "requestedByCustomer", "fieldValues", "grade", "recommendation"],
      "properties": {
        "itemNo": { "type": "integer", "minimum": 1 },
        "productKey": { "type": ["string", "null"], "maxLength": 40 },
        "insurerCode": { "type": "string" }, "productName": { "type": "string" }, "groupCode": { "type": "string" },
        "tempProduct": { "type": "boolean" }, "quoteDocNo": { "type": ["string", "null"] },
        "recommended": { "type": "boolean" }, "requestedByCustomer": { "type": "boolean" },
        "fieldValues": { "type": "object", "propertyNames": { "pattern": "^[A-Z][A-Z0-9_]{0,63}$" },
                         "additionalProperties": { "$ref": "#/$defs/jsonValue" } },
        "grade": { "oneOf": [
          { "type": "object", "additionalProperties": false,
            "required": ["status", "source", "grade", "gradeLabel", "gradeOrdinal", "rankInSet", "ratioToAvg", "tie"],
            "properties": { "status": { "const": "OK" }, "source": { "const": "ENGINE" }, "grade": { "type": "string" },
                            "gradeLabel": { "type": "string" }, "gradeOrdinal": { "type": "integer", "minimum": 1 },
                            "rankInSet": { "type": "integer", "minimum": 1 },
                            "ratioToAvg": { "type": "string" }, "tie": { "type": "boolean" } } },
          { "type": "object", "additionalProperties": false, "required": ["status", "source", "reason"],
            "properties": { "status": { "const": "UNAVAILABLE" }, "source": { "enum": ["ENGINE", "LOCAL"] },
                            "reason": { "type": "string" } } } ] },
        "recommendation": { "oneOf": [ { "type": "null" },
          { "type": "object", "additionalProperties": false, "required": ["reasons", "text"],
            "properties": { "reasons": { "type": "array", "minItems": 1, "items": { "type": "object", "additionalProperties": false,
                              "required": ["code", "label"], "properties": { "code": { "type": "string" }, "label": { "type": "string" } } } },
                            "text": { "type": ["string", "null"] } } } ] }
      } },
    "forbiddenKeys": { "anyOf": [
      { "required": ["disclosureNo"] }, { "required": ["status"] }, { "required": ["sealedAt"] },
      { "required": ["canonicalHash"] }, { "required": ["pdfHash"] }, { "required": ["chainHash"] }, { "required": ["chainSeq"] },
      { "required": ["retentionUntil"] }, { "required": ["policyNo"] }, { "required": ["contractDate"] },
      { "required": ["phone"] }, { "required": ["birthDate"] }, { "required": ["registrationKey"] } ] },
    "jsonValue": { "anyOf": [ { "type": "string" }, { "type": "boolean" },
                              { "type": "integer", "minimum": -9007199254740991, "maximum": 9007199254740991 },
                              { "type": "array", "items": { "$ref": "#/$defs/jsonValue" } },
                              { "$ref": "#/$defs/jsonValueObject" } ] },
    "jsonValueObject": { "type": "object", "additionalProperties": { "$ref": "#/$defs/jsonValue" } },
    "uuid": { "type": "string", "pattern": "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$" },
    "date": { "type": "string", "pattern": "^\\d{4}-\\d{2}-\\d{2}$" }
  }
}
```

**수용심사 §3-3 목록에 더한 것은 셋이다(Q11).**
1. `panel`: 추천가능 보험사와 항목 보험사명.
2. `productGroup.name`.
3. 사유 `label`.

렌더러의 입력을 **canonical + 서식 본문 + 확인서 번호**로 닫기 위해서다. PDF에 찍히는 모든 글자가 해시에 묶이고, 봉인 뒤 카탈로그나 룰이 바뀌어도 재렌더가 같다.

**`ratioToAvg`를 canonical에 넣는다.**
- 불투명 문자열 원문이고 인쇄하지 않는다(§6.3).
- 정책 버전·`basis`와 같은 이유로 증거 본문에 묶는다. 서명이 붙는 해시가 "그때 엔진이 준 값"까지 덮는다.

**금지 키 `not`의 적용 범위**
- 최상위와 `items[]`에 둔다.
- `fieldValues` 안의 코드는 대문자 패턴이라 camelCase 금지 키와 겹치지 않는다.

**S3 테스트(`CanonicalSchemaTest`)**
- 빌더 산출물은 통과한다.
- 금지 키 13종을 최상위와 항목에 각각 넣은 변형은 전부 실패한다.
- 최상위가 배열이나 스칼라이면 실패한다.
- 2^53 이상의 정수가 있으면 실패한다.

**D7 `[x]` 감싸기가 봉인 경로에 나타나지 않음**
- `disclosure-seal`은 `workflow.disclosure.CanonicalValue`에 의존할 수 없다. 모듈 방향상 seal이 아래에 있다.
- ArchUnit에 "봉인 유스케이스 클래스 → `CanonicalValue` 참조 0"을 추가한다.
- 빌더 산출 바이트의 첫 바이트가 `{`임을 단언한다.

---

## 3. 봉인 트랜잭션 순서와 실패 시 정리 (③)

### 3.1 조건 6종 — 전부 평가, 단락 없음(수용심사 §3-2)

모든 조건은 `FOR UPDATE`로 잠근 행 위에서 싼 것부터 평가한다. **하나가 실패해도 나머지를 계속 평가한다.**

| # | 거부 코드 | 판정 | 부수 |
|---|---|---|---|
| ① | `RULE_SUPERSEDED` | 상담일로 GLOBAL·TENANT 재해석 결과가 고정 ID와 다름 | 플래그 `RULE_SUPERSEDED_DRAFT`(대상 DISCLOSURE, 열린 플래그 재사용) |
| ② | `TEMPLATE_SUPERSEDED` | 상담일로 서식 재해석 결과가 고정 (ID, 버전)과 다름 | 같은 플래그 |
| ③ | `SNAPSHOT_STALE` | `Duration(snapshot_generated_at, clock.instant()) > snapshotMaxAgeDays × 24h`. 값은 고정 룰에서 가져온다 | — |
| ④ | `VALIDATION_BLOCKED` | SEAL 단계 규칙 중 `overridable=false` 실패(고정 룰·서식으로 실행) | — |
| ⑤ | `APPROVAL_MISSING` | `overridable=true` 실패 중 `SealGate`가 인정하는 승인이 없는 것 | 기존 `VALIDATION_OVERRIDE` 플래그 유지 |
| ⑥ | `CUSTOMER_NAME_UNAVAILABLE` | 고객의 데이터 키가 파기됐거나(`DESTROYED`) 행이 없음. **복호화하지 않고** 키 상태만 본다 | — |

- ①·②를 둘로 나눈 이유: B1은 "룰·서식을 초안 때 고정"이고, 어느 쪽이 소급됐는지 거부 사유에서 보여야 한다. 플래그는 하나다.
- `SealGate`가 인정하는 승인은 다음이 모두 같은 것이다.
  - (규칙 ID, 대상 해시)
  - **(`rule_version_id`, `tenant_rule_version_id`) = 확인서의 현재 고정 ID**(수용심사 §3-8)
- 재해석 자체가 실패하면(Ambiguous, GLOBAL 0건) 명령 오류다. 롤백하고 `COMMAND_FAILED`를 남긴다. 룰 데이터가 깨진 것이고 업무 거부가 아니다.
- ⑥을 "복호화 없이" 보는 이유는 S6의 "거부 시 감사 1행" 때문이다. 거부 경로에는 `CUSTOMER_VIEW`가 남지 않아야 한다.
  - 실제 복호화는 조건이 전부 통과한 뒤 한 번만 한다.
  - 거기서 실패하면(예: 태그 불일치 = 저장값 손상) 예외 → 롤백 → `COMMAND_FAILED`가 된다.

**거부 결과**
- 업무 트랜잭션은 정상 커밋한다.
- 감사 `DISCLOSURE_SEAL_REJECTED` 1행에는 다음이 들어간다.
  - 거부 코드 목록
  - 실패 규칙의 결과
  - 판정 룰의 정체(고정 ID·사규 ID·본문 해시·서식 버전 — 3A I7 구조)
- ①·②의 경우 플래그도 남긴다.
- 상태·번호·카운터·체인 머리·`document_*`·저장소는 변하지 않는다.
- 조건 평가는 채번보다 앞이므로 거부가 번호를 소비하지 않는다.

### 3.2 성공 경로 — 한 쓰기 트랜잭션

잠금 순서는 모든 봉인에서 같다. 교착은 없다: 확인서 행 → 카운터 행(테넌트, 연도) → 체인 머리 행(테넌트).

```
 1  SELECT … FROM disclosure … FOR UPDATE                     (확인서 행 잠금)
 2  조건 ①~⑥ 전부 평가 → 하나라도 실패면 거부 결과 커밋(위)
 3  성명 복호화 → 감사 CUSTOMER_VIEW(reason=SEAL, 대상 customer_ref, PII 없음)
 4  canonical 빌드 → 스키마 검증 → JCS 바이트 → canonical_hash = SHA-256
 5  채번: INSERT INTO disclosure_counter (tenant_id, year, seq) VALUES (:t, :y, 1)
          ON CONFLICT (tenant_id, year) DO UPDATE SET seq = disclosure_counter.seq + 1 RETURNING seq
     disclosure_no = {tenant}-{yyyy}-{seq:06d}, yyyy = 봉인일(Asia/Seoul, Clock) 연도(Q5)
 6  렌더(canonical + 서식 본문 + disclosure_no) → PDF 바이트 → pdf_hash
 7  문서 DEK 생성(256비트, SecureRandom) → KeyProviderPort.wrap(테넌트 KEK) — 감싸기 AAD에 disclosureId 포함
 8  CANONICAL_JSON·PDF를 AES-256-GCM 암호화(AAD = JCS {disclosureId, kind, tenantId, v:1}) → cipher_sha256
 9  업로드(잠금 없음): {tenant}/{disclosure}/{kind}/{cipher_sha256}(Q4)
10  체인: SELECT … FROM disclosure_chain_head WHERE tenant_id = :t FOR UPDATE (없으면 INSERT로 생성)
     chain_seq = head.seq + 1, chain_hash = SHA-256(prev_chain_hash ‖ canonical_hash ‖ pdf_hash)
     (첫 봉인의 prev = 0×64, 해시 문자열은 소문자 hex ASCII로 이어 붙인다 — 감사 체인과 같은 규약)
11  UPDATE disclosure SET status='SEALED', disclosure_no, sealed_at=clock, canonical_hash, pdf_hash, chain_hash, chain_seq,
                          retention_until = 봉인일 + retentionYears(Q6)                 (애그리게이트 save 경로)
12  INSERT document_key, document_artifact ×2   (V7 가드: 부모가 봉인된 뒤에만 INSERT 가능)
13  UPDATE disclosure_chain_head
14  감사 DISCLOSURE_SEAL(번호·해시 3종·체인·판정 룰 정체·검증 결과 요약)
15  VALIDATION_OVERRIDE 플래그 해소: 승인이 있는 규칙 → APPROVED(resolved_by = 승인자)
                                    봉인 시점에 더는 실패하지 않는 규칙 → CLEARED_AT_SEAL(SYSTEM)(Q9)
16  COMMIT
17  (커밋 후, 별도 트랜잭션) 객체마다 applyRetention(retention_until) 성공 시
     UPDATE document_artifact SET retention_applied_at = clock + 감사 ARTIFACT_RETAIN
```

**감사 행**
- 성공한 봉인 1건의 감사는 `CUSTOMER_VIEW`, `DISCLOSURE_SEAL`, (커밋 후) `ARTIFACT_RETAIN` ×2이다.
- 검증 결과는 3A처럼 별도 `DISCLOSURE_VALIDATE` 행을 두지 않고 `DISCLOSURE_SEAL`·`DISCLOSURE_SEAL_REJECTED`의 detail에 넣는다. S6의 "거부 시 감사 1행"을 만족하는 방법이 이것뿐이다.

### 3.3 실패 시 정리

| 실패 지점 | DB | 저장소 | 정리 |
|---|---|---|---|
| 2(조건) | 거부 감사 커밋 | 없음 | — |
| 3~8(복호화·빌드·렌더·암호화 예외) | 롤백(번호·카운터·체인 포함) + `COMMAND_FAILED` | 없음 | — |
| 9(업로드 도중·후) ~ 16(커밋) 실패 | 롤백 + `COMMAND_FAILED` | **잠금 없는 고아** 0~2개 | `artifacts gc` |
| 17 `applyRetention` 실패 | 커밋됨, `retention_applied_at IS NULL` | 잠금 없는 참조 객체 | `artifacts reconcile` |

**실패 경로에서 즉시 지우지 않는 이유**
- 봉인 실패 경로에서 업로드된 객체를 곧바로 지우지 않고 gc에 맡긴다(지시문 문언).
- 즉시 삭제를 넣으면 S9의 "고아가 남는다"를 관측할 수 없다.
- 삭제 자체가 실패하면 결국 gc가 필요하다.

**gc의 경합(싸게 막을 수 있으므로 막는다)**
- 진행 중인 봉인이 업로드만 하고 아직 커밋하지 않은 객체를 gc가 지우면 안 된다.
- 그래서 gc는 **참조 행이 없고, 마지막 수정 시각이 `clock − grace`(기본 24시간, 설정)보다 오래된 객체**만 지운다.
- 봉인 트랜잭션에는 `statement_timeout`·트랜잭션 제한(설정, 기본 60초)을 둔다. grace가 이 제한보다 항상 크게 설정 검증을 한다.
- 잠금된 객체는 저장소가 삭제를 거부한다. 참조 행이 있는 객체는 gc 대상이 아니다.
- gc는 테넌트마다 `disclosure_app`으로 바인딩해 그 테넌트 접두만 본다.

---

## 4. V7 DDL 초안 (④) — `V7__seal.sql`

```sql
-- ── 1. 봉인 컬럼–상태 결속(3A에서 미룸) ─────────────────────────────────────────────
ALTER TABLE disclosure
  ADD CONSTRAINT ck_disclosure_seal_all_or_none CHECK (
        (disclosure_no IS NULL AND sealed_at IS NULL AND canonical_hash IS NULL AND pdf_hash IS NULL
         AND chain_hash IS NULL AND chain_seq IS NULL)
     OR (disclosure_no IS NOT NULL AND sealed_at IS NOT NULL AND canonical_hash IS NOT NULL AND pdf_hash IS NOT NULL
         AND chain_hash IS NOT NULL AND chain_seq IS NOT NULL AND retention_until IS NOT NULL)),
  ADD CONSTRAINT ck_disclosure_seal_by_status CHECK (CASE
        WHEN status IN ('DRAFT', 'COMPARED', 'GRADED', 'REASONED') THEN disclosure_no IS NULL
        WHEN status = 'VOID' THEN true                          -- 가변 상태에서 무효화된 문서는 봉인 컬럼이 없다(Q8)
        ELSE disclosure_no IS NOT NULL END),                    -- SEALED·PARTIALLY_SIGNED·COMPLETED·SUPERSEDED·EXPIRED
  ADD CONSTRAINT ck_disclosure_no_format CHECK (
        disclosure_no ~ '^[A-Z0-9][A-Z0-9_]{0,31}-[0-9]{4}-[0-9]{6}$' AND split_part(disclosure_no, '-', 1) = tenant_id),
  ADD CONSTRAINT ck_disclosure_seal_hashes CHECK (
        canonical_hash ~ '^[0-9a-f]{64}$' AND pdf_hash ~ '^[0-9a-f]{64}$' AND chain_hash ~ '^[0-9a-f]{64}$'),
  ADD CONSTRAINT ck_disclosure_chain_seq CHECK (chain_seq >= 1),
  ADD CONSTRAINT ck_disclosure_void CHECK ((status = 'VOID') = (voided_at IS NOT NULL)
        AND (voided_at IS NULL) = (void_reason IS NULL) AND coalesce(btrim(void_reason) <> '', true)),
  ADD CONSTRAINT ck_disclosure_superseded CHECK ((status = 'SUPERSEDED') = (superseded_by_id IS NOT NULL)),
  ADD CONSTRAINT ck_disclosure_pinned_rule CHECK (rule_version_id IS NOT NULL),   -- 3A부터 앱이 항상 고정. DB 이중화
  ADD CONSTRAINT ux_disclosure_chain_seq UNIQUE (tenant_id, chain_seq);
-- (NULL인 식이 CHECK를 통과하는 함정은 3A W8에서 배웠다 — 위 식은 IS [NOT] NULL·coalesce로만 NULL을 다룬다. SealColumnCheckIT가 전수 검사)

-- ── 2. 채번 카운터(무결번) ─────────────────────────────────────────────────────────
CREATE TABLE disclosure_counter (
  tenant_id TEXT NOT NULL REFERENCES tenant (tenant_id),
  year      SMALLINT NOT NULL CHECK (year BETWEEN 2000 AND 9999),
  seq       BIGINT NOT NULL CHECK (seq BETWEEN 1 AND 999999),
  PRIMARY KEY (tenant_id, year));
-- 트리거 GD090: UPDATE는 seq = OLD.seq + 1만(키 변경·건너뛰기·감소 거부), DELETE·TRUNCATE 거부. RLS, app에 SELECT·INSERT·UPDATE(seq).

-- ── 3. 봉인 체인 머리(Q7) ───────────────────────────────────────────────────────────
CREATE TABLE disclosure_chain_head (
  tenant_id  TEXT PRIMARY KEY REFERENCES tenant (tenant_id),
  chain_seq  BIGINT NOT NULL CHECK (chain_seq >= 1),
  chain_hash TEXT NOT NULL CHECK (chain_hash ~ '^[0-9a-f]{64}$'));
-- 트리거 GD091: UPDATE는 chain_seq = OLD.chain_seq + 1만, DELETE·TRUNCATE 거부. INSERT는 chain_seq = 1만. RLS.

-- ── 4. 문서 데이터 키(crypto-shredding) ─────────────────────────────────────────────
CREATE TABLE document_key (
  tenant_id     TEXT NOT NULL,
  key_id        TEXT NOT NULL CHECK (key_id ~ '^DOC-[0-9a-f]{32}$'),
  disclosure_id UUID NOT NULL,
  kek_key_id    TEXT NOT NULL,
  wrapped_dek   BYTEA,
  created_at    TIMESTAMPTZ NOT NULL,
  shredded_at   TIMESTAMPTZ,
  shredded_by   TEXT,
  PRIMARY KEY (tenant_id, key_id),
  UNIQUE (tenant_id, disclosure_id),                                   -- 문서당 키 1개(Phase 4 SIGNED_PDF·EVIDENCE_ZIP도 같은 키)
  FOREIGN KEY (tenant_id, disclosure_id) REFERENCES disclosure (tenant_id, disclosure_id),
  CONSTRAINT ck_document_key_shred CHECK ((shredded_at IS NULL) = (wrapped_dek IS NOT NULL)
                                         AND (shredded_at IS NULL) = (shredded_by IS NULL)),
  CONSTRAINT ck_document_key_wrapped CHECK (wrapped_dek IS NULL OR octet_length(wrapped_dek) >= 29));
-- 트리거 GD092: INSERT는 부모 disclosure_no IS NOT NULL일 때만. UPDATE는 "wrapped_dek → NULL + shredded_at·by 기록"만,
--   그것도 current_user = 'disclosure_migrator'일 때만. 나머지 UPDATE·DELETE·TRUNCATE 거부.
-- 파기 함수 ga_shred_document_key(tenant, disclosure, by) — SECURITY DEFINER(소유 disclosure_migrator), search_path 고정,
--   함수 안에서 app.tenant_id를 트랜잭션 로컬로 바인딩. EXECUTE는 PUBLIC에서 회수하고 누구에게도 주지 않는다
--   (Phase 5 파기 배치가 전용 롤에 부여). 지금은 S8 테스트가 migrator로 호출한다.

-- ── 5. document_artifact 확장 ──────────────────────────────────────────────────────
ALTER TABLE document_artifact
  ADD COLUMN cipher_sha256 TEXT NOT NULL,          -- 테이블은 비어 있다(봉인 전 Phase) — 기본값 없이 NOT NULL
  ADD COLUMN cipher_bytes  BIGINT NOT NULL,
  ADD COLUMN key_id        TEXT NOT NULL,
  ADD COLUMN retention_applied_at TIMESTAMPTZ,
  ADD CONSTRAINT fk_document_artifact_disclosure FOREIGN KEY (tenant_id, disclosure_id) REFERENCES disclosure (tenant_id, disclosure_id),
  ADD CONSTRAINT fk_document_artifact_key FOREIGN KEY (tenant_id, key_id) REFERENCES document_key (tenant_id, key_id),
  ADD CONSTRAINT ck_document_artifact_kind CHECK (kind IN ('CANONICAL_JSON', 'PDF', 'SIGNED_PDF', 'EVIDENCE_ZIP')),
  ADD CONSTRAINT ck_document_artifact_hashes CHECK (sha256 ~ '^[0-9a-f]{64}$' AND cipher_sha256 ~ '^[0-9a-f]{64}$'),
  ADD CONSTRAINT ck_document_artifact_sizes CHECK (bytes >= 1 AND cipher_bytes = bytes + 29),   -- 0x01 ‖ nonce 12 ‖ … ‖ tag 16
  ADD CONSTRAINT ck_document_artifact_key CHECK (
        storage_key = tenant_id || '/' || disclosure_id::text || '/' || kind || '/' || cipher_sha256);
DROP TRIGGER trg_document_artifact_append_only ON document_artifact;   -- V3의 전면 append-only를 아래로 교체(V3 파일은 수정하지 않는다)
-- 트리거 GD093: INSERT는 부모 disclosure_no IS NOT NULL일 때만. UPDATE는 retention_applied_at NULL→값 1회만
--   (다른 컬럼 변경·재기록 거부). DELETE는 GD030 유지(append-only 함수 재사용), TRUNCATE 트리거 유지.
GRANT UPDATE (retention_applied_at) ON document_artifact TO disclosure_app;

-- ── 6. review의 룰 버전 귀속(수용심사 §3-8) ───────────────────────────────────────
ALTER TABLE review ADD COLUMN rule_version_id TEXT, ADD COLUMN tenant_rule_version_id TEXT;
-- 기존 행 백필: 3A까지 고정 ID는 초안 이후 바뀐 적이 없다(재기준 명령이 없었다) → 부모의 현재 고정 ID가 곧 승인 당시 ID.
-- RLS(FORCE) 아래이므로 DO 블록이 테넌트마다 set_config('app.tenant_id', t, true)로 바인딩하고, append-only 트리거는
-- 이 블록 동안만 DISABLE(소유자 권한, 마이그레이션 트랜잭션 안) 후 ENABLE.
ALTER TABLE review ALTER COLUMN rule_version_id SET NOT NULL;
-- ga_review_guard_insert 교체 → GD081: NEW의 (rule_version_id, tenant_rule_version_id)가 부모의 현재 고정 ID와 다르면 거부
--   (IS NOT DISTINCT FROM). 승인은 "지금 고정된 룰 아래의 실패"에만 기록할 수 있다. 재기준 뒤 옛 승인은 SealGate가 무시한다.

-- ── 7. RLS·권한 ─────────────────────────────────────────────────────────────────────
-- 새 테이블 3개: ENABLE·FORCE RLS + tenant_isolation 정책(V2 규약), PUBLIC 회수, app 최소 권한.
-- RlsIsolationIT·TenantPredicateScanTest의 테이블 수 21 → 24.
```

**오류 코드**

| 코드 | 대상 | 조건 |
|---|---|---|
| GD081 | review INSERT | 승인의 룰 버전 ≠ 부모의 고정 버전 |
| GD090 | disclosure_counter | 건너뛰기·감소·키 변경 UPDATE, DELETE·TRUNCATE |
| GD091 | disclosure_chain_head | 같음 |
| GD092 | document_key | 봉인 전 INSERT, 파기 외 UPDATE, DELETE·TRUNCATE |
| GD093 | document_artifact | 봉인 전 INSERT, `retention_applied_at` 외 UPDATE·재기록 |

**가드와 쓰기 경로**
- 봉인 UPDATE(REASONED → SEALED)는 OLD가 가변 상태이므로 V3 가드를 통과한다.
- 그 뒤 봉인 컬럼은 V3 메타 목록 밖이므로 자동으로 불변이다.
- VOID·SUPERSEDE는 메타 컬럼(`status`·`voided_at`·`void_reason`·`superseded_by_id`)만 바꾼다.
- `DisclosureWriteScanTest` 허용 목록에 새 메서드를 넣지 않는다. 헤더 봉인 UPDATE는 기존 `DisclosureRepository#save` 경로로 간다.
- 카운터·체인·키·산출물은 확인서 테이블이 아니므로 이 스캔 대상이 아니다. 대신 쓰기 위치를 각 저장소 한 곳으로 두고 `SealWriteScanTest`(같은 스캐너 재사용)로 고정한다.

---

## 5. openhtmltopdf 세 지점 고정 (⑤)

- **라이브러리**: `io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.87`. 오늘 Maven Central의 최신이고 3A 실측과 같은 버전이다.
  - PDFBox는 3.0.7(전이)이다. 3.0.8이 나와 있지만 실측 조합을 유지한다.
  - 올릴 때는 골든 기대값 갱신과 사유가 필요하다.
- **checkBom**: 첫 커밋에서 Boot BOM과 겹치는 좌표를 확인한다(3A 승인 조건).
- **렌더러 입력**: `RenderInput(canonical JsonNode, TemplateBody, DisclosureNo, canonicalHash)`. 벽시계·`Clock`·난수·환경 참조는 0이다.

| 지점 | 값 | 파생식 |
|---|---|---|
| 문서 정보 `/CreationDate`·`/ModDate` | 상담일 00:00 KST | `Calendar(SimpleTimeZone(+9h, "KST"))`에 `consultDate` 00:00:00. **고정 오프셋 객체**를 쓰고 시스템 tz DB·기본 tz를 읽지 않는다 → `D:20260930000000+09'00'` |
| XMP `xmp:CreateDate`·`ModifyDate`·`MetadataDate` | 같은 순간 | `consultDate + "T00:00:00+09:00"`. XMP는 라이브러리 산출물을 정규식으로 고치지 않고 **렌더러가 고정 템플릿으로 통째 생성**한다: `pdfaid:part=2`, `conformance=B`, `dc:title` = 서식 `layout.title`(Info `/Title`과 일치), `pdf:Producer` = `ga-disclosure-renderer/1`(Info와 일치) |
| 트레일러 `/ID` | 두 원소 동일 | `h = SHA-256(ASCII(canonical_hash 소문자 hex) ‖ ASCII(disclosure_no))`, `/ID = [<h[0..16)> <h[0..16)>]`. 트레일러에 직접 설정한다. PDFBox `COSWriter`가 기존 `/ID`를 보존하는지 첫 구현에서 확인하고, 덮어쓰면 `setDocumentId(h[0..8) as long)` 시드로 대체한다. 보고서에 실제 식을 적는다 |

**나머지 결정론 요인**
- 폰트는 클래스패스 바이트(`FSSupplier`)에서 읽는다. 서브셋 태그는 PDFBox가 글리프 집합에서 결정론적으로 만든다.
- ICC는 동봉 `sRGB-v2-magic.icc`이다.
- 숫자·날짜는 로케일 없이 원문 그대로 찍는다(Q10).
- HTML은 `StringBuilder`로 만들고 이스케이프는 5문자만 한다. 템플릿 엔진 의존은 없다.

**각주(모든 페이지)**
- 형식은 `{disclosure_no} · {canonical_hash[0:12]} · {page}/{pages}`(CSS counter)이다. 텍스트이고 QR은 없다(§14 #9).
- 라벨 문구가 없으므로 렌더러에 한글 리터럴이 없다.

**환경 독립 검증(S2)**
- 두 번째 JVM을 `-Duser.timezone=America/New_York -Duser.language=en -Duser.country=US -Dfile.encoding=ISO-8859-1`로 띄운다.
- 첫 JVM은 `Asia/Seoul`·`ko_KR`·UTF-8이다. 두 JVM의 바이트가 같아야 한다.

**`NoFieldCodeLiteralsTest`(S13)**
- 렌더러 패키지 소스에서 `contracts/rules/bundles/templates/*`와 테스트 픽스처 서식의 모든 `code`를 문자열 리터럴로 검색해 0건이어야 한다.
- 주입 기록을 위해 `"PREMIUM"`을 넣어 실패를 확인한 뒤 제거한다.

**골든(S1)**
- `disclosure-seal/src/test/resources/golden/case-{01..03}/`에 다음을 둔다.
  - `canonical.json`(JCS 바이트)
  - `template.json`(서식 본문)
  - `input.properties`(확인서 번호)
  - `expected.properties`(`canonicalSha256`·`pdfSha256`·생성 환경)
- 사례 구성:
  - 01: 3사 정상
  - 02: 임시등록 + 산출불가 + 긴 한글 사유
  - 03: 50항목. 여러 쪽이 되는 경우다.
- 기대값은 로컬 macOS에서 `./gradlew :disclosure-seal:regenerateGolden`(수동 전용 태스크)로 만든다. CI가 검증한다.
- 기대값을 바꾸는 커밋은 메시지에 사유를 적는다. `commit-msg` 규칙은 두지 않고 보고서에 기록한다.

**veraPDF(S12)**
- CI 잡 `pdfa-verify`를 둔다.
  - 메인 빌드의 `:disclosure-seal:renderGolden`이 PDF를 `build/golden/`에 쓴다.
  - `verification/pdfa-verify/`(독립 빌드, `org.verapdf:validation-model:1.30.2` — 오늘 최신)가 PDF/A-2b 프로파일로 검증하고, 실패 1건 이상이면 잡이 실패한다.
- veraPDF는 GPL-3.0/MPL-2.0 이중 라이선스라 메인 의존성에 넣지 않는다.
- 메인 테스트 `PdfAMarkersTest`는 PDFBox로 구조 마커만 본다: XMP `pdfaid`, OutputIntent `GTS_PDFA1`+ICC, 폰트 전부 임베드.

**렌더 시간**
- `RenderTimingTest`를 둔다. 워밍업 5회 뒤 순차 50회와 동시 10건의 p50·p95를 표준 출력과 보고서에 남긴다.
- p95가 3초를 넘으면 실패시키지는 않고 보고한다. CI 러너 편차가 있어서이고, 목표치 판정은 보고서에서 한다.

---

## 6. 객체 저장소 구성 (⑥) — **MinIO 대신 SeaweedFS를 제안**

**MinIO를 쓸 수 없다(2026-09-30 확인)**
- `docker manifest inspect minio/minio:latest`와 `minio/minio:RELEASE.2025-09-07T16-13-09Z`는 `denied: requested access to the resource is denied`를 낸다.
- `quay.io/minio/minio:latest`와 과거 RELEASE 태그는 `no such manifest`이다.
- 공개 보도에 따르면 MinIO는 2025-10에 커뮤니티 이미지 배포를 중단했고, 2026-02에 저장소를 보관 처리했으며, 2026-09-11에 Docker Hub 이미지를 삭제했다.
- 마지막 커뮤니티 릴리스에는 패치되지 않은 인증 우회 CVE(CVE-2026-40344)가 있다고 보고됐다.
- Testcontainers MinIO 모듈은 이미지가 없으면 쓸 수 없다.

**대체 후보 실측(같은 날, boto3 스크립트)**

| 동작 | 기대 | SeaweedFS 4.48 | RustFS 1.0.0 |
|---|---|---|---|
| Object Lock 활성 버킷 생성 → 버전 관리 | Enabled | Enabled | Enabled |
| 잠금 없는 객체 버전 삭제 | 허용(gc 경로) | 허용 | 허용 |
| `PutObjectRetention` COMPLIANCE | 허용 | 허용 | 허용 |
| 잠금된 버전 삭제 | 거부 | AccessDenied | AccessDenied |
| 거버넌스 우회 헤더로 삭제 | 거부 | AccessDenied | AccessDenied |
| 보존기한 단축 | 거부 | AccessDenied | AccessDenied |
| 보존기한 연장 | 허용(Phase 4 완료 시) | 허용 | 허용 |
| 같은 키 덮어쓰기 뒤 잠금 버전 열람 | 원본 유지 | 유지 | 유지 |

- **권장: SeaweedFS 4.48**(Apache-2.0, 2012년부터 개발, 이 실측에서 Object Lock 의미가 AWS와 같음).
  - Testcontainers `GenericContainer` + 태그·다이제스트 고정. 버전 카탈로그에 `seaweedfs-image = "chrislusf/seaweedfs:4.48@sha256:4e61d15f…"`를 둔다.
  - 기동은 `server -s3` + S3 신원 설정 파일(테스트용 허구 키)이다.
  - 실측 때는 인증 없이 기동했지만, 구성에서는 인증을 켠다. 서명 없는 요청이 거부됨을 테스트로 확인한다.
- **대안: RustFS 1.0.0**(Apache-2.0). 같은 동작을 보였지만 1.0 정식이 2026-09-16이라 이력이 짧다.
- 설계서 §11 "S3 호환(개발: MinIO)"·"Testcontainers(PostgreSQL·MinIO)"·compose 구성을 이 결정으로 고친다. D-6(S3 호환 + Object Lock)은 그대로다.

**클라이언트(Q12)**
- AWS SDK for Java v2 `s3`(BOM `software.amazon.awssdk:bom:2.55.8`, 오늘 최신)를 쓴다.
- `url-connection-client`만 쓰고 Apache·Netty HTTP 클라이언트는 제외한다.
- path-style, 체크섬은 `WHEN_REQUIRED`이다. `PutObjectRetention`의 Content-MD5 요구는 SDK가 처리한다.

**`ArtifactStore` 포트와 구성**
- 메서드:
  - `put(key, cipherBytes)`
  - `get(key)`
  - `applyRetention(key, until)`
  - `exists(key)`
  - `list(prefix)`(gc용)
  - `delete(key)`(gc 전용, 잠금 객체는 저장소가 거부)
- 버킷은 테넌트 공통 1개이고, 키 접두 `{tenant}/`로 격리한다(설계서 §9).
- 버킷 생성 시 **기본 보존 규칙을 두지 않는다.** 기본 규칙이 있으면 업로드 즉시 잠겨 "커밋 전 잠금 금지"가 깨진다.
  - `ArtifactStoreBootstrap`이 기동 때 버킷의 Object Lock 활성·버전 관리 Enabled·기본 규칙 없음을 확인한다. 다르면 기동이 실패한다.
- 테스트 대역은 `FailingArtifactStore`(주입 지점: 업로드 후 실패, `applyRetention` 실패)이다. S9 주입에 쓴다.

---

## 7. 명령·상태표

**상태표(`state-table`) 변경**
- `REBASE`를 명령 어휘에 추가한다.
  - `COMPARED,REBASE,COMPARED|DRAFT`
  - `GRADED,REBASE,COMPARED|DRAFT`
  - `REASONED,REBASE,COMPARED|DRAFT`
  - 결과가 둘인 이유는 Q3이다.
- 구현 명령에 `SEAL`·`VOID`·`SUPERSEDE`·`REBASE`를 추가한다. W1 테스트의 구현 목록이 늘어난다.
- `SIGN`·`COMPLETE`·`EXPIRE`는 표에만 있다.

**Seal**: §3.

**Rebase**
- `RULE_SUPERSEDED_DRAFT` 열린 플래그가 없으면 업무 거부 `REBASE_NOT_ALLOWED`이다.
- 상담일로 재해석한 룰·서식을 새로 고정한다. 스냅샷·사유를 폐기한다.
- 새 룰로 COMPARE 단계 검증을 한다. 통과하면 COMPARED, 오버라이드 불가 실패가 있으면 DRAFT다(Q3).
- 플래그를 `REBASED`로 해소한다. 감사 `DISCLOSURE_REBASE`(이전·새 고정 ID)를 남긴다.
- 옛 승인은 그대로 남는다. 삭제하지 않고 룰 버전 귀속으로 무효가 된다(S7 `RebaseIT`).

**Void(reason, actorRole)**
- 가변 상태에서는 역할 제한이 없다(설계사 본인 — 인가는 Phase 6).
- 봉인 이후에는 `actorRole = rule.exceptionApproval.role`(고정 룰에서)이 필요하다. 다르면 업무 거부 `VOID_ROLE_REQUIRED`이다.
- 사유가 빈 문자열이면 명령 오류다(입력 전제).
- 열린 `VALIDATION_OVERRIDE`·`RULE_SUPERSEDED_DRAFT`는 `SUPERSEDED_BY_DOCUMENT_STATE`로 해소한다. `GRADE_INCONSISTENT`는 엔진 이상 신호이므로 그대로 둔다.
- 감사 `DISCLOSURE_VOID`를 남긴다.

**Supersede(reason, actorRole)**
- 봉인 이후 상태(표의 행)에서만 가능하다. 역할 규칙은 Void와 같다.
- 한 트랜잭션에서 다음을 한다.
  - 새 DRAFT(`version + 1`, `supersedes_id`)를 만든다. 고객·설계사·상품군·상담일은 같다.
  - 항목을 복제한다(`field_values` 포함). 등급 복사본과 추천사유는 복제하지 않는다.
  - 룰·서식은 **원본 상담일로 재해석**해 새로 고정한다.
  - 원본은 `SUPERSEDED`와 `superseded_by_id`를 갖는다.
  - 플래그를 해소한다(Void와 같다).
  - 감사 `DISCLOSURE_SUPERSEDE`(원본)와 `DISCLOSURE_CREATE`(새 버전, `supersedesId`)를 남긴다.
- 원본의 산출물·키·잠금은 그대로다.

**CLI**

| 명령 | 비고 |
|---|---|
| `disclosure seal --tenant T --id <uuid>` | 거부면 종료 코드 2와 거부 코드 목록 |
| `disclosure void --tenant T --id … --reason-file <path> --role R` | 사유는 파일로 받는다. 자유 텍스트에 PII가 섞일 수 있어서다(CLAUDE.md 규칙 6). `supersede`도 같다 |
| `disclosure supersede …` | 위와 같음 |
| `disclosure rebase --tenant T --id …` | |
| `artifacts get --tenant T --id … --kind PDF --out <path>` | 복호화 → `sha256` 대조 → 감사 `ARTIFACT_VIEW` |
| `artifacts gc --tenant T\|all [--grace PT24H]` | 테넌트별, 삭제마다 감사 `ARTIFACT_GC` |
| `artifacts reconcile --tenant T\|all` | 감사 `ARTIFACT_RETAIN` |

**데모 시드**
- 봉인 2건: A-1 정상, A-2 임시등록 + 승인.
- 정정 1건: A-1 → supersede → 새 버전 REASONED.
- 2회차는 NOOP다. 판정 기준:
  - 봉인: 같은 (고객, 상담일, 상품군)의 SEALED 존재
  - 정정: `supersedes_id`가 있는 버전의 존재
- compose에 `seaweedfs` 서비스와 버킷 부트스트랩을 추가한다. 로컬 KEK 파일은 Phase 2와 같이 저장소 밖에 둔다.

---

## 8. 완료 기준 ↔ 테스트 (요약)

| # | 테스트 | 핵심 단언 |
|---|---|---|
| S1 | `SealGoldenTest`(seal 단위) | 골든 3건의 canonical·PDF SHA-256 = 커밋 값. CI Linux에서 통과 = OS 간 결정론 |
| S2 | `RenderDeterminismIT` | 봉인 → 저장된 PDF를 복호화 → 별도 JVM 2개(다른 tz·로케일·인코딩)에서 복원한 canonical로 재렌더 → 바이트 동일. 상담일·항목 하나 변경 시 두 해시 모두 변경 |
| S3 | `CanonicalSchemaTest` | §2 |
| S4 | `ImmutabilityTriggerIT`(봉인된 확인서 행·항목·사유·산출물·키 편입), `SealColumnCheckIT` | 봉인 컬럼 6개의 NULL/값 2^6 × 상태 10 = 640조합 + VOID·SUPERSEDED·번호 형식·해시 형식 변형 전수. 기대는 독립 오라클 함수로 계산한다(3A W8 방식) |
| S5 | `NumberingIT` | 같은 테넌트 REASONED 50건을 동시에 봉인 → 번호 {1..50} 정확히, 중복 0. 50 + 거부 유도 20건 혼합에서도 {1..50}. 다른 테넌트 병행 시 서로 독립 |
| S6 | `SealRejectionIT` | 조건 6종 각각 단독 실패 + 6종 동시 실패(목록에 6개 전부). 매번 상태·번호·카운터·체인 머리·`document_*`·버킷 객체 수·감사 증가분 1행을 단언 |
| S7 | `SealGateTest`(단위), `RebaseIT` | 대상 해시만 같고 룰 버전이 다른 승인은 무시. 재기준 뒤 옛 승인으로 봉인 시도 → `APPROVAL_MISSING`. 새 승인 후 봉인 성공 |
| S8 | `ArtifactEncryptionIT` | 버킷에서 직접 받은 바이트에 평문 SHA-256(hex·바이트)과 성명(UTF-8)이 없음. 형식 머리 0x01. 복호화 뒤 `sha256` 일치. AAD 교차(다른 kind·확인서로 옮김) 시 태그 실패. `ga_shred_document_key` 뒤 `artifacts get`은 `ArtifactKeyShreddedException`, 감사 `ARTIFACT_VIEW_DENIED` |
| S9 | `RetentionOrderIT` | (a) 커밋 직전 실패 주입 → 롤백, 버킷에 잠금 없는 고아 2, `gc`(grace 0, 시계 이동) 후 0. (b) 진행 중 봉인의 객체는 grace 안이라 `gc`가 건드리지 않음. (c) `applyRetention` 실패 주입 → `retention_applied_at` NULL → `reconcile` → 값, 저장소 보존기한 = `retention_until`. (d) 잠금 객체 삭제 시 저장소 거부 |
| S10 | `SealChainIT` | 테넌트별 `chain_seq` 1..n 갭 0, `chain_hash` 재계산 일치, 다른 테넌트 체인 독립. 체인 머리 = 마지막 행 |
| S11 | `LifecycleIT` | 표의 VOID·SUPERSEDE·REBASE 행 전부. Supersede 새 버전의 고정 ID = 원본 상담일 재해석(원본 고정 이후 소급 배포된 룰로). 플래그 해소 규칙 3종(APPROVED·CLEARED_AT_SEAL·SUPERSEDED_BY_DOCUMENT_STATE·REBASED) |
| S12 | CI `pdfa-verify` 로그 + `PdfAMarkersTest` | §5 |
| S13 | `RuleFreezeIT` 갱신 + `NoFieldCodeLiteralsTest` | §1·§5 |
| S14 | 전체 빌드 | 평문 유출 스캔에 봉인 경로 로그 포함(`PlaintextLeakScanIT`에 봉인 후 DB 덤프·서버 로그·**버킷 전 객체 바이트** 스캔 추가). BOM(`checkBom`)·jqwik 0 |

**규칙 테스트 주입 계획(보고서 표로 기록)**
- V7 트리거 5종에 각각 위반을 넣는다.
- `NoFieldCodeLiteralsTest`에 코드 리터럴을 넣는다.
- `SealWriteScanTest`에 허용 밖 INSERT를 넣는다.
- ArchUnit `CanonicalValue` 금지를 어긴다.
- 렌더러에서 `Instant.now()`·`Locale.getDefault()`를 호출한다. 기존 벽시계 금지 ArchUnit 규칙에 더해, 렌더러 패키지는 `java.util.Locale#getDefault`·`TimeZone#getDefault`·`SecureRandom`도 금지하는 규칙을 추가한다.
- 채번을 선할당으로 바꾸고, 거부 뒤 번호 소비가 생기는지 확인한다.

---

## 9. 질문 (권장안 먼저)

**Q1. 객체 저장소.**
- **권장**: SeaweedFS 4.48(§6 실측).
- 대안: RustFS 1.0.0. 둘 다 지시문의 "MinIO"와 다르므로 결정이 필요하다.

**Q2. 헤더 항목(확인서 번호·상담일·설계사·고객 성명)을 서식 항목으로 둘지.**
- `STANDARD-v1`의 확인된 9개 항목에는 이 넷이 없다. 확인되지 않은 항목명은 넣지 않는 것이 `pendingConfirmation` 원칙이다.
- 그런데 서식에 항목이 없으면 성명이 canonical에는 있으면서 PDF에는 찍히지 않는다. 이는 §6.4-8("서명자가 누구인지 문서 자체가 말해야 증거")과 어긋난다.
- **권장**: 항목 4개를 `required=true`로 추가한다. 코드는 `DISCLOSURE_NO`·`CONSULT_DATE`·`AGENT`·`CUSTOMER_NAME`이고, 라벨은 "확인서 번호"·"상담일"·"설계사"·"고객명"이다. 새 `HEADER` 섹션에 두고, `pendingConfirmation`에 "헤더 4개 라벨·배치는 협회 서식 확인 전 가정(`TODO(confirm#2)`)"을 남긴다.
- 대안: 서식에 넣지 않는다. 확인서 번호만 각주에 찍고 성명은 인쇄하지 않는다.

**Q3. 재기준 결과.**
- 지시문은 "COMPARED 회귀"다. 그런데 새 룰의 COMPARE 검증(예: `minCompare` 3 → 4)을 지금 항목이 통과하지 못하면 COMPARED 불변식(3A W2)이 깨진다.
- **권장**: 결과를 `COMPARED|DRAFT`로 한다. 새 룰로 통과하면 COMPARED, 아니면 DRAFT(검증 없는 작성 상태)이다. 교착이 없다.
- 대안: 통과하지 못하면 재기준을 업무 거부한다. 이 경우 설계사가 옛 룰 아래에서 항목을 먼저 고쳐야 하고, 두 룰이 상충하면 VOID 말고는 길이 없다.

**Q4. 객체 키의 해시.**
- 지시문의 `{sha256}`은 평문 해시로 읽힌다.
- **권장**: 암호문 해시(`cipher_sha256`)를 쓴다. 이유는 둘이다.
  1. 버킷 목록에 평문 해시가 드러나지 않는다(S8의 "평문 해시 없음"을 키에도 적용).
  2. 봉인 재시도마다 DEK·nonce가 달라 키가 달라진다. 실패한 시도의 고아가 성공한 시도의 키와 같은 이름의 옛 버전으로 숨지 않는다.
- 평문 해시로 하면 같은 키에 버전이 쌓이고, gc가 "참조된 키의 잠금 없는 옛 버전"까지 따로 다뤄야 한다.

**Q5. 확인서 번호의 연도.**
- **권장**: 봉인일(Asia/Seoul, 주입 Clock) 연도. 발급 연도이고, 카운터 행이 발급 순서와 같이 움직인다.
- 대안: 상담일 연도. 12-31 상담·01-02 봉인 건이 전년도 번호를 받는다.

**Q6. `retention_until`을 봉인 때 정할지.**
- 설계서 §9는 "완료일(또는 계약일) + `retentionYears`"이고, 완료는 Phase 4다. 그런데 커밋 후 잠금에는 기한이 필요하다.
- **권장**: 봉인 때 `봉인일 + retentionYears`(고정 룰)를 하한으로 설정하고, 잠금도 그 값으로 건다.
  - Phase 4 완료 시 재계산해 **연장만** 한다. COMPLIANCE 모드는 연장만 허용하며, §6 실측으로 확인했다.
  - 미체결·VOID 건의 보존(§14 #3)도 이 하한이 막는다.
  - Phase 4에서 `retention_until` 단조 증가 트리거를 추가한다.

**Q7. 체인 직렬화.**
- 카운터 잠금은 (테넌트, 연도) 단위이고 체인은 테넌트 단위다. 연말 경계에서 두 봉인이 서로 다른 연도 카운터를 잡으면 `max(chain_seq)` 방식은 경합한다.
- **권장**: V7에 `disclosure_chain_head`(테넌트당 1행, +1만 허용 트리거)를 두고 `FOR UPDATE`로 잠근다.
- 대안: `pg_advisory_xact_lock(테넌트 해시)` + `max(chain_seq)`. 테이블이 없는 대신 DB가 +1을 강제하지 못한다.

**Q8. 결속 CHECK의 VOID.**
- 지시문 문언은 "봉인 이후 상태 ⇔ 봉인 컬럼 전부 NOT NULL"이다. 그런데 VOID는 가변 상태(DRAFT~REASONED)에서도 들어갈 수 있고, 그때 번호가 없다. V3의 `ga_is_mutable_status`에서도 VOID는 불변 쪽이다.
- **권장**: VOID는 "전부 있거나 전부 없음"만 요구한다(§4 DDL). 나머지 봉인 이후 상태는 전부 NOT NULL이고, 가변 상태는 전부 NULL이다.

**Q9. 봉인 시점에 더는 실패하지 않는 규칙의 열린 오버라이드 플래그.**
- 예: 임시등록 항목을 중간에 빼서 R-TEMP-PRODUCT 실패가 사라진 경우다. 3A는 실패가 사라져도 플래그를 닫지 않았다.
- **권장**: 봉인 성공 시 `CLEARED_AT_SEAL`(해소자 `SYSTEM`)로 닫는다. 승인이 있는 규칙은 결정 7대로 `APPROVED`(해소자 = 승인자)다.

**Q10. 값 표기.**
- 카탈로그 기본값의 정수(`PREMIUM: 32100`)와 배열·객체(해약환급예시 표)의 표기가 문제다.
- **권장**: 3B는 로케일 없는 원문 표기로 한다. 정수는 자릿수 구분 없이, 배열·객체는 키·값 중첩 표로 찍는다. 표기 규칙(`render.format` — 천 단위 구분, 표 열 라벨)은 서식 데이터로 Phase 7에서 정한다.
- 금액 표기를 지금 코드에 넣으면 규칙 4(서식 배치는 데이터)와 결정론(로케일) 둘 다 건드린다.

**Q11. canonical에 패널·상품군명·사유 라벨 포함(§2).**
- **권장**: 포함한다. 렌더러 입력이 canonical + 서식 + 번호로 닫힌다.
- 대안: ID만 두고 렌더 때 카탈로그·룰에서 조회한다. 이 경우 봉인 뒤 카탈로그 정정이 재렌더 바이트를 바꾼다.

**Q12. S3 클라이언트.**
- **권장**: AWS SDK v2(`s3` + `url-connection-client`, BOM 2.55.8).
- 대안: JDK `HttpClient` + 직접 구현한 SigV4. 의존이 0이지만 서명 코드를 우리가 소유하게 된다.

---

## 10. 순서 (승인 후)

1. **문서**: 이 계획 갱신(승인 반영), 설계서 v1.8 초안 절(§5 V7·§6.1 표·§6.2 bind·§6.4·§6.6·§9·§11·§12·§14)은 각 코드 커밋과 같은 커밋으로.
2. **V7** + `db-error-codes.md` + `SealColumnCheckIT` + 트리거 주입 기록.
3. **서식 결속**: `form-template.schema.json` `bind`, `STANDARD-v1` 제자리 수정, `BindingResolver`, `R-FIELD-REQUIRED` 교체, `FieldValueView` 삭제, `RuleFreezeIT`·`RuleAsDataIT` 갱신.
4. **canonical**: `contracts/seal/v1/canonical.schema.json`(+ `CHECKSUMS`), `CanonicalDocumentBuilder`, `CanonicalSchemaTest`.
5. **렌더러**: openhtmltopdf 의존성(`checkBom`), HTML·XMP·세 지점, 골든 3건, `PdfAMarkersTest`, `NoFieldCodeLiteralsTest`, `RenderTimingTest`, CI `pdfa-verify` 잡 + `verification/pdfa-verify/`.
6. **저장·암호화**: `ArtifactStore`·S3 어댑터·SeaweedFS 하니스, `DocumentCipher`(infra.crypto), `document_key`·`document_artifact` 저장소, `ArtifactEncryptionIT`.
7. **봉인**: `Seal`·`SealGate` 갱신·채번·체인, `NumberingIT`·`SealRejectionIT`·`SealChainIT`·`RetentionOrderIT`·`RenderDeterminismIT`.
8. **Void·Supersede·Rebase**, 상태표·W1, `LifecycleIT`·`RebaseIT`.
9. **CLI·데모·compose**, 데모 2회 실행 기록.
10. **보고서** `docs/phase-03B-보고서.md`(지시문 추가 4항목 포함), PR, CI 1차 증거(`gh run view`), 태그 `phase-3B`.

**엔진 쪽 할 일(수용심사 §4)**
- ① 마이그레이션 번호 규칙 문서화, ② V104 `product_key` 폭 40은 승인됐다.
- §5 "엔진은 E4까지 대기"에 따라 E4 착수 때 첫 커밋으로 처리한다. 지금 엔진 저장소에는 손대지 않는다.
