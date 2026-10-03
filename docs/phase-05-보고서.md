# Phase 5 완료 보고 — 일일 앵커·TSA·검증·파기·법적 보류

작성 2026-10-03 · 대상 지시문 `docs/phase-05-지시문.md` · 계획 `docs/phase-05-계획.md`(승인 2026-10-03, `docs/phase-05-계획승인.md`)
- 승인 내용: Q2·Q13은 대안, Q12는 승인 문구대로 REPEATABLE READ, 나머지는 권장안. 보강 B1~B4.
- 설계서 v1.11 · 브랜치 `work/phase-5` · PR [#8](https://github.com/hjryoo-ai/ga-disclosure/pull/8)
- **병합은 수용 심사 회신 뒤**에 한다.

## 요약

- **봉인 체인과 감사 체인을 매일 외부 시각에 묶는다(지시문 §2·§3).**
  - 테넌트마다 KST 하루 한 번 두 체인의 머리를 **한 스냅샷**(REPEATABLE READ)에서 읽어 앵커 레코드를 쓴다.
  - 그 날짜의 앵커 전부를 잎으로 깊이 16 고정의 머클 루트 **하나**를 만들고, RFC 3161 토큰을 받는다.
  - 테넌트는 자기 잎의 경로·루트·토큰이 담긴 영수증만 갖는다.
  - 잎과 경로→루트는 DB 트리거가 같은 식으로 다시 계산한다(GD110·GD111).
  - TSA가 실패하면 앵커만 남고, 다음 실행이 같은 날짜의 둘째 배치로 잇는다.
- **TSA는 포트 + 검증기 + 로컬 스텁이다.**
  - Bouncy Castle 1.86을 쓰고 OSV 0건이다(대조군 1.77은 bcprov 9건).
  - 수락 조건: 상태가 granted이고, nonce가 같고, imprint가 루트와 같고, EKU가 timeStamping 하나(critical)이고, CMS 서명과 ESSCertID가 결속되고, genTime 시점에 인증서가 유효해야 한다. 설정된 신뢰 앵커로의 PKIX 경로가 닿지 않으면 `UNTRUSTED`다(통과로 치지 않는다).
  - BC 참조는 `audit.tsa`·`audit.tsa.stub`에만 있다(ArchUnit).
- **검증은 두 명령이다(지시문 §4).**
  - `verify package`는 DB·키·저장소·생산자 코드 없이 증거 패키지만으로 돈다(ArchUnit으로 증명).
    - 영수증이 있으면 결론을 상한으로만 단언한다: "이 문서는 {TSA 시각} 이전에 이 내용으로 존재했다".
    - 직전 앵커가 있으면 하한을 **자체 기록**이라고 밝힌 약한 문장으로 덧붙인다(승인 Q13 대안).
  - `verify tenant`는 감사·봉인 체인, 채번, 객체(복호화·해시), 앵커, 영수증을 한 스냅샷에서 흘려 읽는다.
  - 보고서는 닫힌 스키마이고 종료 코드는 0/2/3이다.
- **파기는 묘비를 남긴다(지시문 §5, CLAUDE.md 규칙 2).**
  - 순서: ⓪ 잠금 기한 확인 → ① 문서 키 파기 → ② 모든 버전·삭제 마커를 버전 ID로 삭제 → ③ 지정 개인정보 컬럼만 NULL. 단계별로 멱등이다.
  - 번호·상태·해시·시각·체인과 서명 레코드의 두 해시는 그대로 남는다. 그래서 파기 뒤에도 봉인 체인·채번·`verify tenant`가 성립한다.
  - 지운 값은 감사에 해시(작은 정의역은 존재·유형만)로 남는다.
  - 파기는 **전용 정의자 롤의 DB 함수만** 할 수 있다. 앱은 한 트랜잭션 안에서 `SET LOCAL ROLE`로 함수를 부른다(승인 Q2 대안).
  - 지정 컬럼 전수는 설계서 §9 `pii-columns` 블록이 정본이다. 테스트가 세 함수의 `UPDATE … = NULL` 집합(`pg_proc`)과 DB 카탈로그에 양방향으로 대조한다.
  - 구현 중 찾은 `legal_hold.reason_text`는 V11로 더했다(승인 Q4).
- **법적 보류**: DB 보류가 통제한다(배치·함수가 건너뛴다). 저장소 legal hold(SeaweedFS 지원)는 보조 장치로, 모든 버전에 건다. 보류는 `retention_until`을 바꾸지 않는다.
- **룰 데이터**: 보존 = `retentionYears`년 + `retentionDays`일(합계 ≥ 1일)이다. 다음 값도 전부 룰 데이터이고, 코드 diff 없이 룰만 바꿔 판정이 바뀐다(G10):
  - 계약일 대기 `contractLinkWaitDays`, 고객 유예·방치 일수
  - 보류 사유 코드와 텍스트 상한
  - 앵커 깊이, 미고정 경보 일수
- **테스트 12,037건, 실패 0, 스킵 0**(Phase 4: 11,733건).
  - 위반 주입 69건이 전부 의도한 테스트에서 잡혔다(§3).
  - 1건(V7′, 7단계)은 첫 시도에서 실패 0이었다. 빠진 테스트(산출물 해시 변조)를 더한 뒤 잡혔다.
- **CI**: PR #8의 첫 run `37099082331`(head `5acfbb6`)은 `build`가 **실패**했다. 시간대 의존 테스트 1건이 원인이었다(로컬 KST, CI UTC). 고친 뒤 run `37099922622`(head `858ef53`)에서 `build`·`pdfa-verify`·`no-docker`가 전부 success였다. CI 테스트 보고서는 로컬과 같은 12,037건(실패 0, 무시 0)이고, 모듈별 수도 같다(§3 CI). `gh run view`와 아티팩트 내려받기로 직접 확인했다.
- **데모**: 사용자의 로컬 compose 볼륨을 건드리지 않으려고, 새 격리 컨테이너(포트 15432/18333)에서 `seed.sh`를 2회 실행했다.
  - 1회째: 두 날 앵커 → 영수증 → `verify package` 둘 다 0 → DEMO3 짧은 보존 → 파기 1건·HOLD 1건 → `verify tenant` 세 테넌트 0.
  - 2회째: 전부 NOOP(§4).
- **엔진**: 엔진 저장소는 손대지 않았다.

## 1. 커밋·파일

커밋 목록(`main..work/phase-5`, 보고서 커밋은 이 표 뒤에 붙는다):

| 커밋 | 계획 §10 | 요약 |
|---|---|---|
| `0d8c172` | — | docs: Phase 4 수용 심사 기록, Phase 5 지시문, 규칙 2 보존기간 문구, 병합 게이트, 파기 롤 |
| `8bbf030` | — | docs: Phase 5 계획(승인 대기) |
| `8f7091d` | 1 | docs: 계획 승인 반영 — Q2 한 트랜잭션 `SET LOCAL ROLE`, Q13 약한 하한 |
| `5075aac` | 2 | V9 — 앵커·영수증·법적 보류·정의자 롤을 통한 파기(GD110~GD114) |
| `30a83ec` | 3 | 룰 — 보존 = 년 + 일, GLOBAL `anchoring.treeDepth`, 파기·검증 키 |
| `25fbb40` | 4 | audit 순수 — 머클 규격, 체인 걷기, RFC 3161 포트·검증기·스텁(BC 1.86) |
| `b50cd9b` | 5 | 저장소 — 버전 ID로 모든 버전·마커 삭제, 전 버전 legal hold, 능력 명시 |
| `8b721fd` | 6 | 일일 앵커 배치(REPEATABLE READ), 날짜별 머클 영수증, 매니페스트 `anchor` |
| `dd8c4f7` | 7 | `verify package`(생산자 독립), 영수증 내보내기, `verify tenant` |
| `2557756` | 7(보완) | 대상을 정하지 못한 `CHAIN_BROKEN`이 끊긴 지점(감사 seq·앵커)을 가리키게(계획 §1.6) |
| `4d9e5fd` | 8 | 파기 배치·법적 보류·고객 파기·`DisclosureDestroyed`·`pii-columns` 표(V10·V11) |
| `5acfbb6` | 9 | CLI(앵커·영수증·검증·파기·보류), 데모 시계 오프셋, DEMO3 짧은 보존 데모 |
| `ce9616e` | 10 | CI가 잡은 시간대 의존 테스트 수정(KST 날짜) |
| `858ef53` | 10 | 검증 계약의 `format`을 `pattern`으로, Q6(b) 명시 테스트, 설계서 Q1·Q14 |

주요 추가 파일:

| 영역 | 파일 |
|---|---|
| DB | `V9__anchor_retention.sql`, `V10__disclosure_destroyed_event.sql`, `V11__legal_hold_reason_destroy.sql`, `docker/postgres/init-roles.sql`(파기자·정의자 롤), `docs/db-error-codes.md` GD110~GD114 |
| 계약 | `contracts/verify/v1/{verify-report, anchor-receipt-export, destruction-report}.schema.json`, 이벤트 `DisclosureDestroyed`(payload·샘플·envelope), 매니페스트 `anchor` 추가형, 룰 스키마(`retentionDays`·`anchoring`·`retention`·`legalHold*`·`customerRef`·`verify`), `CHECKSUMS` |
| audit(순수) | `anchor/{MerkleTree, AnchorRecord, MerkleSpec}`, `chain/{SealChainWalker, ChainBreak, SealLink}`, `AuditChainWalker`, `tsa/{TimestampAuthorityPort, TimestampClient, TimestampVerifier, TrustAnchors, …}`, `tsa/stub/LocalStubTsa`, `tsa/http/HttpTimestampAuthority`, `verify/{PackageVerifier, VerifyReport, ReportBuilder, FindingCode, Statements, ReceiptExport, EvidenceZip, VerifySchemas}` |
| sign(순수) | `retention/RetentionDecision` |
| 워크플로 | `anchor/{AnchorJob, AnchorStore, AnchorReceipt}`, `verify/{TenantVerifier, ReceiptExporter, SealChainReader}`, `retention/{DestructionJob, LegalHoldService, DestroyerPort, ErasureReader, RetentionStore, LegalHoldStore, …}`, `WorkflowTransactions.inTenantRepeatableRead` |
| 인프라 | `persistence/{AnchorRepository, SealChainRepository}`, `retention/{DestroyerGateway, ErasureRepository, RetentionRepository, LegalHoldRepository}`, `S3ArtifactStore`(버전 ID 삭제·legal hold·능력) |
| 앱·데모 | `RetentionConfiguration`, CLI `RetentionCommands`·`CliExit`, `demo/{DemoClockConfiguration, DemoKeysGuard}`, `demo/bundles/rules/DISC-DEMO-SHORT.bundle.json`, `phase5-seed.json`, `disclosures-demo3.json`, `seed.sh` Phase 5 절 |
| 문서 | `docs/third-party.md`(BC 1.86, OSV 조회 기록) |

## 2. 지시문 추가 보고 6항목

### ① 머클 규격 블록 (기계 판독)

정본은 설계서 §6.7의 `merkle-spec` 블록이다.
- `MerkleSpecTableTest.designBlockEqualsTheCodeBothWays`가 코드 상수(접두·패딩 라벨·잎 키·깊이 키·기본·최대 깊이·정렬·경로 방향)와 양방향으로 대조한다.
- 주입 M1~M4가 이 대조와 트리 테스트를 깬다.

```merkle-spec
key,value
hash,SHA-256
leafPrefix,00
nodePrefix,01
padPrefix,02
padLabel,ga-disclosure/anchor/pad/v1
leafInput,JCS(anchorDate|anchorSeq|auditHead|auditSeq|sealChainHead|sealChainSeq|tenantId|v)
recordVersion,1
leafOrder,leafHashUnsignedAscending
depthKey,anchoring.treeDepth
defaultDepth,16
maxDepth,24
maxLeaves,2^depth
pathOrder,leafToRoot
pathSide,bit(leafIndex;level)=0 -> sibling right
imprint,root
```

- **접두 분리**: 잎 `00`, 노드 `01`, 패딩 `02`가 서로 다르다. 그래서 잎을 노드로, 노드를 잎으로 내미는 두 번째 원상 공격이 성립하지 않는다(`MerkleTreeTest.domainsAreSeparated`).
- **고정 깊이**: 깊이가 고정이라 경로 길이는 테넌트 수와 무관하게 `treeDepth`다(`everyPathHasTheFixedDepthAndReachesTheRoot` — 잎 1·2·17개 등). 잎 수가 `2^depth`를 넘으면 자르지 않고 `TREE_FULL`로 거부한다.
- **한계**(설계서에 적었다): 패딩이 상수라서, 경로의 형제 중 전부 패딩인 부분트리 상수의 위치로 점유 슬롯 수의 **상한**을 추정할 수 있다. 정확한 수와 다른 테넌트의 잎·머리 값은 드러나지 않는다(`AnchorIsolationIT.aTenantsRowsCarryNothingOfTheOtherTenant`).

### ② `verify` 보고서 예시 3건

(a)·(b)는 데모 1회째 DEMO2 A-4-SCAN(종이 스캔 → 관리자 확인 완료)의 실제 출력이다(`build/demo/phase5/`). (c)는 통합 테스트 조립에서 슈퍼유저가 트리거를 끄고 봉인 `chain_hash` 하나를 바꾼 뒤 돌린 실제 출력이다. 저장 형태는 JCS이고, 아래는 읽기 쉽게 들여쓴 것이다.

**(a) `verify package` — 영수증 없음, 종료 0**

```json
{
  "checks": [
    {"check": "MANIFEST_SCHEMA", "count": 1, "status": "PASS"},
    {"check": "ENTRY_HASHES", "count": 7, "status": "PASS"},
    {"check": "CANONICAL_JCS", "count": 1, "status": "PASS"},
    {"check": "SIGNED_PDF_PREFIX", "count": 1, "status": "PASS"},
    {"check": "SIGNATURE_BINDING", "count": 3, "status": "PASS"},
    {"check": "AUDIT_ENTRIES", "count": 17, "status": "PASS"},
    {"check": "RECEIPT_SCOPE", "count": 0, "status": "SKIPPED"},
    {"check": "SEAL_CHAIN_SEGMENT", "count": 0, "status": "SKIPPED"},
    {"check": "RECEIPT_PATH", "count": 0, "status": "SKIPPED"},
    {"check": "TSA_TOKEN", "count": 0, "status": "SKIPPED"}
  ],
  "conclusion": {"existedBefore": null, "sealedAfter": null, "tsaTrusted": null},
  "counts": {"anchors": 0, "auditRows": 17, "disclosures": 1, "objects": 7, "receipts": 0},
  "findings": [],
  "inputs": {
    "asOf": "2026-10-03T05:07:29.440419Z", "from": null,
    "package": {"bytes": 122263, "sha256": "a2a84f62543489fe221f56192433156462e7df849c2352067184d8a9d6cb0206"},
    "receipt": null, "tenantId": "DEMO2", "to": null, "trust": null
  },
  "kind": "PACKAGE", "reportVersion": 1, "result": "MATCH",
  "statements": ["내부 정합성만 확인. 존재 시각·체인 연속은 영수증 또는 verify tenant가 필요하다."],
  "verifierVersion": "ga-disclosure-verify/1"
}
```

**(b) `verify package` — 영수증 + 신뢰 앵커, 종료 0.** `checks`의 영수증 4항목(범위·체인 구간·경로·토큰)이 PASS이고, 나머지 필드는 (a)와 같다.

```json
{
  "conclusion": {
    "existedBefore": "2026-10-03T05:05:49Z",
    "sealedAfter": {"anchorDate": "2026-10-02", "recordedAt": "2026-10-03T05:05:43.369932Z", "sealChainSeq": 0},
    "tsaTrusted": true
  },
  "counts": {"anchors": 2, "auditRows": 17, "disclosures": 1, "objects": 7, "receipts": 1},
  "findings": [],
  "inputs": {
    "receipt": {"bytes": 4006, "sha256": "1f7dd8b6bf3f178a2803508e30e3c146f9fa9092ef936b8f0f9c03971ff8ae5b"},
    "trust": {"bytes": 688, "sha256": "0db656532d1ad251da8577d70c4ae898ed57104834c90abc170af1170043d644"},
    "tenantId": "DEMO2"
  },
  "result": "MATCH",
  "statements": [
    "감사 체인 연속은 verify tenant만 확인한다. 패키지의 감사 발췌는 행별 해시만 검사했다.",
    "이 문서는 2026-10-03T05:05:49Z 이전에 이 내용으로 존재했다.",
    "이 문서는 2026-10-02 앵커의 봉인 체인 머리(seq 0, 기록 시각 2026-10-03T05:05:43.369932Z) 뒤에 봉인되었다. 하한의 시각은 자체 기록이며 외부로 증명되는 것은 상한(2026-10-03T05:05:49Z 이전)뿐이다."
  ]
}
```

- **하한의 seq 0.** 첫 날(2026-10-02) 앵커는 DEMO2의 첫 봉인보다 먼저 만들어졌다. 그래서 이 문서의 하한은 "빈 체인 뒤"다. 문장은 참이지만 정보량은 작다.
- **seq가 0보다 큰 하한**: `VerifyPackageTest.withTheReceiptTheUpperBoundIsTheTsaTimeAndTheLowerBoundIsSelfRecorded`가 seq 1을 문장 그대로 단언한다.

**(c) `verify tenant` — 불일치 1건(봉인 `chain_hash` 변조), 종료 2, `CHAIN_BROKEN` 플래그(그 확인서)**

```json
{
  "checks": [
    {"check": "AUDIT_CHAIN", "count": 39, "status": "PASS"},
    {"check": "SEAL_CHAIN", "count": 1, "status": "FAIL"},
    {"check": "NUMBERING", "count": 1, "status": "PASS"},
    {"check": "DESTRUCTION_AUDIT", "count": 0, "status": "PASS"},
    {"check": "OBJECTS", "count": 8, "status": "PASS"},
    {"check": "ANCHORS", "count": 1, "status": "PASS"},
    {"check": "RECEIPTS", "count": 1, "status": "PASS"}
  ],
  "conclusion": {"existedBefore": null, "sealedAfter": null, "tsaTrusted": null},
  "counts": {"anchors": 1, "auditRows": 39, "disclosures": 1, "objects": 8, "receipts": 1},
  "findings": [
    {"code": "SEAL_CHAIN_BROKEN", "detail": {"kind": "HASH_MISMATCH"},
     "where": {"chainSeq": 1, "disclosureId": "ec18caa0-5f05-4fb1-a493-46360c107cde"}}
  ],
  "inputs": {"asOf": "2026-09-23T02:10:00Z", "from": null, "package": null, "receipt": null, "tenantId": "WF_242E9A12D9BF", "to": null,
             "trust": {"bytes": 688, "sha256": "ccea154abbdb0f354e9781d44ab1b43d743ad3a413644eac540dd1e7b732086d"}},
  "kind": "TENANT", "reportVersion": 1, "result": "MISMATCH", "statements": [],
  "verifierVersion": "ga-disclosure-verify/1"
}
```

- **`ANCHORS`가 PASS인 이유**: 앵커는 "다시 계산한" 머리와 대조한다. 변조된 것은 저장된 `chain_hash`이고, 재계산 머리는 앵커가 기록한 값과 같다. 단절은 `SEAL_CHAIN`이 한 번만 보고한다(걷기가 저장된 해시로 이어 간다).
- 다른 변조 4종(감사 `entry_hash`, 객체 바이트, 앵커 잎, 영수증 경로)과 산출물 해시 변조는 `VerifyTenantIT` 각 메서드가 코드와 플래그를 단언한다.

### ③ 파기 전후 묘비 행 비교 1건과 파기 감사 행

`TombstoneIT`와 같은 조립(짧은 보존 룰 변형, 3자 터치 서명 완료 → 보존 종료 뒤 재적용 → 해제된 보류 1건(사유 텍스트 있음) → 파기)에서 슈퍼유저로 읽었다. 판정 시각은 2026-09-26 10:00 KST이다.

| 표 · 컬럼 | 파기 전 | 파기 후 |
|---|---|---|
| `disclosure.disclosure_no` · `status` · `version` | `WF_A10090BBEDBC-2026-000001` · COMPLETED · 1 | 같음 |
| `disclosure.canonical_hash` · `pdf_hash` | `14ecf9bb…` · `d27e7a3f…` | 같음 |
| `disclosure.chain_seq` · `chain_hash` | 1 · `4156ac19…` | 같음 |
| `disclosure.sealed_at` · `completed_at` · `retention_until` | 2026-09-23 10:00 · 11:10 KST · 2026-09-24 | 같음 |
| `disclosure.customer_ref` | `CR-3309…`(가명) | 같음(PSEUDONYM, 남김) |
| `disclosure.destroyed_at` · `destroyed_by` | NULL · NULL | 2026-09-26 10:00 KST · `system:destruction` |
| `signature` 3행 `signer_role`·`channel`·`method`·`signed_at`·`signed_doc_hash`·`signed_pdf_hash` | CUSTOMER TOUCH_PAD / AGENT SSO / MANAGER SSO, 두 해시 = 문서 | 같음 |
| `signature.device` | `{fingerprint, userAgent}` ×2 | NULL |
| `signature.ip` | `10.0.0.7`(고객) | NULL |
| `signature.view_evidence` | `{scrollComplete, viewSeconds: 42, viewedAt}`(고객) | NULL |
| `document_key.key_id` | `DOC-b4d0165c…` | 같음 |
| `document_key.wrapped_dek` | 61바이트 | NULL(`shredded_at`·`shredded_by` 기록) |

감사 두 행이다. 같은 실행에서 seq 51과 52로 이어지고, 체인 해시가 붙는다.

```json
{"seq": 51, "action": "DOCUMENT_KEY_SHREDDED", "at": "2026-09-26T01:00:00Z", "actor": "system:destruction", "role": "SYSTEM",
 "targetKind": "DISCLOSURE", "targetId": "6887e034-8dfe-41dc-b74b-a5cde7990b16",
 "detail": {"asOf": "2026-09-26", "ruleVersionId": "DISC-2026-07",
            "erased": [{"table": "document_key", "column": "wrapped_dek", "row": "DOC-b4d0165c3a155726f1e185553b00a55f",
                        "repr": "sha256-stored", "value": "bbb52b1536c03f5b161c71852f7e3f8cae0ed91c7fcb04a5f3d6b700ffb10946"}]},
 "entryHash": "ba9f3495bdf0cba32f7f47fad48e3c230f9c7eb69876d28ece7707c63ecd42f1"}
{"seq": 52, "action": "DISCLOSURE_DESTROYED", "at": "2026-09-26T01:00:00Z", "actor": "system:destruction", "role": "SYSTEM",
 "targetKind": "DISCLOSURE", "targetId": "6887e034-8dfe-41dc-b74b-a5cde7990b16",
 "detail": {"asOf": "2026-09-26", "ruleVersionId": "DISC-2026-07", "objectsDeleted": 8, "anchorsWaived": ["CONTRACT_DATE"],
            "erased": [
              {"table": "signature", "column": "device", "row": "14391224-…", "repr": "presence", "value": "present"},
              {"table": "signature", "column": "device", "row": "1ba7165e-…", "repr": "presence", "value": "present"},
              {"table": "signature", "column": "ip", "row": "1ba7165e-…", "repr": "presence-family", "value": "4"},
              {"table": "signature", "column": "view_evidence", "row": "1ba7165e-…", "repr": "sha256-jcs", "value": "d37341371f0c5abb…"},
              {"table": "sign_session", "column": "view_evidence", "row": "11135293-…", "repr": "sha256-jcs", "value": "d37341371f0c5abb…"},
              {"table": "legal_hold", "column": "reason_text", "row": "0d369bc9-…", "repr": "sha256-utf8", "value": "f53d51214a9c1b26…"}]},
 "entryHash": "362945a0765916631d9e23f5549d03b94836578f2dc0357cdd32dbfe51ca21e3"}
```

- **표현(승인 Q3)**:
  - 암호문·감싼 키는 저장된 바이트의 SHA-256이다. 평문 해시는 쓰지 않는다.
  - IP·기기는 존재와 주소 계열만 남는다(작은 정의역의 해시는 값 자체이기 때문).
  - JSONB는 JCS 바이트의 해시이고, 평문 자유 텍스트는 UTF-8 해시다.
- **사전 계산과의 대조**: `TombstoneIT.onlyTheDesignatedColumnsBecomeNullAndTheAuditHashesMatchTheErasedValues`가 이 값들을 파기 전 행에서 따로 계산해 감사와 같음을 단언한다. 같은 테스트가 9개 표의 모든 행에서 지정 컬럼만 NULL이 되고 나머지 컬럼은 그대로임도 확인한다.
- **이 사례에 없는 값**: 추천사유 텍스트·무효 사유·증권번호는 이 사례에 값이 없어서 감사에 나오지 않는다(코드만 있는 추천사유). 값이 있는 경우는 `DestroyerRoleIT.destructionNullsOnlyTheDesignatedColumnsAndLeavesATombstone`(종료 상태 4종)이 본다.

### ④ 개인정보 컬럼 전수 표

정본은 설계서 §9 `pii-columns` 블록이다. `PiiColumnTableTest`(통합 소스셋 — DB 카탈로그와 `pg_proc`를 읽는다)가 다음을 확인한다.
- 세 함수의 `UPDATE … SET c = NULL` 집합과 블록의 함수별 행을 양방향으로 대조한다.
- 카탈로그의 BYTEA, `*_enc`·`wrapped_*` 이름, 암호문 형식 CHECK가 걸린 컬럼이 블록이나 비개인정보 열거(`anchor_receipt.tsa_token` — 폐기 항목이 남으면 실패)에 있어야 한다.
- `customer_ref` 가명 컬럼과 감사·아웃박스 JSON의 `customerRef` 키는 PSEUDONYM 행이어야 한다.
- **블록에서 어느 한 줄을 빼도 실패한다**(`removingAnyLineFromTheBlockFails`가 21줄 전부를 하나씩 빼 본다).

```pii-columns
table,column,kind,erasedBy,auditRepr,keeps
customer_ref,name_enc,ENCRYPTED,ga_customer_ref_destroy,sha256-stored,customer_ref·enc_key_id·created_at·destroyed_at
customer_ref,phone_enc,ENCRYPTED,ga_customer_ref_destroy,sha256-stored,same row
customer_ref,birth_date_enc,ENCRYPTED,ga_customer_ref_destroy,sha256-stored,same row
customer_ref,crm_customer_id,EXTERNAL_ID,ga_customer_ref_destroy,sha256-utf8,same row
document_key,wrapped_dek,KEY,ga_document_key_shred,sha256-stored,key_id·shredded_at·shredded_by
disclosure,void_reason_text,FREE_TEXT,ga_disclosure_destroy,sha256-utf8,void_reason_code
disclosure,supersede_reason_text,FREE_TEXT,ga_disclosure_destroy,sha256-utf8,supersede_reason_code
disclosure,policy_no,EXTERNAL_ID,ga_disclosure_destroy,sha256-utf8,disclosure_no·status·hashes·chain·times
recommendation,reason_text,FREE_TEXT,ga_disclosure_destroy,sha256-utf8,reason_codes·item link
review,reason,FREE_TEXT,ga_disclosure_destroy,sha256-utf8,rule·target hash·approver·time
signature,device,DEVICE,ga_disclosure_destroy,presence,role·channel·method·signed_at·both hashes
signature,ip,NETWORK,ga_disclosure_destroy,presence-family,same row
signature,view_evidence,BEHAVIOR,ga_disclosure_destroy,sha256-jcs,same row
sign_session,view_evidence,BEHAVIOR,ga_disclosure_destroy,sha256-jcs,session status·times·pinned hashes
compliance_flag,policy_no,EXTERNAL_ID,ga_disclosure_destroy,sha256-utf8,type·status·resolution·times
legal_hold,reason_text,FREE_TEXT,ga_disclosure_destroy|ga_customer_ref_destroy,sha256-utf8,reason_code·placed/released by·times (released holds only)
customer_data_key,wrapped_key,KEY,RETAINED,-,tenant key lifecycle GD060-062 (not per customer)
disclosure,customer_ref,PSEUDONYM,RETAINED,-,tombstone link; meaningless once customer_ref is destroyed
legal_hold,customer_ref,PSEUDONYM,RETAINED,-,hold history; meaningless once customer_ref is destroyed
audit_log,detail.customerRef,PSEUDONYM,RETAINED,-,hash chain; pseudonym only
outbox_event,payload.customerRef,PSEUDONYM,RETAINED,-,published contract history; pseudonym only
```

- **지시문 최소 목록 밖에서 더한 것**:
  - 계획 Q4의 5개: `crm_customer_id`, `policy_no` 두 곳, `review.reason`, `sign_session.view_evidence`.
  - 구현 중 찾은 `legal_hold.reason_text`: V9 표와 함수에 없었다. 운영자가 쓰는 자유 텍스트라 고객 사정을 담을 수 있으므로, 승인 Q4의 "경계 사례는 지우는 쪽"에 따라 V11로 더했다. 해제된 보류만 지운다. 활성 보류가 있으면 파기 자체가 GD114다.
- **인벤토리에서 PII가 아니라고 판단한 것**:
  - `sign_session.revoke_reason`(닫힌 코드)
  - `signature.identity_check`(수단·결과·시각만)
  - `signature.scan_match`(불리언·길이만)
  - `catalog_import.file_name`(운영자 파일명)
- **객체**는 컬럼이 아니라 ①·②로 지운다. 대상은 `document_artifact`의 CANONICAL_JSON·PDF·SIGNED_PDF·EVIDENCE_ZIP과 `signature_evidence`의 STROKES·IMAGE·SCAN이다. `DestructionOrderIT.aFullRunShredsDeletesEveryVersionAndLeavesATombstone`이 모든 키의 버전·마커 0을 단언한다.

### ⑤ `ArtifactStoreContract` 신규 항목의 SeaweedFS 결과

대상은 `chrislusf/seaweedfs@sha256:4e61d15f…`(4.48, compose·하네스와 같은 digest)이다. 계약은 SeaweedFS 어댑터(`SeaweedArtifactStoreIT`)와 미지원 대역(`LegalHoldUnsupportedStoreIT`, `FailingPorts.Store`) **둘 다**에 돈다.

| 계약 항목 | SeaweedFS | 미지원 대역 |
|---|---|---|
| `capabilitiesAreExplicit` | `legalHold = SUPPORTED` | `UNSUPPORTED`. `setLegalHold`·`legalHold`는 `UnsupportedCapabilityException`이다(조용한 no-op 없음) |
| `deleteRemovesEveryVersionAndMarker` | 두 버전 + 마커 1 → ID별 삭제 → `versions=0 markers=0`, 객체 404 | 같음(대역은 위임) |
| `deletingAMissingKeyCreatesNothing` | 마커 0(버전 없는 삭제를 보내지 않으므로) | 같음 |
| `adapterNeverSendsAVersionlessDelete`(B1) | SDK 실행 인터셉터가 모든 `DeleteObject`에 `versionId`가 있음을 캡처로 단언 | — |
| `legalHoldSetGetRelease` | 모든 버전 ON → 조회 ON → 보류 중 삭제 `403` → OFF → 삭제 OK | 예외 |
| `heldVersionCannotBeDeletedEvenAfterRetention`(B1) | 보존 만료 뒤에도 hold ON이면 삭제 `403`(보류가 보존과 독립) | 예외 |
| 3B 항목(잠긴 버전 삭제·우회 헤더·단축 거부·연장 허용·잠기지 않은 삭제) | 그대로 통과 | 그대로 |

- 계획 단계 스파이크(커밋하지 않음)에서 찾은 사실: **없는 키를 버전 없이 삭제해도 삭제 마커가 생긴다.** 그래서 어댑터는 나열 기반 ID 삭제만 한다(주입 S1·S2).
- 또 하나의 사실: 과거 시각의 retain-until 설정은 `400 InvalidRequest`이다(AWS와 같다). 이것이 Q6(b) `RETENTION_ALREADY_ELAPSED` 경로의 이유다.
- 설계서 §9: "DB 보류가 통제(배치 건너뜀), 저장소 보류는 벨트"를 지원 여부와 무관하게 적었다.

### ⑥ Phase 6 질문 — §7

## 3. 테스트와 완료 기준

### 테스트 수 (`858ef53`에서 `./gradlew check --continue`)

| 모듈 / 스위트 | Phase 4 | Phase 5 |
|---|---|---|
| platform-core test | 3,058 | 3,058 |
| platform-canonical test | 1,054 | 1,054 |
| platform-spring test | 13 | 13 |
| disclosure-domain test | 1,085 | 1,085 |
| disclosure-rules test | 1,334 | 1,366 |
| disclosure-workflow test | 1,136 | 1,141 |
| disclosure-seal test | 57 | 63 |
| disclosure-sign test | 487 | 491 |
| disclosure-audit test | 3 | 141 |
| disclosure-app archTest | 44 | 46 |
| disclosure-infra integrationTest | 3,453 | 3,566 |
| disclosure-app integrationTest | 9 | 13 |
| **합계** | **11,733** | **12,037** (실패 0, 스킵 0) |

- **증가분**:
  - audit 순수: `VerifyPackageTest` 28, `TsaStubTest` 14, `MerkleTreeTest`(경로 길이 매개변수 포함), `DestructionReportSchemaTest` 18.
  - infra: V9 트리거(`DestroyerRoleIT` 19, `AnchorGuardIT`, `LegalHoldGuardIT`, `V9MigrationIT`), 앵커·검증(`AnchorJobIT` 6, `VerifyTenantIT` 8, `ReceiptExportIT` 2, `AnchorIsolationIT` 2), 파기(`DestructionOrderIT` 8, `TombstoneIT` 3, `LegalHoldIT` 5, `CustomerRefDestructionIT` 4, `DestructionRulesAsDataIT` 3, `PiiColumnTableTest` 4), 저장소 계약 신규 항목.
- **집계 기준**: 결과 XML(`*/build/test-results/*/*.xml`, 실패·오류·스킵 합 0)이다.
- **B3 실행 끝 스캔**: `elapsed-retention scan: harness=started, hits=18, allowed=24, controls=18`.
  - `RETENTION_ALREADY_ELAPSED`가 나온 테넌트 18개는 모두 짧은 보존 테넌트 열거 안이다.
  - 보존 종료 뒤 재적용을 부른 대조군 18개를 스캔이 전부 봤다.

### 완료 기준

| # | 기준 | 증거 |
|---|---|---|
| G1 | 앵커: 테넌트·날짜당 1행, 재실행 NOOP, 잎 = JCS 해시(앱·DB 일치), 두 머리 한 시점, `audit_seq` = 자기 `ANCHOR_CREATED` 직전, KST 날짜, GD110 | `AnchorJobIT`(6): `everyTenantGetsOneAnchorAndOneRootIsStampedForAll`, `aSecondRunOnTheSameDayChangesNothing`, `headsComeFromOneSnapshotEvenWhenASealCommitsInBetween`(봉인 병행 주입 — 재시도 1회 이상, 앵커 머리 = 그 seq의 실제 값), `theKstDayIsTheAnchorDateEvenAfterUtcMidnight`, `anEarlierDayIsNotAnchoredAfterALaterOne`. `AnchorGuardIT.anchorsFollowTheChainsAndTheLeafFormula`(GD110, SQL과 독립인 Java 식) |
| G2 | 머클: 경로 길이 = d, 재계산 = 루트, 패딩 상수, 도메인 분리, `2^d+1` 거부, 규격 블록 양방향, DB 경로 재계산 | `MerkleTreeTest`(`everyPathHasTheFixedDepthAndReachesTheRoot`, `anyLeafAndItsPathRecomputeTheRoot`, `paddingIsAConstantOfItsOwnDomain`, `domainsAreSeparated`, `moreLeavesThanSlotsAreRefused`), `MerkleSpecTableTest.designBlockEqualsTheCodeBothWays`, `AnchorGuardIT.receiptsMustReachTheRootFromTheirLeaf`(GD111) |
| G3 | 테넌트 격리, 허용 목록 | `AnchorIsolationIT`(2): `aTenantsRowsCarryNothingOfTheOtherTenant`, `rlsKeepsAnotherTenantsAnchorsAndReceiptsOutOfReach`. ArchUnit `DB_INFRASTRUCTURE` 허용 목록에 `infra.retention.DestroyerGateway` 하나만 추가했다(설계서 §9 표의 "예정" 행을 "현재"로) |
| G4 | TSA: 스텁 VALID, 변조·다른 키·신뢰 없음, TSA 불가 → 둘째 배치, BC 범위, 락 1.86 | `TsaStubTest`(14): `stubTokenIsValidAgainstItsTrustAnchor`, `anotherRootIsAnImprintMismatch`, `aReplyForAnotherNonceIsRejectedAtAcceptance`, `aTokenFromAnotherKeyIsUntrusted`, `withoutTrustAnchorsAValidSignatureIsStillUntrusted`, `aRealTsaShapedChainVerifiesToTheRootCa` 외. `AnchorJobIT.aTsaOutageLeavesAnchorsAndTheNextRunStampsThemInASecondBatch`. `ArchitectureRulesTest.bouncyCastleOnlyInTsaPackages`, `BouncyCastlePinTest` |
| G5 | `verify package`: 정상 0, 변조 각각 2 + 코드, 손상 3, 영수증 유·무 문장, 다른 문서 영수증 2 | `VerifyPackageTest`(28): `anUntouchedPackageMatchesAndSaysOnlyInternalConsistencyIsShown`, `withTheReceiptTheUpperBoundIsTheTsaTimeAndTheLowerBoundIsSelfRecorded`(Q13 두 문장 각각), `aTamperedEntryIsAnEntryMismatch`, `aSignedPdfThatDoesNotStartWithTheSealedPdfIsReported`, `aSignatureBoundToAnotherDocumentIsReported`, `anEditedAuditRowIsReportedByItsSeq`, `aTamperedPathIsAnInvalidReceiptPath`, `aFlippedTokenByteIsAnInvalidToken`, `aReceiptForAnotherDocumentIsRejected`, `aReceiptWhoseAnchorStopsBeforeTheDocumentDoesNotCover`, `unreadableInputsAreInputErrorsNotReports`(3), `formatsAreEnforcedByPatterns`(8). CLI 종료 코드 `Phase5CliIT.anchorReceiptAndBothVerifyCommands`(0·0·3) |
| G6 | `verify tenant`: 정상 0, 변조 5종 각각 2 + `CHAIN_BROKEN`, 파기 건 부재 정상·존재 시 `OBJECT_NOT_DELETED`, `ANCHOR_UNSTAMPED` | `VerifyTenantIT`(8, 각 변조 메서드 — §2 ② (c)), `TombstoneIT.afterDestructionTheChainNumberingAndVerifyStillHold`(파기 건 섞여도 0), `TombstoneIT.aReappearingObjectAndAnUnauditedDestructionAreFound`(`OBJECT_NOT_DELETED`·`DESTRUCTION_UNAUDITED`) |
| G7 | 파기 롤: 권한 = 함수 EXECUTE뿐, 직접 UPDATE 거부, 함수 판정, 앱 표식 무효, 지정 외 컬럼 거부 | `DestroyerRoleIT`(19): `theDestroyerHoldsOnlyTheThreeFunctionsAndTheAppMayOnlySwitchToIt`, `theAppRoleCannotCallTheFunctionsWithoutSetRole`, `theDestroyerRoleCannotTouchTablesDirectly`, `theMarkerAloneOpensNothingForTheAppRole`, `theDefinerWithoutTheMarkerIsRejectedByTheTriggers`, `insideTheBranchOnlyTheDesignatedColumnsMayChange`, `theFunctionsRefuseWhatIsNotYetDestroyable`, `aHoldReasonTextIsErasedOnlyOnAReleasedHoldInsideTheBranch`(V11), `theRoleSwitchEndsWithTheTransactionEvenAfterAFailure` |
| G8 | 순서·재시도: ⓪ 미만료 → 변경 0, ① 뒤 중단 → ②③만, ③ 직전 중단 → ③, 잠금 거부 → ③ 미진입, 끝나면 버전·마커 0 | `DestructionOrderIT`(8): `locksNotYetRecordedAsExpiredChangeNothing`, `anInterruptionAfterTheShredResumesAtTheObjects`, `anInterruptionBeforeTheTombstoneFinishesOnTheNextRun`(거부된 트랜잭션의 감사 롤백 포함), `aStorageLockRefusalStopsBeforeTheTombstone`, `aFullRunShredsDeletesEveryVersionAndLeavesATombstone`, `aDryRunJudgesButWritesNothing`, `theDestroyerRoleNeverOutlivesTheCallEvenAfterARefusal`, `theMinimumRetentionNeverElapsesAtSealOrCompletionButDoesAfterwards`(Q6(b)) |
| G9 | 묘비 | `TombstoneIT`(3) — §2 ③ |
| G10 | 앵커 대기·룰 데이터 | `RetentionDecisionTest.everyCombinationFollowsTheFormula`(상태 × 봉인 × 계약일 × 대기 경과 × 보류 × 잠금 × 도달 × 파기 전수, DESTROY 15건), `theContractLinkWaitEndsTheDayAfterItsLastDay`. `DestructionRulesAsDataIT`(3): `theContractWaitComesFromTheRule`(대기 3일 → PENDING_ANCHOR → 다음 날 파기 + `anchorsWaived`), `anExpiredDisclosureDoesNotWaitForAContractDate`, `theRetentionLengthComesFromTheRule`. 고객 유예·방치도 룰만 바꿔(`CustomerRefDestructionIT`) |
| G11 | 보류 | `LegalHoldIT`(5): `aHoldSkipsDestructionAndItsReleaseLetsTheNextRunDestroy`(감사 2행, `retention_until` 불변, SeaweedFS hold 켬·끔), `oneActiveHoldPerTargetAndAReleaseHappensOnce`, `aCustomerHoldCoversEveryDisclosureAndKeepsTheStorageHoldItCovers`, `reasonCodesAndTextLimitsComeFromTheRule`, `withoutStorageSupportTheDatabaseHoldStillControls`. `LegalHoldGuardIT`(유일 인덱스 23505, GD112). 계약 결과 §2 ⑤ |
| G12 | 고객 | `CustomerRefDestructionIT`(4): `aLiveDisclosureEvenADraftKeepsTheCustomer`, `afterTheGraceTheEncryptedColumnsAreErasedAndTheAuditHoldsTheirHashes`(저장 바이트 해시 사전 계산), `aDestroyedCustomerNoLongerChangesAndIsStillNeverDeleted`(GD113·GD064), `aCustomerWithoutDisclosuresWaitsForAbandonmentAndAHoldKeepsIt` |
| G13 | 개인정보 컬럼 전수 | `PiiColumnTableTest`(4) — §2 ④ |
| G14 | 0~4 무손상, 평문·jqwik·BOM, 위반 주입 | 위 테스트 수, 아래 주입 기록, CI `build` |

**BOM·의존성**:
- 새 외부 의존성은 Bouncy Castle 1.86(`bcpkix`·`bcprov`·`bcutil`) 하나다. Boot BOM이 관리하지 않으므로 버전 카탈로그와 락에 고정했다.
- 그 밖의 락 변경은 BOM 관리 JUnit `junit-platform-launcher`(6.0.3)가 integrationTest 컴파일 클래스패스에 들어간 것뿐이다. B3 리스너 때문이다.
- `net.jqwik`은 락 파일에 0건이다.
- OSV·최신판 확인 기록은 `docs/third-party.md`에 있다.

### 규칙 테스트 위반 주입 기록 (주입 → 실패 확인 → 제거)

각 주입은 코드나 마이그레이션을 임시로 고쳐 대상 스위트를 돌린 뒤 원복했다. 원문은 각 커밋 메시지 본문에 있다. **굵게** 표시한 것은 지시문 G14 최소 8종과 승인 B2·B3이다.

**V9**(`5075aac`):

| # | 주입 | 실패 |
|---|---|---|
| **V1** | **트리거 파기 분기가 롤을 보지 않음(표식만)** | `DestroyerRoleIT` 1 |
| **V2** | **트리거 파기 분기가 표식을 보지 않음(롤만)** | `DestroyerRoleIT` 1 |
| V3 | 앱 롤이 함수를 직접 EXECUTE(B2) | `DestroyerRoleIT` 1 |
| V4 | 파기자 롤에 컬럼 권한(B2) | `DestroyerRoleIT` 1 |
| V5 | 분기가 지정 외 변경 허용 | `DestroyerRoleIT` 1 |
| V6 | 함수가 보존 도래 검사 생략 | `DestroyerRoleIT` 1 |
| V7 | 함수가 보류 검사 생략 | `DestroyerRoleIT` 1 |
| V8 | 파기된 고객 재기입 | `DestroyerRoleIT` 1 |
| V9 | GD110 잎 재계산 제거 | `AnchorGuardIT` 1 |
| V10 | GD110 무간격 앵커 seq 제거 | `AnchorGuardIT` 1 |
| V11 | GD111 경로 재계산 제거 | `AnchorGuardIT` 1 |
| V12 | GD112 해제 2회 허용 | `LegalHoldGuardIT` 1 |
| V13 | `audit_anchor`를 테넌트 FORCE RLS 상태로 셈 | `V9MigrationIT` 1 |
| V14 | 표식 없이 키 파기 | `SealTriggerIT` + `DestroyerRoleIT` 2 |

**룰**(`30a83ec`): R1 `retentionYears` 테넌트 개방, R2 스키마의 합계 ≥ 1일 제거, R5 옛 `anchor` 키 재허용 → `ContractSchemaTest` 각 1. R3 `RetentionPeriod` 0 허용 → `RetentionAnchorsTest` 2. R4 일수 무시 → 1.

**audit 순수**(`25fbb40`):

| # | 주입 | 실패 |
|---|---|---|
| **M1** | **잎 도메인 접두 제거(잎 = SHA-256(JCS))** | `AnchorRecordTest`·`MerkleTreeTest` 2 |
| M2 | 노드 도메인 접두 제거 | `MerkleTreeTest.domainsAreSeparated` |
| **M3** | **패딩 깊이 가변(점유 높이에서 멈춤)** | `MerkleTreeTest` 61 |
| M4 | 영수증 경로 길이 = 깊이 검사 제거 | `MerkleTreeTest` 1 |
| **T1** | **TSA 신뢰 앵커 검사 생략** | `TsaStubTest` 2 |
| T2 | 빈 신뢰 묶음이 UNTRUSTED로 먼저 나오지 않음 | `TsaStubTest` 1 |
| T3 | 서명자 EKU 검사 제거 | `TsaStubTest` 1 |
| T4 | imprint = 루트 검사 제거 | `TsaStubTest` 1 |
| T5 | 수락 때 nonce 비교 제거 | `TsaStubTest` 1 |
| C1 | 감사 걷기 checkpoint가 저장된 머리를 기억 | `ChainWalkersTest` 1 |
| C2 | 봉인 걷기가 재계산 해시로 이어 감(연쇄 단절) | `ChainWalkersTest` 2 |
| A1 | BC를 `..tsa..` 밖에서 참조 | `ArchitectureRulesTest.bouncyCastleOnlyInTsaPackages` |
| A2 | `audit.tsa`에서 BigInteger | `ArchitectureRulesTest.bigDecimalOnlyInInfraJson` |
| L1 | 카탈로그 버전을 락에서 이탈(1.85) | `BouncyCastlePinTest` |

**저장소**(`b50cd9b`): S1 버전 없는 삭제 → `SeaweedArtifactStoreIT` 6 · **S2 삭제 마커 남기기** → 2 · S3 legal hold를 최신 버전에만 → 1 · S4 삭제 중 잠금 거부 삼킴 → 5 · S5 미지원 hold가 조용한 no-op → `LegalHoldUnsupportedStoreIT` 1.

**앵커 배치**(`8b721fd`):
- N1 머리를 REPEATABLE READ 밖에서 읽음 → `AnchorJobIT.headsComeFromOneSnapshot…`.
- N2 재시도 없음 → 같은 테스트.
- N3 B단계가 이번 실행 앵커만 → `aTsaOutageLeaves…`.
- N4 다른 잎의 경로 → 3.
- N5 충돌 번역 제거 → 1.
- N6 매니페스트 anchor 늘 null → `CompletionIT` 1.

**검증**(`dd8c4f7`):

| # | 주입 | 실패 |
|---|---|---|
| **V1′** | **`verify tenant`가 감사 연속(prev) 검사 생략** | `VerifyTenantIT` 1 |
| **V2′** | **다른 테넌트 영수증 수용** | `VerifyPackageTest.aReceiptLabelledForAnotherTenantIsRejected` |
| V3′ | 영수증 없음 문장 누락 | `VerifyPackageTest` 1 |
| V4′ | 패키지 감사 행 재계산 생략 | `VerifyPackageTest` 1 |
| V5′ | 발견이 있어도 결론을 냄 | `VerifyPackageTest` 2 |
| V6′ | 운영 발견(`ANCHOR_UNSTAMPED`)에도 `CHAIN_BROKEN` | `VerifyTenantIT` 1 |
| V7′ | 객체를 존재만 확인 | **첫 시도 실패 0** → `aRewrittenArtifactHashIsAHashMismatchWithBothHashes` 추가 후 1 |
| V8′ | 복호화 실패 삼킴 | `VerifyTenantIT` 1 |
| V9′ | `verify tenant`가 영수증 경로 미검사 | `VerifyTenantIT` 1 |

**파기·보류**(`4d9e5fd`):

| # | 주입 | 실패 |
|---|---|---|
| **B2-1** | **`SET LOCAL ROLE` 없이 앱 롤로 파기 함수 호출** | `DestructionOrderIT` 5(SHRED 단계 권한 거부) |
| **B2-2** | **함수가 표식 없이 UPDATE**(정의자 롤의 직접 UPDATE와 같은 경로) | `TombstoneIT` 3(GD113) |
| S8-1 | 판정이 보류 무시 | `RetentionDecisionTest` 1 |
| S8-2 | `verify tenant`가 `DESTRUCTION_UNAUDITED` 미보고 | `TombstoneIT` 1 |
| S8-3 | `pii-columns` 블록에서 `review.reason` 줄 제거 | `PiiColumnTableTest` 1("ga_disclosure_destroy nulls review.reason which the block does not list") |
| S8-4 | V11 확인서 파기가 보류 텍스트를 남김 | `PiiColumnTableTest` 1 + `TombstoneIT` 3 |
| **B3-a** | **짧은 보존 테넌트를 열거하지 않음** | `verifyElapsedRetentionScan`(VIOLATION outside the short-retention tenants) |
| B3-b | 스캔 질의가 눈멂(사유 문자열 오타) | `verifyElapsedRetentionScan`(control unseen) |
| B3-c | 리스너 미등록 | `verifyElapsedRetentionScan`(report missing) |

**CLI·데모**(`5acfbb6`)와 보고 단계:

| # | 주입 | 실패 |
|---|---|---|
| **B2-3** | **데모 시계 오프셋 키를 데모 아닌 프로파일에서 허용**(가드 해제) | `DemoClockProfileIT` 1 |
| S9-1 | `anchor run`이 미래 날짜 허용 | `AnchorJobTest` 1 |
| **B3(계획 문구)** | **봉인 경로를 `RETENTION_ALREADY_ELAPSED` 분기로 강제** | `verifyElapsedRetentionScan`(VIOLATION … `WF_46BFAD7830DD`), `DestructionOrderIT` 2 |
| **G14-4** | **⓪ 잠금 확인 제거**(판정이 잠금을 늘 만료로 봄) | `DestructionOrderIT.locksNotYetRecordedAsExpiredChangeNothing` |
| **G14-5** | **파기 감사 해시 생략**(`erased` 비움) | `TombstoneIT` 1 + `CustomerRefDestructionIT` 1 |
| P-1 | 함수가 지정 외 컬럼(`sign_session.revoke_reason`) 하나를 더 소거 | `PiiColumnTableTest` 1 + `TombstoneIT` 3 |
| P-2 | `DISCLOSURE_DESTROYED` 감사를 함수와 다른 트랜잭션에 먼저 적재 | `DestructionOrderIT.anInterruptionBeforeTheTombstone…`(거부된 파기의 감사가 남음) + `TombstoneIT` 1 |

- **G14 최소 8종의 대응**:
  - 도메인 접두 → M1, 패딩 깊이 → M3, 트리거의 롤·표식 → V1·V2, ⓪ 잠금 → G14-4, 파기 감사 해시 → G14-5, 감사 연속 → V1′, 다른 테넌트 영수증 → V2′, 신뢰 앵커 → T1.
  - G14-4·G14-5는 단계별 커밋 때 빠뜨려 보고 단계에서 했다.
- **계획 추가분**:
  - GD110 잎 → V9, GD111 경로 → V11, 마커 → S2, `PackageVerifier`의 `java.net` → 7단계 ArchUnit(`verifyPackageIsIndependentOfTheProducerAndOfEveryEnvironment`, 단계 커밋), 지정 외 컬럼 → P-1, 블록 한 줄 → S8-3.
  - 계획의 "파기 함수가 감사 seq 대조 생략"은 승인 Q2 대안에서 성립하지 않는다. 함수는 감사를 쓰지도 보지도 않고, 감사와 함수 호출이 한 트랜잭션이다. 그 보장(거부된 파기의 감사는 남지 않는다)을 깨는 P-2로 대체했다.
- 모든 주입은 제거한 뒤 전체 check가 통과했다. 거짓 양성은 없었다.

### CI (1차 증거, `gh run view`)

**첫 run `37099082331`**(pull_request, head `5acfbb6`) — `build` failure, `pdfa-verify`·`no-docker` success.
- `DestroyerRoleIT.aCustomerIsDestroyedOnlyWithoutLiveDisclosuresOrHoldAndThenStaysTombstoned`가 `expected: …:2026-09-01 but was: …:2026-08-31`로 실패했다(3,565건 중 1건).
- 원인: 시드는 `TIMESTAMPTZ '2026-09-01 00:00:00+09'`인데 단언이 `created_at::date`였다. 세션 시간대(pgjdbc가 JVM에서 가져온다 — 로컬 Asia/Seoul, CI UTC)를 따른 것이다.
- 로컬에서 `TZ=UTC`로 재현(1 failed)한 뒤 `(created_at AT TIME ZONE 'Asia/Seoul')::date`로 고쳤다(`ce9616e`). 이어서 **전체 check를 `TZ=UTC`로 한 번 더** 돌려 다른 시간대 의존이 없음을 확인했다(통과).
- 이 테스트는 V9(2단계) 때 쓴 것이다. 로컬이 KST라 단계 커밋 때는 드러나지 않았다.

**run `37099922622`**(pull_request, head `858ef53`) — 전부 success. 로그는 `gh run view --job <id> --log`, 아티팩트는 `gh run download`로 직접 받아 확인했다.
- `build`(job `111137288279`, ubuntu-latest x64, Temurin 25.0.4)
  - `net.jqwik` 의존 그래프 검사 통과.
  - `elapsed-retention scan: harness=started, hits=18, allowed=24, controls=18`(B3, 로컬과 같은 수).
  - `scanPlaintextLeaks: 145 result files, 14 forbidden strings, 0 hits`, `BUILD SUCCESSFUL in 4m 41s`.
  - 발행 아티팩트 소비 빌드 `BUILD SUCCESSFUL`.
  - 아티팩트 `test-reports`의 모듈별 HTML 보고서 합계는 **12,037건, 실패 0, 무시 0**이다. 모듈별 수가 §3 표의 로컬 값과 하나도 다르지 않다(app archTest 46·integrationTest 13, audit 141, infra integrationTest 3,566 …).
- `pdfa-verify`(job `111137288246`)
  - `case-01`·`case-02`·`case-03`·`signed-01` 각각 `flavour=2b declared=2b compliant=true failedChecks=0`.
  - 아티팩트 `signed-01.pdf`의 SHA-256 `7a1f6a86d8758e67…`은 Phase 4 골든과 같다(Phase 5는 렌더러를 바꾸지 않았다).
- `no-docker`(job `111137288177`)
  - `334 tests completed, 334 failed`, `result files: 67, with failures/errors: 67, skipped: 0`, `OK: integration tests failed (not skipped) because Docker is missing`.
- 보고서 커밋이 올린 새 head의 CI는 PR 코멘트로 덧붙인다(문서만 바뀐다).

### CLAUDE.md 규칙 9 기록

- 지시문 형태의 문장은 0건이었다("ignore previous"·"이전 지시"·"system prompt" 패턴 검색). 대상은 다음과 같다.
  - 데모 로그 2회분(1,848행)
  - 전체 check 출력
  - CI `build` 로그(run `37099922622`)
- 데모 고객 파일의 이름·전화·생년월일(하이픈 제거형 포함) 11개 값과 테스트용 보류 사유 텍스트 2개도 두 데모 로그에서 0건이었다.
- `net.jqwik`은 락 파일과 의존 그래프에 0건이다.
- Bouncy Castle 1.86의 POM 3개와 jar의 `META-INF/LICENSE.md`를 같은 패턴(+ "AI agent"·"language model")으로 검색한 결과도 0건이었다(보고 단계, Gradle 캐시의 실제 아티팩트).

## 4. 데모

- **환경**: 새 격리 컨테이너(PostgreSQL 18.6 + `init-roles.sql`, SeaweedFS digest 고정)를 포트 15432/18333에 띄우고 `disclosure-demo/scripts/seed.sh`를 그대로 2회 실행했다(`5acfbb6`의 코드). 두 번 다 exit 0이었다.
- **사용자 볼륨을 쓰지 않은 이유**: 사용자의 compose 볼륨은 Phase 4 이전 상태라 V9가 롤이 없어 실패한다(README가 `down -v`를 안내). 그러나 그 볼륨을 지우는 것은 사용자 데이터를 지우는 일이라 하지 않았다.
- **키 위치**: KEK·스텁 TSA 키는 환경변수로 스크래치 경로를 주었다(데모 기본값은 `~/.ga-disclosure`). 실행 뒤 격리 컨테이너는 지웠다.
- **로그 발췌**: Phase 5 줄만 남겼다. UUID는 앞 8자리, 해시는 앞 12자리다.

```
(1회)
ANCHOR_RUN date=2026-10-02 created=[DEMO1, DEMO2] unchanged=[] retries={} batches=1 second=0 receipts=2
  BATCH 2026-10-02 e31dcd7b-… root=b07d37261b49… depth=16 leaves=2 genTime=2026-10-03T05:05:43Z
DEMO_DISCLOSURE DEMO2 A-4-SCAN CREATED id=9251e2bc-… status=REASONED
  A-4-SCAN seal -> SEALED no=DEMO2-2026-000001
DEMO_SIGN DEMO2 A-4-SCAN id=9251e2bc-… PAPER_SCAN -> COMPLETED
ANCHOR_RUN date=2026-10-03 created=[DEMO1, DEMO2] unchanged=[] retries={} batches=1 second=0 receipts=2
  BATCH 2026-10-03 e1a0e1af-… root=b84fcedade5f… depth=16 leaves=2 genTime=2026-10-03T05:05:49Z
RECEIPT_EXPORT DEMO2 9251e2bc-… covering=2@2026-10-03 previous=1@2026-10-02 links=1 -> build/demo/phase5/A-4-SCAN.receipt.json
ARTIFACT_GET DEMO2 9251e2bc-… EVIDENCE_ZIP sha256=a2a84f625434… bytes=122263 -> build/demo/phase5/A-4-SCAN.evidence.zip
VERIFY_PACKAGE MATCH findings=0 sha256=4699b9e704d1…
  STATEMENT 내부 정합성만 확인. 존재 시각·체인 연속은 영수증 또는 verify tenant가 필요하다.
VERIFY_PACKAGE MATCH findings=0 sha256=b609a7e08309…
  STATEMENT 감사 체인 연속은 verify tenant만 확인한다. 패키지의 감사 발췌는 행별 해시만 검사했다.
  STATEMENT 이 문서는 2026-10-03T05:05:49Z 이전에 이 내용으로 존재했다.
  STATEMENT 이 문서는 2026-10-02 앵커의 봉인 체인 머리(seq 0, 기록 시각 2026-10-03T05:05:43.369932Z) 뒤에 봉인되었다. 하한의 시각은 자체 기록이며 외부로 증명되는 것은 상한(2026-10-03T05:05:49Z 이전)뿐이다.
DEMO_DISCLOSURE DEMO3 A-6-SHORT CREATED id=c0ae1b0b-… status=REASONED
  A-6-SHORT seal -> SEALED no=DEMO3-2026-000001 RETENTION_PENDING
  A-6-SHORT void -> VOID
DEMO_DISCLOSURE DEMO3 A-7-HOLD CREATED id=5d7d520b-… status=REASONED
  A-7-HOLD seal -> SEALED no=DEMO3-2026-000002 RETENTION_PENDING
  A-7-HOLD void -> VOID
ARTIFACT_RECONCILE DEMO3 applied=4 failed=0
LEGAL_HOLD_PLACE DEMO3 disclosure=5d7d520b-… hold=88016e02-… storageHold=2
RETENTION_DESTROY DEMO3 asOf=2026-10-03T05:06:23.626979Z candidates=2 destroyed=1 wouldDestroy=0 skipped={HOLD=1} failed=0 anchorsWaived=0 holdAfterShred=0 customersDestroyed=0
  DESTROYED c0ae1b0b-… DEMO3-2026-000001 anchorsWaived=[]
  SKIPPED 5d7d520b-… HOLD [HOLD]
ANCHOR_RUN date=2026-10-03 created=[DEMO3] unchanged=[] retries={} batches=1 second=0 receipts=1
  BATCH 2026-10-03 d2c3012d-… root=0c49807c143f… depth=16 leaves=1 genTime=2026-10-03T05:06:25Z
VERIFY_TENANT DEMO1 MATCH findings=0 sha256=3a3d09095670…
VERIFY_TENANT DEMO2 MATCH findings=0 sha256=f863da7a2e96…
VERIFY_TENANT DEMO3 MATCH findings=0 sha256=97c32f9b7f4e…
(2회)
ANCHOR_RUN date=2026-10-02 created=[] unchanged=[DEMO1, DEMO2] retries={} batches=0 second=0 receipts=0
ANCHOR_RUN date=2026-10-03 created=[] unchanged=[DEMO1, DEMO2] retries={} batches=0 second=0 receipts=0
RECEIPT_EXPORT DEMO2 9251e2bc-… covering=2@2026-10-03 previous=1@2026-10-02 links=1 -> build/demo/phase5/A-4-SCAN.receipt.json
VERIFY_PACKAGE MATCH findings=0 sha256=852fe84fbe0b…
VERIFY_PACKAGE MATCH findings=0 sha256=585d88f4fe4b…
DEMO_DISCLOSURE DEMO3 A-6-SHORT NOOP existing=c0ae1b0b-… (demo rule: same customer, date, group)
  A-6-SHORT seal NOOP (already sealed)
  A-6-SHORT void NOOP (already VOID)
DEMO_DISCLOSURE DEMO3 A-7-HOLD NOOP existing=5d7d520b-… (demo rule: same customer, date, group)
  A-7-HOLD seal NOOP (already sealed)
  A-7-HOLD void NOOP (already VOID)
ARTIFACT_RECONCILE DEMO3 applied=0 failed=0
LEGAL_HOLD_PLACE DEMO3 disclosure=5d7d520b-… NOOP (an active hold exists)
RETENTION_DESTROY DEMO3 asOf=2026-10-03T05:07:55.139531Z candidates=1 destroyed=0 wouldDestroy=0 skipped={HOLD=1} failed=0 anchorsWaived=0 holdAfterShred=0 customersDestroyed=0
  SKIPPED 5d7d520b-… HOLD [HOLD]
ANCHOR_RUN date=2026-10-03 created=[] unchanged=[DEMO3] retries={} batches=0 second=0 receipts=0
VERIFY_TENANT DEMO1 MATCH findings=0 sha256=89e2ff4e3342…
VERIFY_TENANT DEMO2 MATCH findings=0 sha256=cc673969f159…
VERIFY_TENANT DEMO3 MATCH findings=0 sha256=806f5d7d7e1c…
```

- **두 날 앵커**: 어제 날짜(`--date`)의 첫 앵커는 "지금 머리를 어제 날짜로 기록"하는 것이다. 운영 의미는 누락된 날을 늦게 채우는 것이고, `created_at`이 실제 시각을 남긴다(계획 §8.8). 이미 그 뒤 날짜의 앵커가 있는 볼륨에서는 `DATE_NOT_AFTER_LATEST`로 거부되고, 스크립트는 안내 한 줄을 남긴 뒤 오늘 앵커로 잇는다.
- **DEMO3**:
  - 데모 전용 GLOBAL 번들 `DISC-DEMO-SHORT@2aa67b3e046c`(보존 0년 1일)만 받는다.
  - 봉인·무효는 `--spring.profiles.active=cli,demo --ga.demo.clock-offset=-P5D`로 했다. 실제 시각으로 지난 잠금 기한이라 저장소가 400을 내고 `RETENTION_PENDING`이 되었다(봉인은 유효).
  - 실제 시계의 `artifacts reconcile`이 객체 4개를 `RETENTION_ALREADY_ELAPSED`로 기록했다.
  - 파기는 A-6-SHORT만이다. A-7-HOLD는 보류로 건너뛰었다(`storageHold=2` — SeaweedFS 모든 버전 ON).
- **보고서 파일**: 파기 보고서(`destruction-DEMO3.json`)와 `verify tenant` 보고서 3개가 `build/demo/phase5/`(gitignore)에 남는다.
- **같은 흐름의 Testcontainers 테스트**:
  - `Phase5CliIT.shortRetentionTenantIsDestroyedExceptTheHeldDisclosure`가 같은 흐름을 반복하고, 해제 뒤 파기까지 확인한다.
  - `Phase5CliIT.anchorReceiptAndBothVerifyCommands`가 앵커 → 영수증 → 검증 종료 코드를 확인한다.

## 5. 설계서와 달리 구현했거나 해석한 지점

설계서는 해당 코드와 같은 커밋에서 v1.11 변경 이력 ①~⑨로 고쳤다. 아래는 계획·지시문의 문언과 다른 지점이다.

| # | 지점 | 이유 |
|---|---|---|
| D1 | **TSA 포트는 DER 전송만 한다**(`exchange(TimeStampReq) → TimeStampResp`). 요청 생성·수락은 `TimestampClient` 한 곳에서 한다. 계획은 `stamp(digest)` 포트였다 | 포트 구현(스텁·HTTP)마다 수락 검증을 되풀이하지 않게 하려는 것이다. nonce 비교·imprint·EKU를 한 곳에서 강제한다(주입 T1~T5) |
| D2 | nonce는 63비트 양수다. BigInteger 허용 목록에 `audit.tsa.stub`을 추가했다 | BC 생성 API(스텁의 일련번호·nonce)가 BigInteger를 요구한다. 수락·검증 쪽은 ASN.1 옥텟의 hex로만 다룬다(주입 A2) |
| D3 | 스텁의 CMS signingTime도 주입된 `Clock`이다 | BC 기본은 벽시계이다. 과거 시계의 테스트에서 "서명 시각에 인증서 무효"로 모든 토큰이 실패했다 |
| D4 | `AuditChainWalker`는 `audit` 패키지에 있다(계획 `audit.chain`) | `AuditChain`과의 패키지 순환을 피했다 |
| D5 | 저장소 포트에 `versionCount`·`legalHold(key)`(조회)를 더했다. 미지원 대역 계약 IT를 따로 둔다 | 파기 ③의 "버전·마커 0" 확인과 `OBJECT_NOT_DELETED`, 보류 상태 확인이 포트 밖에서 불가능했다 |
| D6 | `AnchorJob.run(테넌트 목록, 날짜, 행위자)`. 깊이 불일치는 fail-fast `TREE_DEPTH_DISAGREES`, 지난 날짜는 `DATE_NOT_AFTER_LATEST`, 미래 날짜는 `DATE_IN_FUTURE`(9단계)다 | 워크플로는 `compliance.TenantDirectory`에 의존하지 못한다(레이어). 미래 날짜는 계획 §8.8이 요구했으나 배치에 없어 9단계에서 더했다 |
| D7 | REPEATABLE READ 충돌 번역은 스프링 예외(`DuplicateKeyException`·`ConcurrencyFailureException`)로 한다 | `java.sql` 참조 금지 ArchUnit(워크플로) |
| D8 | 결론 필드는 `sealedAfter`다(계획 `existedAfter`). `verifierVersion`은 상수 `ga-disclosure-verify/1`이다. `CHAIN_BROKEN`은 무결성 발견만 올린다(`ANCHOR_UNSTAMPED`·`TSA_UNTRUSTED` 제외). 대상을 정하지 못하면 테넌트 수준(`raiseUnattached`, 끊긴 감사 seq·앵커를 대상으로) | `existedAfter`는 외부 증명처럼 읽힌다(승인 Q13의 약한 문장). 운영 신호에 무결성 플래그를 올리면 준법 큐가 오염된다 |
| D9 | **V9 함수는 이미 파기된 키·확인서에 GD114로 거부한다**(계획 §5.2는 NULL·`ALREADY_DESTROYED` 반환) | 배치가 단계 전에 상태(키 생존·`destroyed_at`)를 읽고 끝난 단계를 건너뛴다. 재실행의 멱등은 같고 함수는 더 엄격하다. V9를 고치지 않으려고 계획을 맞췄다 |
| D10 | **V10**: `ck_outbox_event_type`에 `DisclosureDestroyed` 추가 | V9가 이벤트 계약 추가(계획 §1.6)에 맞춰 DB 목록을 넓혔어야 했는데 빠뜨렸다. 기존 마이그레이션은 고치지 않는다 |
| D11 | **V11**: `legal_hold.reason_text` 파기와 보류 가드의 파기 분기 | 구현 중 찾은 자유 텍스트 컬럼(승인 Q4 — 표가 권위, 함수가 따라간다) |
| D12 | §14 #15 신설: 보류 **해제** 사유 코드의 룰 어휘가 없다. 형식만 검사한다(`TODO(confirm#15)`) | 설정 사유는 룰 `legalHoldReasons`인데, 해제 사유는 지시문·계획 어디에도 어휘가 없다 |
| D13 | 보존기한 전인 확인서는 후보 쿼리에서 빠진다. `RETENTION_NOT_REACHED`는 순수 판정(전수 테스트)에만 나타나고 실제 배치 보고서에는 거의 나오지 않는다 | 보존 5년의 테넌트에서 모든 확인서를 매 실행 판정하지 않게 하려는 것이다. 지시문의 사유별 집계 칸은 스키마에 그대로 있다 |
| D14 | 파기 보고서·검증 계약 스키마의 형식은 `format`이 아니라 `pattern`으로 강제한다 | 쓰는 검증기(networknt)는 `format`을 단언하지 않는다. 7단계의 두 스키마는 날짜·UUID 형식을 실제로 지키지 않고 있었다(보고 단계에서 고침, 테스트 8건) |
| D15 | `PiiColumnTableTest`는 통합 소스셋에 있다(이름은 계획대로) | `pg_proc`·카탈로그를 읽어야 한다 |
| D16 | 데모 전용 번들 `DISC-DEMO-SHORT`는 `disclosure-demo`에 있다(계획 3단계 → 9단계). 대사는 `--bundles-dir a,b`로 두 디렉터리를 정본으로 본다. `seed.sh`의 규제 번들 배포·대사는 `--tenants all` 대신 `DEMO1,DEMO2`다 | 규제 번들 디렉터리에 데모 번들을 두면 정본 목록이 오염된다. 두 GLOBAL 룰이 겹치면 해석이 Ambiguous이므로 DEMO3에 규제 번들을 주면 안 된다 |
| D17 | 스텁 TSA는 신뢰 앵커 PEM을 매번 덮어쓴다(계획은 "있으면 그대로") | 키를 다시 만들었는데 옛 인증서가 남으면 모든 영수증이 `UNTRUSTED`가 된다 |
| D18 | B3 스캔은 `disclosure-infra` 통합 테스트 JVM의 공유 DB 전체를 본다. `disclosure-app` 통합 테스트(`Phase5CliIT`)는 의도적으로 이 사유를 만들고, 다른 JVM·컨테이너라 스캔 대상이 아니다 | 승인 B3의 "Phase 0~4 전체 IT"는 infra 쪽이다. 앱 IT는 CLI 흐름 9+4건이고 봉인 경로는 infra IT가 다룬다 |
| D19 | 파기 함수 호출기 `DestroyerGateway`는 별도 DataSource가 아니라 호출자 트랜잭션의 연결이다(계획 §10 8단계 문구) | 승인 Q2 대안(한 트랜잭션 `SET LOCAL ROLE`)이 계획 본문(§5.4)을 대체했다. §10의 문구만 남은 것이다 |

## 6. 엔진

- 엔진 저장소는 손대지 않았다(E4는 Phase 6 뒤, 4 수용심사 §4).
- E4 첫 커밋에서 할 일:
  - ① 마이그레이션 번호 전역 단조 문서화.
  - ② V104 `product_key` 폭 40.

## 7. Phase 6(준법 큐·징구율·게이트 API·이벤트 피드·REST·인가·고객 공개 서명 엔드포인트) 질문

1. **인가 모델.**
   - 권장: 토큰은 주체(subject)만 준다. 역할·조직·`agent_id`는 `identity_link`에서 해석한다(CLAUDE.md 규칙 5).
   - 범위:
     - AGENT는 자기 `agent_id` 확인서만 다룬다.
     - MANAGER는 `org_path` 접두로 다룬다.
     - COMPLIANCE는 테넌트 전체를 다룬다. 열람은 전부 감사 `VIEW`.
   - 유스케이스 진입점에 인가 포트(`AuthorizationPort.require(actor, action, target)`)를 두고 컨트롤러는 얇게 둔다.
   - CLI는 지금처럼 OPERATOR이고, 운영에서는 별도 배포로 분리한다.
   - **질문**: 다음 둘을 COMPLIANCE에 줄지.
     - 법적 보류 설정·해제. 권장: 해제는 설정자와 다른 사람 — 4-eyes.
     - `verify tenant` 실행.
   - **질문**: 파기 배치를 사람 역할에 노출하지 않고 시스템 스케줄러만 실행할지(권장).
2. **REST API 표면과 계약.**
   - 권장:
     - `contracts/api/v1/disclosure-internal.openapi.yaml`을 정본으로 확장한다.
     - 쓰기 POST에 `Idempotency-Key`를 둔다.
     - 오류는 `{code, message, details}`로 하고, 업무 거부는 409/422로 한다.
     - 목록은 커서 페이지로 한다.
   - `verify tenant`·`retention destroy`처럼 긴 작업은 비동기 작업 리소스로 둔다(`202` + 상태 조회).
   - **질문**: 검증 보고서·영수증 내보내기를 REST로 노출할지, 준법 화면 전용으로 둘지.
3. **고객 공개 서명 엔드포인트.**
   - 권장:
     - 공개 인그레스를 별도로 두고 토큰 경로 하나만 연다.
     - 토큰은 경로가 아니라 헤더 또는 POST 본문으로 받는다. 경로는 액세스 로그에 남기 때문이다.
     - 거부 응답은 모든 사유에서 같은 상태·본문·지연이 되게 한다(Phase 4 D3의 남은 몫).
     - 레이트 리밋(설계서 §9의 N회)을 둔다.
     - 응답에 PDF 외 개인정보를 싣지 않는다.
   - **질문**: 원격 링크의 실제 통지 사업자(SMS·알림톡)와 발송 실패 재시도 정책.
4. **준법 큐.**
   - 권장: 열린 `compliance_flag`(CHAIN_BROKEN·대리 서명·종이 스캔 검토·RULE_DRIFT·RULE_ACTIVATION_MISSED 등)를 유형별 SLA로 보여준다.
   - 해소는 사유 코드 + 근거를 남긴다. 특히 `CHAIN_BROKEN`은 클릭으로 해소하지 않고, 원인 조치 뒤 `verify tenant`가 MATCH여야 해소할 수 있게 한다.
   - **질문**: 플래그 유형별 담당 역할과 SLA 수치(§14로 둘지).
5. **징구율.**
   - 계약 연결(`CONTRACT_DATE` 앵커, 파기 대기 `contractLinkWaitDays`)의 입력 출처가 정해져야 한다.
   - 권장: 보험사·청약 시스템의 계약 피드를 받아 `policy_no`로 확인서와 연결하고, 징구율 = 대상 계약 중 완료 확인서가 있는 비율로 한다.
   - **질문**:
     - 대상 계약 피드의 출처·형식·주기.
     - 매칭 키(`policy_no`만인지, 청약번호도인지).
     - 파기된 확인서(묘비)를 징구율 분자에 넣을지.
6. **청약 게이트 API.**
   - 권장: 청약 시스템이 "이 청약에 완료된 확인서가 있는가"를 묻는 내부 API로 한다.
     - 입력: 청약 식별자(또는 `policy_no`), 고객 참조.
     - 출력: `allowed|blocked` + 확인서 번호만 준다(개인정보 없음).
     - mTLS로 보호하고, 게이트 산식은 Phase 4 순수 함수를 그대로 쓴다.
   - **질문**: 청약 식별자의 형태와, 게이트 판정을 감사에 남길 단위(요청마다 vs 상태 변화만).
7. **이벤트 피드.**
   - 권장: 아웃박스를 테넌트별 seq 커서의 **풀 HTTP 피드**로 연다(at-least-once, 소비자가 `eventId`로 중복 제거).
   - `DisclosureDestroyed`가 추가된 것을 소비자에게 알린다(추가형).
   - **질문**:
     - 푸시(Kafka 등)가 필요한지.
     - 아웃박스 행의 보존기간 — payload에 가명 `customerRef`가 있다. 발행 확인 뒤 일정 기간 후 정리할지, 계약 이력으로 영구 보존할지(`pii-columns`는 지금 RETAINED).
8. **운영 스케줄링과 TSA 사업자.**
   - 권장: 일일 앵커·파기·만료·재적용을 외부 스케줄러(쿠버네티스 CronJob 등)가 CLI 또는 작업 엔드포인트로 부르게 한다. 리더 잠금(advisory lock)으로 중복 실행을 막는다.
   - TSA 사업자 선택·인증서 체인·폐기 확인(CRL·OCSP) 보관은 §14 #4로 남아 있다.
   - **질문**: Phase 6에서 실제 TSA 연동까지 할지, HTTP 어댑터 + 스텁으로 둘지.
9. **남은 미결정.**
   - §13 초안 보존: 봉인 전 초안을 언제 지우는지.
   - §14 #15 보류 해제 사유 어휘.
   - Q14로 옮긴 내보내기 워터마크(내보내기 API와 함께).
   - Q10 규제 변경 시 보존기한 재계산 작업.
