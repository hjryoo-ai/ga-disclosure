# Phase 3A 수용 심사 결과 (2026-09-30)

## 판정: 수용. PR #5 병합 승인(merge commit). 엔진 E3.1 PR #2도 병합 승인

## 1. 인정하는 것

- **I7의 처리.** "명령이 고정 ID 대신 상담일로 재해석" 주입이 1차에 잡히지 않았을 때, 테스트 입력을 조작해 잡히게 만드는 대신 **검증 감사에 판정 룰의 정체(고정 ID·사규 ID·본문 해시·서식 버전)를 남기는 구조 변경**으로 잡히게 했다. 규칙 테스트가 못 잡은 것을 "테스트를 좁히지 않고 관측 가능성을 늘려" 해결한 첫 사례이고, 그 감사 필드는 3B·Phase 5 증거 패키지에서 그대로 쓰인다.
- **규칙 테스트가 개발 중 실제 결함 2건을 잡은 기록.** V6 CHECK의 NULL 통과(`SnapshotColumnCheckIT` 768조합)와 `ratio_to_avg`를 언급한 CHECK(`RatioLabelRoundTripIT`). 후자를 규칙을 좁히지 않고 생성 컬럼 `ratio_present`로 코드를 옮겨 해결한 것은 CLAUDE.md의 거짓 양성 원칙을 DB 제약에까지 적용한 것이다.
- **W1이 설계서를 테스트 입력으로 파싱**하고, Gradle UP-TO-DATE 때문에 설계서 변경이 무시되던 것을 test inputs에 추가해 고친 것. 문서-코드 동일 커밋 원칙이 처음으로 기계 검증됐다.
- **엔진 클라이언트가 자기 요청도 계약으로 검증(D11)**하고, JCS 상호 검증에서 엔진 라이브러리의 짝 없는 서로게이트 수용을 찾아 고친 것. 두 저장소 사이의 접점이 문서가 아니라 테스트로 묶였다.

## 2. D1~D12 판정

전부 수용. 덧붙일 것만 적는다.

| # | 덧붙임 |
|---|---|
| D2 | 출처별 존재 판정은 임시 방편이고 `TEST_ONLY_FIELD`의 과대 충족이 그 한계를 스스로 드러냈다. 3B 질문 1의 `bind`로 대체한다. `R-FIELD-REQUIRED`도 `bind` 기준으로 바뀐다. |
| D7 | `CanonicalValue`의 스칼라 `[x]` 감싸기는 승인 대상 해시 생성과 검증이 **같은 함수**를 쓰는 한 문제없다. 3B canonical 문서는 최상위가 항상 객체이므로 봉인 경로에는 이 규약이 나타나지 않아야 한다(테스트로 확인). |
| D9 | 관리자가 본 적 없는 대상을 승인할 수 없게 한 것은 Q3의 귀속 원리를 기록 시점까지 밀어붙인 것이다. 3B에서 `review`에 고정 룰 버전 2종을 함께 귀속시킨다(§3-8). |

## 3. 3B 질문에 대한 결정

1. **서식 항목 ↔ 값 결속: `render.bind` 닫힌 어휘 채택.** 코드는 항목 코드를 모르고 `bind`로만 분기한다. 어휘는 계획에서 열거해 승인받되 최소 `HEADER_*`(확인서 번호·상담일·설계사·고객 성명·상품군), `CATALOG_DEFAULT`, `ENGINE_GRADE_LABEL`, `ENGINE_RANK`, `ENGINE_UNAVAILABLE_TEXT`, `RECOMMENDATION`, `AGENT_INPUT`, `PANEL_INSURERS`. `R-FIELD-REQUIRED`는 `bind`가 가리키는 값의 존재로 판정한다(D2 폐기). 서식 형식 제자리 수정은 첫 운영 배포 전 조건으로 허용.
2. **봉인 조건: 전부 업무 거부, 단락 없이 전부 평가해 실패 목록을 한 번에 반환.** 순서는 싼 것부터(룰 재해석 → 스냅샷 노후 → SEAL 단계 검증·승인 → 성명 복호화 가능). 실패해도 상태 불변, `DISCLOSURE_SEAL_REJECTED` 감사 + 해당 플래그.
3. **canonical JSON 구성.** 항목은 `item_no` 순, 스냅샷 결과는 항목 순(D8), `field_values`는 `{code: value}`이고 origin은 증거 패키지에만. 성명은 봉인 시점 복호화(감사 `CUSTOMER_VIEW` 1행, 사유 `SEAL`). 추가로: 최상위에 `canonicalVersion: 1`, 테넌트·확인서 ID·버전·`supersedesId`·설계사·`customerRef`·상담일·상품군·정본 모드·고정된 룰·사규·서식 버전 ID·스냅샷 헤더(정책 2종·`tieBreak`·`basis`·`generatedAt`)를 넣고, **확인서 번호·상태·봉인 시각·해시·체인·메타 컬럼은 넣지 않는다**(번호는 canonical 해시 다음에 채번되므로). 봉인 경로의 canonical은 최상위가 객체다(D7 규약 미사용).
4. **채번: `(tenant_id, year)` 카운터 행 `INSERT … ON CONFLICT DO UPDATE SET seq = seq + 1 RETURNING seq` 채택. 무결번(gapless)으로 한다** — 번호 채번과 렌더·저장·커밋이 한 트랜잭션이고 카운터 행 잠금이 테넌트·연도별 봉인을 직렬화한다. 봉인이 실패하면 번호는 롤백되어 빈 번호가 생기지 않는다. 대가는 테넌트당 봉인이 순차라는 것인데 렌더 p95 3초면 월말 저녁에도 충분하다. 3B 완료 기준에 동시 봉인 50건(같은 테넌트)에서 번호 연속·중복 0을 넣는다. 번호 선할당(빈 번호 허용) 방식은 실측 병목이 확인될 때만 재검토.
5. **봉인 산출물 암호화: 문서별 DEK를 테넌트 KEK로 감싼 `document_key` 테이블 채택.** 파기 = 감싼 키 NULL(crypto-shredding). SSE-KMS 테넌트 키 대안은 문서 단위 파기가 불가능하므로 기각. 클라이언트 측 AES-256-GCM, AAD = `tenant_id`·`disclosure_id`·`artifact kind`. `document_artifact.sha256`은 **평문** 해시(체인·검증용)이고 암호문 해시를 별도 컬럼에 둔다. **Object Lock 적용 순서**: 객체 업로드(잠금 없이) → DB 커밋 → 커밋 후 `PutObjectRetention`(보존기간 = `retention_until`) → `document_artifact.retention_applied_at` 기록. 커밋 실패 시 잠금 없는 고아 객체만 남아 GC 가능하고, 잠금 적용이 누락된 행은 준법 배치가 재적용한다.
6. **플래그 유형 `VALIDATION_OVERRIDE` 하나 유지.** 큐(Phase 6)는 대상의 규칙 ID로 묶는다.
7. **플래그 해소: 봉인 성공 시 그 확인서의 오버라이드 플래그를 `APPROVED`(해소자 = 승인자)로 닫는다.** 승인만으로는 닫지 않는다. VOID·SUPERSEDE 시에는 `SUPERSEDED_BY_DOCUMENT_STATE`로 닫는다.
8. **재기준(rebase): COMPARED 회귀 + 상담일 재해석 결과를 새로 고정 + 스냅샷·사유 폐기 + 기존 승인 무효.** 무효화는 삭제가 아니라 귀속으로 한다 — `review`에 고정 룰 버전 2종(`rule_version_id`·`tenant_rule_version_id`, V7)을 넣고 `SealGate`가 현재 고정 버전과 같은 승인만 인정한다. 재기준은 `RULE_SUPERSEDED_DRAFT` 플래그가 있을 때만 허용되는 명령이다(임의 재기준 금지).

## 4. 엔진 E3.1 (PR #2): 병합 승인 + 질문 2건

- **① 마이그레이션 번호**: 승인. 앞으로 공통·벤더 디렉터리가 **하나의 번호 공간**을 쓰고 다음은 V104. CONTRIBUTING·CLAUDE.md에 "번호는 전역 단조, 디렉터리별 재시작 없음"으로 적는다. 이미 적용된 V12는 그대로 둔다(수정 금지).
- **② `DISC_GRADE_SNAPSHOT_ITEM.product_key` 폭 129→40**: 승인. V104. 저장된 행이 계약(40자)을 넘을 수 없으므로 폭도 맞추는 것이 옳다.
- 스냅샷 번호 7자리 시작은 문제없음(이 저장소는 불투명 코드로 다룬다).

## 5. 다음

- `phase-03B-지시문.md` 첨부. 3A PR #5 병합 후 시작.
- 엔진 E3.1 병합 후 엔진은 E4(정책·상품군 운영 경로)까지 대기. E4는 이 저장소 Phase 6과 함께 지시한다.
