# Phase 3B 지시문 — 봉인: 정규화·채번·PDF/A·암호화 저장·체인·정정·무효 (v1.0)

## 역할과 맥락

`ga-disclosure` Phase 3A(`phase-3A`, PR #5) 수용 후. 대상은 설계서 **§6.4(봉인), §6.6(정정과 무효), §5(`document_artifact`·`document_key`·채번·`review` 귀속, V7), §9(산출물 암호화·파기), §12 Phase 3B**와 `phase-03A-수용심사.md` §3의 결정 1~8. 서명·증거 패키지·만료는 Phase 4, 앵커·TSA·`verify`·파기 배치는 Phase 5다.

이 Phase가 끝나면 다음이 테스트로 증명돼야 한다.

1. 같은 확인서를 두 번 봉인하면(다른 프로세스·다른 OS에서) canonical JSON과 PDF가 **바이트 단위로 같다.**
2. 봉인된 본문은 어떤 경로로도 바뀌지 않으며, 봉인 실패는 상태·번호·저장소에 흔적을 남기지 않는다(감사 제외).
3. 확인서 번호는 테넌트·연도별로 연속이고 동시 봉인에서도 중복·빈 번호가 없다.
4. 산출물은 문서별 키로 암호화돼 저장되고, 키를 지우면 어떤 사본도 읽을 수 없다.

## 시작 전 보고

계획에 ① `render.bind` 어휘 전체 목록 ② canonical 문서 스키마(`contracts/seal/v1/canonical.schema.json`) 초안 ③ 봉인 트랜잭션 순서와 실패 시 정리 ④ V7 DDL ⑤ openhtmltopdf 세 지점 고정 방법(어느 값을 무엇에서 파생하는지) ⑥ MinIO Testcontainers·Object Lock 구성을 넣고 승인 후 진행.

## 작업 목록

### 1. V7 마이그레이션
- `document_key(tenant_id, disclosure_id, key_id, wrapped_dek BYTEA NULL, kek_key_id, created_at, shredded_at, shredded_by)` — append-only에 `wrapped_dek`만 NULL로 바꾸는 파기 경로(Phase 5, `disclosure_migrator` 전용 함수)를 예약. `shredded_at`이 있으면 `wrapped_dek IS NULL` CHECK.
- `document_artifact`: `sha256`(평문)·`cipher_sha256`·`bytes`(평문 길이)·`cipher_bytes`·`key_id`·`retention_applied_at TIMESTAMPTZ NULL`. UPDATE는 `retention_applied_at`만 NULL→값 1회 허용(트리거), 나머지 append-only 유지.
- `disclosure_counter(tenant_id, year SMALLINT, seq BIGINT)`; `disclosure.disclosure_no` UNIQUE(V1) 유지, 형식 CHECK `^[A-Z0-9][A-Z0-9_]{0,31}-[0-9]{4}-[0-9]{6}$`.
- `review`에 `rule_version_id`·`tenant_rule_version_id` 추가(NOT NULL, `tenant_rule_version_id`는 NULL 허용) — 승인의 룰 버전 귀속(수용심사 §3-8).
- 봉인 컬럼과 상태의 결속 CHECK(3A에서 미룬 것): `status ∈ 봉인 이후` ⇔ `disclosure_no·sealed_at·canonical_hash·pdf_hash·chain_hash·chain_seq` 전부 NOT NULL; 가변 상태 ⇔ 전부 NULL. `chain_seq`는 테넌트별 UNIQUE.
- 오류 코드는 `db-error-codes.md`에 이어서 배정.

### 2. 서식 결속 (`disclosure-rules`, 선행 소과제)
- `form-template.schema.json`의 `fields[].render`에 `bind` 필수(닫힌 enum, 계획 ①의 목록). `STANDARD-v1` 번들을 제자리 수정(첫 운영 배포 전 조건, 설계서 §5 주석 인용). `R-FIELD-REQUIRED`는 `bind`가 가리키는 값의 존재로 판정하도록 바꾸고, 3A의 `FieldValueView` 출처별 판정은 제거한다. `RuleFreezeIT`의 `TEST_ONLY_FIELD`가 추천사유만으로 충족되지 않음을 확인.

### 3. 정규화·해시 (`disclosure-seal`, Spring 무의존)
- `CanonicalDocumentBuilder`: 수용심사 §3-3의 구성으로 canonical 문서(JSON 객체)를 만들고 `platform-canonical`로 JCS 직렬화 → `canonical_hash`. 문서는 `contracts/seal/v1/canonical.schema.json`을 통과해야 하며 이 스키마가 **봉인 본문의 정본 정의**다(포함·제외 필드가 스키마로 고정). 번호·상태·시각·해시·체인은 스키마에서 금지(`additionalProperties: false` + 금지 키 `not`).
- 성명은 봉인 유스케이스가 복호화해 넣는다(감사 `CUSTOMER_VIEW`, 사유 `SEAL`). 전화·생년월일·`registration_key`는 스키마 위반이다.
- `subject_hash` 규약(D7 `[x]` 감싸기)은 봉인 경로에 나타나지 않는다(테스트).

### 4. 렌더 (`disclosure-seal` 렌더러 패키지)
- openhtmltopdf(계획 ⑤). HTML 템플릿은 `form_template.layout`과 `bind`로만 값을 채운다. 결정론 세 지점 — 문서 정보 날짜·XMP 날짜는 **상담일 00:00 KST 고정값**, `/ID`는 `SHA-256(canonical_hash || disclosure_no)`에서 파생. 벽시계 참조 0(`Clock` 주입도 렌더러에는 없음). 폰트·ICC는 3A 동봉 자산.
- PDF/A-2b. 각주에 확인서 번호와 `canonical_hash` 앞 12자를 **텍스트**로 인쇄(QR은 §14 #9 미결정 그대로).
- 골든 테스트: `src/test/resources/golden/`에 입력 canonical·서식 버전·기대 SHA-256(PDF·canonical)을 커밋한다. 로컬(macOS)에서 생성한 기대값을 CI(Linux)가 검증하므로 **OS 간 결정론이 자동으로 검사**된다. 기대값 갱신은 커밋 메시지에 사유 필수.
- veraPDF: CI 잡 `pdfa-verify`가 골든 PDF를 PDF/A-2b 프로파일로 검증(실패 0). 메인 테스트는 구조 마커(XMP `pdfaid:part=2`·`conformance=B`, OutputIntent, 폰트 임베드)만 검사.
- 렌더 시간 측정을 남긴다(p95 목표 3초).

### 5. 암호화 저장 (`disclosure-infra`, MinIO)
- `ArtifactStore` 포트: `put(key, cipherBytes)`, `get(key)`, `applyRetention(key, until)`, `exists`. S3 호환 어댑터 + Testcontainers MinIO(Object Lock 활성 버킷, 버전 관리).
- 문서별 DEK(AES-256-GCM 256비트)를 봉인 시 생성, 테넌트 KEK로 감싸 `document_key`에 저장(Phase 2 `KeyProviderPort` 재사용). 산출물은 클라이언트 측 암호화, AAD = `tenant_id`·`disclosure_id`·`kind`. 객체 키 = `{tenant_id}/{disclosure_id}/{kind}/{sha256}`.
- **순서**: 렌더·암호화·업로드(잠금 없음) → DB 커밋(아래 6) → 커밋 후 `applyRetention(retention_until)` → `retention_applied_at` 기록. 커밋 실패 시 업로드된 객체는 잠금이 없으므로 `artifacts gc` 명령(참조 없는 객체 삭제, 감사)이 치운다. `retention_applied_at IS NULL`인 행은 `artifacts reconcile`이 재적용한다(Phase 5 배치의 전신, 지금은 CLI).
- 열람(`get`)은 복호화 후 평문 해시를 `document_artifact.sha256`과 대조해야 반환하고 감사 `ARTIFACT_VIEW`를 남긴다.

### 6. 봉인 유스케이스 (`disclosure-workflow`)
- `Seal(disclosureId, actor)`: 한 쓰기 트랜잭션에서 순서대로 — `FOR UPDATE` → 상담일 룰 재해석 = 고정 ID 확인(다르면 `RULE_SUPERSEDED_DRAFT` 플래그) → 스냅샷 노후(`snapshotMaxAgeDays`, `Clock`) → SEAL 단계 검증 + `SealGate`(승인의 대상 해시·룰 버전 일치) → 성명 복호화 → canonical·해시 → **채번**(`INSERT … ON CONFLICT DO UPDATE … RETURNING`, 카운터 행 잠금) → 렌더·PDF 해시 → DEK 생성·감싸기 → 암호화·업로드 → `chain_hash = H(prev_chain_hash || canonical_hash || pdf_hash)`, `chain_seq` → 헤더·`document_artifact`·`document_key` 기록 → 상태 `SEALED` → 감사 `DISCLOSURE_SEAL` → 오버라이드 플래그 `APPROVED` 해소 → 커밋 → 커밋 후 잠금 적용.
- 조건 실패는 **단락 없이 전부 평가**해 목록으로 반환하는 업무 거부(감사 `DISCLOSURE_SEAL_REJECTED`, 상태·번호·저장소 불변). 예외는 3A 규약대로 롤백 + `COMMAND_FAILED`.
- `Rebase`: `RULE_SUPERSEDED_DRAFT` 플래그가 있을 때만. COMPARED 회귀 + 재해석 결과 고정 + 스냅샷·사유 폐기. 기존 승인은 룰 버전 귀속으로 자연히 무효.
- `Void(reason, actorRole)`: 가변 상태는 설계사, 봉인 이후는 `exceptionApproval.role`(룰 데이터) 필요(인가 자체는 Phase 6, 지금은 역할 인자·감사). 플래그는 `SUPERSEDED_BY_DOCUMENT_STATE`로 해소.
- `Supersede(reason, actorRole)`: 봉인 이후 상태에서 새 버전 DRAFT 생성(`supersedes_id`, 항목 복제, 스냅샷·사유는 폐기 — 재산출), 원본 `SUPERSEDED`·`superseded_by_id`. 룰·서식은 **원본의 상담일로 다시 해석**해 새로 고정(상담일 동일).
- 상태표(`state-table`)에 SEAL·VOID·SUPERSEDE·REBASE를 구현 명령으로 옮기고 W1 목록 갱신.

### 7. CLI·데모
- `disclosure seal --tenant T --id …`, `disclosure void/supersede/rebase`, `artifacts get/gc/reconcile`. 데모 시드에 봉인 2건(정상, 승인 후 임시등록 건) + 정정 1건(supersede → 새 버전 REASONED까지). 2회 실행 NOOP.

## 완료 기준 (전부 테스트로 증명)

| # | 기준 | 증명 방법 |
|---|---|---|
| S1 | 골든 입력의 canonical·PDF SHA-256이 커밋된 기대값과 일치(CI Linux ↔ 로컬 macOS 생성) | `SealGoldenTest` |
| S2 | 같은 확인서를 별도 JVM 2회 봉인(첫 봉인 후 복원한 상태에서 재렌더) → 바이트 동일; 상담일·항목 하나만 바꾸면 해시가 바뀜 | `RenderDeterminismIT` |
| S3 | canonical 문서가 스키마를 통과하고 금지 키(번호·상태·시각·해시·전화·생년월일·`registration_key`)가 들어가면 실패; 최상위가 객체 | `CanonicalSchemaTest` |
| S4 | 봉인 후 본문 컬럼·항목·사유 UPDATE 거부(3A 매트릭스 편입), 봉인 컬럼–상태 결속 CHECK 위반 조합 전수 거부 | `ImmutabilityTriggerIT`·`SealColumnCheckIT` |
| S5 | 같은 테넌트 동시 봉인 50건: 번호 연속·중복 0·빈 번호 0; 봉인 거부 20건 섞어도 빈 번호 0 | `NumberingIT` |
| S6 | 봉인 조건 6종 각각 실패 시 상태·번호·`document_artifact`·저장소 객체·`document_key` 모두 불변, 거부 목록에 전부 포함(단락 없음), 감사 1행 | `SealRejectionIT` |
| S7 | 승인 귀속: 대상 해시 불일치·룰 버전 불일치 승인은 `SealGate`가 무시; 재기준 후 기존 승인 무효 | `SealGateTest`·`RebaseIT` |
| S8 | 산출물이 암호문으로 저장되고(버킷에서 직접 읽은 바이트에 평문 해시·성명 없음), 복호화 후 `sha256` 일치, `wrapped_dek` NULL이면 복호화 불가 | `ArtifactEncryptionIT` |
| S9 | 잠금 순서: 커밋 실패 주입 시 잠금 없는 고아만 남고 `gc`가 치움; 커밋 후 `applyRetention` 실패 주입 시 `retention_applied_at` NULL → `reconcile`이 재적용; 잠금된 객체는 `retention_until` 전 삭제 불가 | `RetentionOrderIT` |
| S10 | `chain_hash` 연속(테넌트별 재계산 일치), `chain_seq` 갭 0 | `SealChainIT` |
| S11 | Void·Supersede·Rebase 전이가 상태표대로, Supersede 새 버전의 룰·서식이 원본 상담일 재해석으로 고정, 플래그 해소 규칙 | `LifecycleIT` |
| S12 | veraPDF PDF/A-2b 실패 0(CI `pdfa-verify`), 구조 마커 테스트 | CI 로그·`PdfAMarkersTest` |
| S13 | `bind` 결속: `TEST_ONLY_FIELD`가 추천사유만으로 충족되지 않음, 항목 코드 리터럴이 렌더러 소스에 0건 | `RuleFreezeIT` 갱신·`NoFieldCodeLiteralsTest` |
| S14 | Phase 0~3A 무손상, 평문 유출 스캔(성명 복호화 경로 포함) 0건, BOM·jqwik | 빌드 로그 |

## 하지 말 것

- 서명·증거 패키지·만료 배치(Phase 4), 앵커·TSA·`verify`·파기 배치(Phase 5), REST·인가(Phase 6).
- 렌더러가 벽시계·난수·환경(로케일·시간대)에 의존하는 것. PDF에 성명 외 PII를 넣는 것.
- 번호 선할당·빈 번호 허용. 잠금을 커밋 전에 거는 것. `canonical.schema.json` 밖의 필드를 canonical에 넣는 것.

## 보고 형식

3A와 동일. 추가로 ① 세 지점 고정의 실제 파생식 ② 렌더 시간 분포(p50·p95, 동시 10건) ③ 골든 기대값 생성 환경 ④ Phase 4(서명) 질문.
