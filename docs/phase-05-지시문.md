# Phase 5 지시문 — 앵커·TSA·검증·파기 (v1.0)

## 역할과 맥락

`ga-disclosure` Phase 4(`phase-4`, PR #7) 수용 후. 대상은 설계서 **§6(봉인 체인·감사 체인), §9(산출물 암호화·파기), §12 Phase 5, §14 #4(TSA)** 와 `phase-04-수용심사.md` §3의 결정 1~8·승인 ①②. 스케줄 등록·공개 엔드포인트·인가는 Phase 6다. 이번 Phase는 **배치·CLI·포트·스텁**까지이며 HTTP는 없다.

이 Phase가 끝나면 다음이 테스트로 증명돼야 한다.

1. 매일 테넌트별 체인 머리(봉인·감사)가 **하나의 머클 루트**에 들어가 외부(TSA)에 고정되고, 테넌트 검증자는 자기 잎과 경로만으로 포함을 증명하며 다른 테넌트의 내용은 알 수 없다.
2. 봉인·감사·저장 객체·앵커 어디를 바꿔도 `verify tenant`가 잡는다. `verify package`는 DB 없이 패키지 내부 정합성을, 영수증이 있으면 **TSA 시각 이전의 존재**까지 증명한다.
3. 보존기간이 끝난 확인서는 **전용 롤의 함수로만** 파기되고, 번호·상태·해시·시각·체인은 묘비로 남아 체인·채번·`verify`가 파기 뒤에도 통과한다.
4. 법적 보류 중·계약일 대기 중·살아 있는 확인서가 있는 고객의 데이터는 파기되지 않는다.

## 시작 전 반영 (첫 커밋, 승인 불요 — 수용심사가 승인)

- CLAUDE.md 규칙 2에 덧붙임: *"봉인 본문은 **보존기간 중** 불변이다. 보존기간 종료 후 파기는 전용 롤의 파기 함수만이 지정 컬럼을 NULL로 바꿀 수 있고, 번호·상태·해시·시각·체인은 묘비로 남는다. 파기 감사에는 지운 값의 해시를 남긴다."*
- CLAUDE.md 작업 방식에 추가: *"PR 병합은 수용 심사 회신 뒤에만. 태그는 먼저 달아도 된다."*
- 직접 접근 허용 목록 세 번째 항목: `disclosure_destroyer` 롤(파기 함수 EXECUTE만). 3B 지시문이 `document_key` 파기를 `disclosure_migrator` 함수로 예약한 것은 **폐기** — 마이그레이터는 DDL, 파기자는 파기 함수 하나. 설계서에 대체 사실을 적는다.

## 시작 전 보고

계획에 ① V9 DDL(앵커·영수증·보류·파기 컬럼·롤·함수·트리거 예외) ② 머클 트리 규격(잎 정의·정렬·패딩 상수·깊이·해시 도메인 분리, RFC 6962식 접두) ③ TSA 포트·스텁 설계(BC 버전·라이선스·신뢰 앵커 구성·스텁 키 수명) ④ `verify` 보고서 JSON 스키마·발견 코드·종료 코드 ⑤ 파기 판정 산식·순서·재시도 지점(단계별 멱등) + **확인서에 귀속된 개인정보 컬럼 전수 목록** ⑥ `ArtifactStoreContract`의 legal hold·전체 버전 삭제 항목을 SeaweedFS에 돌린 결과 ⑦ 배치 코드의 모듈 위치(권장: `disclosure-compliance`가 조정, 키·객체는 `disclosure-seal` 포트 호출 — 레이어 규칙을 넓히면 표시)를 넣고 승인 후 진행.

## 작업 목록

### 1. V9 마이그레이션
- `anchor(tenant_id, anchor_seq, anchor_date, seal_chain_seq, seal_chain_head, audit_seq, audit_head, leaf_hash, created_at)` append-only. `UNIQUE (tenant_id, anchor_date)`. `anchor_date`는 **KST 날짜**. `leaf_hash` = 도메인 접두 + JCS(레코드)의 SHA-256. `audit_seq`는 앵커 자신의 감사 행 **직전** 머리다(앵커 감사 행은 다음 앵커가 덮는다).
- `anchor_receipt(tenant_id, anchor_seq, root_hash, tree_depth, leaf_index, merkle_path JSONB, tsa_token BYTEA, tsa_gen_time, tsa_policy_oid, tsa_serial, created_at)` append-only, 앵커당 최대 1행. **루트·토큰은 영수증 행마다 중복 저장**한다 — 테넌트 없는 배치 테이블은 두지 않는다.
- `legal_hold(tenant_id, hold_id, disclosure_id NULL, customer_ref_id NULL, reason_code, reason_text NULL, placed_by, placed_at, released_by, released_at, release_reason_code)`. 대상은 둘 중 정확히 하나(CHECK). 대상당 활성 보류 1건(부분 유일). 전이는 `PLACED→RELEASED` 1회(트리거). `reason_code`는 룰 데이터 `legalHoldReasons` 닫힌 목록, 텍스트 길이 상한은 룰 데이터.
- 파기 컬럼: `disclosure.destroyed_at`·`destroyed_by`(write-once), `customer_ref.destroyed_at`. `document_key.wrapped_dek` NULL 경로는 3B 예약 그대로(`shredded_at`·`shredded_by`).
- **파기 함수와 롤**: `disclosure_destroy(tenant_id, disclosure_id, as_of, actor)`·`customer_ref_destroy(...)`·`document_key_shred(...)`. 롤 `disclosure_destroyer`는 이 함수들의 EXECUTE **외에 어떤 권한도 없다**(테이블·컬럼 GRANT 0). 함수는 `SECURITY DEFINER`로 지정 컬럼만 바꾸고, 불변 트리거는 "함수 경유 + 지정 컬럼만 변경 + `destroyed_at` 같은 문장에서 설정"일 때만 통과(함수가 세션 설정으로 표시하고 트리거가 읽는 방식 권장 — 계획 ①에서 확정). 함수 안 검사: `retention_until ≤ as_of`, 활성 보류 없음, 미파기. 룰 의존 검사(앵커 대기 등)는 앱이 한다.
- 지정 컬럼(최소): `recommendation.reason_text`, `disclosure.void_reason_text`·`supersede_reason_text`, `signature.device`·`ip`·`view_evidence`, `customer_ref`의 이름·전화·생년월일 암호문. 전수 목록은 계획 ⑤의 것을 **설계서 §9의 기계 판독 표**(`pii-columns` 블록)로 두고, 테스트가 표와 함수의 컬럼 집합을 양방향 대조한다(G13).
- Phase 4가 미룬 **`disclosure.void_reason` 구 컬럼 제거**(2단계 전방 호환의 둘째 단계).
- GLOBAL 룰 번들에 키 추가(번들 해시 갱신 → 기존 복제 경로): `anchoring.treeDepth`(비오버라이드, 기본 16), `retention.contractLinkWaitDays`(오버라이드 가능), `legalHoldReasons`, `customerRef.graceDaysAfterLastDestruction`·`abandonedDays`(오버라이드 가능), `verify.unstampedAnchorAlertDays`. 테스트·데모 테넌트가 짧은 보존기간을 가질 수 있어야 한다 — 현재 보존 키가 연 단위뿐이면 일 단위 키를 추가하되 **산식은 하나**.
- 오류 코드 이어서 배정, `db-error-codes.md`.

### 2. 앵커 (`disclosure-audit`, Spring 무의존)
- `AnchorJob(date)`(주입된 `Clock`, 기본 = 오늘 KST): **A단계** `TenantDirectoryReader`로 테넌트를 순회하며 테넌트 트랜잭션마다 `anchor` 행이 없으면 생성(한 스냅샷에서 두 체인 머리를 읽는다 — REPEATABLE READ). 행위자는 `ExpireJob`과 같은 시스템 행위자 규약. 감사 `ANCHOR_CREATED`. **B단계** 그 날짜의 **영수증 없는 앵커 전부**를 잎으로 트리를 만들고 루트 1개를 TSA에 보내 토큰을 받은 뒤, 테넌트 트랜잭션마다 영수증을 쓴다. 감사 `ANCHOR_RECEIPT_STORED`.
- 멱등·복구: 재실행은 앵커를 다시 만들지 않는다(UNIQUE). B단계 도중 중단되면 영수증을 못 받은 앵커만 **같은 날짜의 둘째 배치**(새 루트·새 토큰)가 된다. 토큰을 복사하거나 테넌트 밖에 보관하지 않는다. 둘째 배치 발생은 보고서에 센다.
- 트리: 잎을 `leaf_hash`로 정렬, 깊이 `treeDepth` 고정, 빈 자리는 **결정론적 상수 패딩 잎**. 잎·노드·패딩은 서로 다른 도메인 접두(두 번째 원상 공격 방지). 잎 수가 `2^treeDepth`를 넘으면 **거부**(fail-fast, 보고). 경로 길이는 테넌트 수와 무관하게 `treeDepth`. 패딩이 상수이므로 경로의 상수 부분트리로 **규모의 상한은 추정 가능**하다 — 이 한계를 설계서에 적는다(정확한 수·다른 테넌트의 내용은 드러나지 않는다).
- 증거 매니페스트 `anchor` 필드: 완료 시점에 있던 **최신 앵커 참조** `{anchorSeq, anchorDate, leafHash, sealChainSeq, auditSeq}`(없으면 null). 스키마 `evidence-manifest.schema.json`에 객체 형태를 추가(추가형, CHECKSUMS 갱신). 패키지는 **다시 만들지 않는다**.
- 이벤트: 앵커는 이벤트 없음(감사만).

### 3. TSA (`disclosure-audit`의 `..tsa..`)
- 포트 `TimestampAuthorityPort.stamp(rootHash)`: RFC 3161 요청(SHA-256 imprint, 난수 nonce, `certReq=true`). `TimestampVerifier.verify(token, rootHash, trustAnchors)`: imprint·nonce 일치, 서명자 인증서가 **설정된 신뢰 앵커**로 체인, 토큰 서명 유효, `genTime`·정책 OID·일련번호 추출. 신뢰 앵커 없이는 검증 결과 `UNTRUSTED`(통과 아님).
- 의존성: `org.bouncycastle:bcpkix-jdk18on`(+ `bcprov`·`bcutil` 동일 버전). Maven Central 기준 2026-10-02 확인 시점 최신은 **1.86**(약 3주 전 배포) — 착수 때 재확인하고 OSV에서 그 버전의 공개 취약점을 조회해 보고서에 적는다. Boot BOM 비관리이므로 버전 카탈로그 + 락 파일 고정, 라이선스(Bouncy Castle License, MIT 계열)를 서드파티 목록에 기록. ArchUnit: `org.bouncycastle..` 참조는 `..disclosure.audit.tsa..`와 테스트 픽스처만.
- 스텁 TSA(자체 서명, BC `TimeStampResponseGenerator`): 테스트 픽스처와 데모 프로파일. **키는 커밋하지 않는다** — 테스트는 실행마다 생성, 데모는 시작 시 생성해 신뢰 앵커 인증서를 파일로 내보낸다(gitignore). 실 TSA 어댑터는 HTTP 포트 구현만(URL·신뢰 앵커 설정), 기관 선택·체인 보관은 §14 #4 그대로 운영 결정.
- TSA 실패(불가·거부·검증 실패)는 봉인·서명·완료를 **막지 않는다**. 앵커는 남고 영수증만 비며 다음 실행이 둘째 배치로 잇는다. `unstampedAnchorAlertDays`를 넘긴 미고정 앵커는 `verify tenant` 발견 `ANCHOR_UNSTAMPED`.

### 4. 검증 (`disclosure-audit` + CLI)
- `anchor receipt export --tenant T --disclosure X --out receipt.json`: 그 확인서의 `chainSeq`·`audit.toSeq`를 **덮는 첫 영수증**(앵커·경로·루트·토큰)과 매니페스트가 가리키는 **직전 앵커의 영수증**(있으면), 그리고 두 앵커 사이의 **봉인 체인 구간 — 해시·번호·seq만**(`[{chainSeq, canonicalHash, chainHash, …산식 입력}]`). 감사 행은 내보내지 않는다(다른 대상의 행이 섞인다).
- `verify package <zip> [--receipt receipt.json] [--tsa-trust pem]`(오프라인, DB·키 없음): 매니페스트 스키마, 모든 엔트리 해시, canonical JCS 재계산 = `canonicalHash`, `disclosure.pdf`가 `signed.pdf`의 바이트 접두, 서명 레코드의 두 해시 결속, 감사 발췌 행별 `entry_hash` 재계산(연속 아님), 매니페스트 입력으로 재계산 가능한 해시 전부. 영수증이 있으면: 체인 구간을 문서의 `chainSeq`부터 덮는 앵커 머리까지 재계산, 잎 → 경로 → 루트, 토큰을 신뢰 앵커로 검증 → 보고서 결론 "**이 문서는 {genTime} 이전에 이 내용으로 존재했다**"(직전 앵커가 있으면 "{T0} 이후"도). 영수증이 없으면 보고서에 **명시적 문장**: "내부 정합성만 확인. 존재 시각·체인 연속은 영수증 또는 `verify tenant`가 필요하다."
- `verify tenant --tenant T [--from --to] [--as-of]`(온라인, 테넌트 컨텍스트·RLS 안): 봉인 체인·감사 체인을 처음(또는 `--from`)부터 재계산, 채번 갭 0, 모든 산출물·서명 증거 객체를 복호화해 기록 해시와 대조(파기된 확인서는 **부재가 정상**, 존재하면 `OBJECT_NOT_DELETED`), 모든 앵커 잎을 그 seq 시점의 재계산 머리와 대조, 모든 영수증의 경로·루트·토큰 검증, 미고정 앵커 기간 검사. 스트리밍(전체 적재 금지).
- 출력: JSON 보고서(`verifierVersion`, 입력 해시, 발견 목록 `{code, where, detail}`) + 종료 코드 **0 일치 / 2 불일치 / 3 입력 오류**. 발견 코드(최소): `SEAL_CHAIN_BROKEN`, `AUDIT_CHAIN_BROKEN`, `NUMBERING_GAP`, `OBJECT_HASH_MISMATCH`, `OBJECT_MISSING`, `OBJECT_NOT_DELETED`, `ANCHOR_MISMATCH`, `RECEIPT_PATH_INVALID`, `TSA_INVALID`, `TSA_UNTRUSTED`, `ANCHOR_UNSTAMPED`, `PACKAGE_ENTRY_MISMATCH`, `SIGNED_PDF_NOT_PREFIXED`. `verify tenant`의 쓰기는 감사 1행(`VERIFY_RUN`, 보고서 해시)과 불일치 시 `compliance_flag(CHAIN_BROKEN)` 뿐이다. 플래그 대상은 끊긴 지점의 확인서; 대상을 정할 수 없으면 테넌트 수준(V9에서 `disclosure_id NULL` 허용 여부는 계획 ①에서 제안).

### 5. 파기
- `DestructionJob(asOf, --dry-run, --tenant)`(주입 `Clock`): 테넌트 순회는 `TenantDirectoryReader`, 판정·실행은 테넌트 트랜잭션. **판정**(전부 참): 봉인 이후 종료 상태(`COMPLETED|EXPIRED|VOID|SUPERSEDED`) ∧ `retention_until ≤ asOf` ∧ 미파기 ∧ 활성 보류 없음(확인서·고객 양쪽) ∧ **앵커 대기 없음**. 앵커 대기: 룰 `retentionAnchors`에 날짜가 아직 없는 앵커가 있으면 보류하되, (a) 그 상태에서 **발생 불가능한 앵커**는 대기하지 않는다(`COMPLETED`가 아닌 종료 상태의 `COMPLETION`·`CONTRACT_DATE`), (b) `COMPLETED`의 `CONTRACT_DATE`는 `completed_at + contractLinkWaitDays ≤ asOf`면 "계약 없음"으로 확정하고 파기 감사에 `anchorsWaived`를 적는다.
- **순서**(단계별 멱등, 어느 단계에서 중단돼도 재실행이 이어 간다): ⓪ 그 확인서의 모든 객체 행이 `retention_applied_until ≤ asOf`인지 확인 — 하나라도 아니면 `LOCK_NOT_EXPIRED`로 건너뛰고 **아무것도 바꾸지 않는다**(키 파기는 되돌릴 수 없으므로 잠금 판단이 먼저다) → ① `document_key_shred` — `wrapped_dek` NULL, 감사 `DOCUMENT_KEY_SHREDDED`(지운 값의 해시) → ② `document_artifact`·`signature_evidence` 객체 전부 `ArtifactStore.delete(key)` — **모든 버전·삭제 마커 제거**, 없는 객체 삭제는 성공, 저장소가 그래도 잠금으로 거부하면(시계 차) 그 확인서는 `LOCK_NOT_EXPIRED`로 보고하고 다음 실행이 ②부터 잇는다(③ 진입 금지) → ③ `exists` 전부 거짓 확인 후 `disclosure_destroy` — 지정 컬럼 NULL, `destroyed_at`, 감사 `DISCLOSURE_DESTROYED`에 `{컬럼: SHA-256(지운 평문)}`, 아웃박스 `DisclosureDestroyed`(이벤트 계약 추가형, CHECKSUMS 갱신, payload는 식별자·번호·시각만).
- 묘비: 번호·상태·`canonical_hash`·`pdf_hash`·`chain_hash`·`chain_seq`·모든 시각·`retention_until`·서명 레코드의 역할·시각·두 해시·`signature_evidence` 행의 해시·키는 남는다. 체인·채번·`verify tenant`는 파기 뒤에도 통과해야 한다.
- 고객: `customer_ref_destroy` — 그 고객의 확인서가 **0건 live**(전부 파기됐거나 없음)이고, 마지막 파기 + `graceDaysAfterLastDestruction` 또는(확인서가 한 번도 없었으면) `created_at + abandonedDays` 경과 시 이름·전화·생년월일 암호문 NULL. GD064 DELETE 금지 유지. 감사에 해시.
- `ArtifactStore` 포트 확장: `delete(key)`(전 버전), `setLegalHold(key, on)`, `capabilities()`. `ArtifactStoreContract`에 추가: 전 버전 삭제 후 `ListObjectVersions` 0, 잠긴 버전 삭제 거부(3B 항목 재사용), legal hold 설정·조회·해제·보류 중 삭제 거부. SeaweedFS가 legal hold를 지원하지 않으면 어댑터는 **`UNSUPPORTED`를 명시**(조용한 no-op 금지)하고 설계서 §9에 "DB 보류 + Object Lock 보존이 통제, S3 보류는 벨트"를 적는다. 어댑터는 어떤 경우에도 `x-amz-bypass-governance-retention`을 보내지 않는다.
- `PlaceLegalHold`·`ReleaseLegalHold` 유스케이스(준법 역할 — 인가는 Phase 6, 지금은 `actor` 인자와 감사 `LEGAL_HOLD_PLACED|RELEASED`). 보류는 `retention_until`을 바꾸지 않는다. 지원 시 객체 legal hold를 켜고 해제 시 끈다(실패해도 DB 보류가 통제이므로 배치는 건너뛴다 — 실패는 보고).
- **봉인 전 초안의 보존·파기는 범위 밖** — §14 미결정에 추가(룰 어휘 미정). 이번에 건드리지 않는다.
- 배치 보고서(JSON): 후보·파기·건너뜀 사유별(`HOLD`·`PENDING_ANCHOR`·`LOCK_NOT_EXPIRED`·`RETENTION_NOT_REACHED`)·실패. 요약 감사 1행 `DESTRUCTION_BATCH_RUN`.

### 6. CLI·데모
- CLI: `anchor run [--date]`, `anchor receipt export`, `verify package`, `verify tenant`, `retention destroy --as-of [--dry-run] [--tenant]`, `legal-hold place/release`.
- 데모 시드: 앵커 2일치(첫 날 전에 완료된 건은 `anchor=null`, 둘째 날 영수증이 덮는 건 1건) → `anchor receipt export` → 데모 증거 ZIP에 `verify package`(영수증 유무 각 1회, 둘 다 종료 0) → `verify tenant` 종료 0 → 짧은 보존 테넌트의 확인서 1건을 `--as-of`로 파기(그 테넌트의 객체 잠금은 실제로 만료돼야 하므로 시드가 짧은 보존기간을 쓴다) → `verify tenant` 다시 종료 0(묘비) → 보류 1건 설정 후 파기 시도 → `HOLD` 건너뜀. 2회 실행 NOOP(둘째 실행에 새 앵커·토큰·파기 0).

## 완료 기준 (전부 테스트로 증명)

| # | 기준 | 증명 방법 |
|---|---|---|
| G1 | 앵커: 테넌트·날짜당 1행, 잎 = JCS 해시, 두 머리가 한 스냅샷, `audit_seq`는 자기 감사 행 직전, 재실행 시 새 앵커·토큰 0, UTC 자정을 넘는 하나의 KST 날 | `AnchorJobIT` |
| G2 | 머클: 테넌트 1·2·17개에서 경로 길이 = `treeDepth`, 임의 잎 + 경로로 루트 재계산 일치, 패딩 상수, 도메인 접두(잎을 노드로 제시하면 실패), `2^depth` 초과 거부, 규격 블록 ↔ 코드 양방향 대조 | `MerkleTreeTest`·`MerkleSpecTableTest` |
| G3 | 격리: A의 영수증·경로·앵커 어디에도 다른 테넌트 ID·머리 값 없음, 영수증 쓰기는 테넌트 트랜잭션(교차 삽입 RLS 거부), 배치 읽기는 `TenantDirectoryReader`만(FQN 허용 목록 테스트) | `AnchorIsolationIT`·ArchUnit |
| G4 | TSA: 스텁 토큰이 신뢰 앵커로 검증, 루트·nonce·인증서 변조 각각 실패, 신뢰 앵커 없으면 `UNTRUSTED`, TSA 불가 주입 시 앵커 있음·영수증 없음·다음 실행 둘째 배치, `org.bouncycastle..` 참조 범위, 카탈로그·락 고정 | `TsaStubTest`·`AnchorJobIT`·ArchUnit |
| G5 | `verify package`: 정상 0; 엔트리 바이트·매니페스트 해시·접두 파괴·서명 해시·감사 행·영수증 경로·토큰 변조 각각 2 + 해당 코드; 손상 ZIP 3; 영수증 없음 보고서에 명시 문장; 영수증 있음 보고서에 존재 시각 결론; 다른 문서의 영수증 2 | `VerifyPackageTest`(DB 없이) |
| G6 | `verify tenant`: 정상 0; 슈퍼유저로 `chain_hash`·감사 `entry_hash`·객체 바이트·앵커 잎·영수증 경로 변조 각각 2 + `CHAIN_BROKEN` 플래그; 파기 건 객체 부재 정상, 존재 시 `OBJECT_NOT_DELETED`; 미고정 앵커 기간 초과 `ANCHOR_UNSTAMPED` | `VerifyTenantIT` |
| G7 | 파기 롤: 롤 권한 = 함수 EXECUTE만(카탈로그 조회), 앱 롤·파기 롤의 직접 UPDATE 거부, 함수가 보존 미도래·활성 보류·기파기 각각 거부, 지정 외 컬럼 변경 거부 | `DestroyerRoleIT` |
| G8 | 순서·멱등: ⓪ 잠금 미만료 → `LOCK_NOT_EXPIRED`·변경 0(키 포함); ① 뒤 중단 → 재실행이 ②③만, ①의 감사 중복 0; ② 뒤 중단 → 삭제 재시도 성공·③ 진행; 저장소 잠금 거부 주입 → ③ 미진입; 파기 뒤 전 버전·삭제 마커 0 | `DestructionOrderIT` |
| G9 | 묘비: 파기 전후 번호·상태·해시·시각·체인·서명 레코드 불변, 지정 컬럼 NULL, 감사 해시 = 사전 계산값, `SealChainIT`·채번·`verify tenant` 통과 | `TombstoneIT` |
| G10 | 앵커 대기: `COMPLETED` + `CONTRACT_DATE` 룰 + 계약일 없음 → `PENDING_ANCHOR`, `contractLinkWaitDays` 경과 → 파기 + `anchorsWaived`; `EXPIRED`는 대기 없음; 룰만 바꿔 동작 변경(코드 diff 0) | `RetentionDecisionTest`·`DestructionRulesAsDataIT` |
| G11 | 보류: 설정 → `HOLD` 건너뜀, 해제 → 다음 실행 파기, 설정·해제 감사, 대상당 활성 1건, `retention_until` 불변, 저장소 legal hold 지원 여부 계약 결과 기록(지원 시 켬·끔 확인) | `LegalHoldIT`·`ArtifactStoreContract` |
| G12 | 고객: live 확인서 있으면 건너뜀, 0건 + 유예 경과 시 NULL, GD064 DELETE 여전히 거부, 감사 해시 | `CustomerRefDestructionIT` |
| G13 | 개인정보 컬럼 전수: 설계서 `pii-columns` 블록 ↔ 세 파기 함수의 컬럼 집합 양방향 대조(어느 함수가 지우는지도 표에), 블록에서 한 줄 제거 시 실패, 암호화 컬럼 중 표에 없는 것이 있으면 실패 | `PiiColumnTableTest` |
| G14 | Phase 0~4 무손상, 평문·jqwik·BOM 검사, 위반 주입 기록(최소: 도메인 접두 제거, 패딩 깊이 가변, 트리거의 롤·함수 경유 검사 제거, ⓪ 잠금 확인 제거, 파기 감사 해시 생략, `verify tenant`의 감사 연속 검사 생략, 다른 테넌트 영수증 수용, TSA 신뢰 앵커 검사 생략) | 빌드 로그·보고서 |

## 하지 말 것

- 실 TSA 기관 선택·인증서 체인 보관 정책(§14 #4), 스케줄러 등록·공개 엔드포인트·인가(Phase 6), PAdES, 봉인 전 초안 파기, 프론트(Phase 7).
- 테넌트 없는 배치·토큰 테이블. 패키지 재생성. 토큰 복사·테넌트 밖 보관.
- 파기 롤에 함수 EXECUTE 외 권한. 파기에서 번호·상태·해시·시각·체인을 건드리는 것. `x-amz-bypass-governance-retention`. 조용한 no-op legal hold.
- `verify`가 감사 1행·플래그 외의 쓰기를 하는 것. `verify package`가 네트워크·DB·키에 닿는 것.
- 감사 행을 영수증·패키지로 내보내는 것.

## 보고 형식

Phase 4와 동일. 추가로 ① 머클 규격 블록(기계 판독: 접두·정렬·패딩 상수·깊이) ② `verify` 보고서 예시 3건(package 영수증 유·무, tenant 불일치 1건) ③ 파기 전후 묘비 행 비교 1건과 파기 감사 행 ④ 개인정보 컬럼 전수 표 ⑤ `ArtifactStoreContract` 신규 항목의 SeaweedFS 결과 ⑥ Phase 6(준법 큐·징구율·게이트 API·이벤트 피드·REST·인가·고객 공개 서명 엔드포인트) 질문.
