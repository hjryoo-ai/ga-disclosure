# Phase 3B 완료 보고 — 봉인: 정규화·채번·PDF/A·암호화 저장·체인·정정·무효

작성 2026-10-01 · 대상 지시문 `docs/phase-03B-지시문.md` v1.0(정정 기록 포함) · 계획 `docs/phase-03B-계획.md`(승인 2026-10-01, `docs/phase-03B-계획승인.md` — Q1~Q12 권장안, 보강 B1~B4) · 설계서 v1.8 · 브랜치 `work/phase-3B` · PR [#6](https://github.com/hjryoo-ai/ga-disclosure/pull/6)

## 요약

- **REASONED 확인서가 봉인된다.** 한 쓰기 트랜잭션(제한 60초) 안에서 다음 순서로 진행한다.
  1. 잠금 순서: 확인서 → (테넌트, 봉인 연도) 카운터 → 테넌트 체인 머리.
  2. 성명 복호화(`CUSTOMER_VIEW` 감사).
  3. canonical JCS 생성.
  4. 무간격 번호 `{tenant}-{yyyy}-{6자리}`(봉인일 KST 연도, Q5).
  5. PDF/A-2b 렌더.
  6. 문서별 DEK로 AES-256-GCM, 잠금 없이 업로드.
  7. 체인 `SHA-256(prev ‖ canonical ‖ pdf)`, 봉인 컬럼·키·산출물 행, 플래그 해소.
  8. **커밋 뒤** Object Lock COMPLIANCE.
- **봉인 조건 6종은 단락 없이 전부 평가한다.** 조건은 소급 룰, 소급 서식, 스냅샷 노후, 오버라이드 불가 실패, 승인 누락(고정 룰 버전 귀속), 성명 복호화 불가다.
  - 거부되면 상태·번호·카운터·산출물·저장소 객체·문서 키가 모두 그대로다.
  - 감사는 `DISCLOSURE_SEAL_REJECTED` 1행이고, 거부 코드 목록이 전부 들어간다.
- **재렌더 결정론.**
  - 다른 JVM 2개(Asia/Seoul·ko_KR·UTF-8 ↔ America/New_York·en_US·ISO-8859-1)가 저장된 canonical에서 같은 PDF 바이트를 만든다.
  - 골든 3건의 canonical·PDF SHA-256이 로컬 macOS 기대값과 CI Linux에서 같다(§6 CI).
  - veraPDF PDF/A-2b 실패 0건: 골든 3건과 데모 봉인본 1건.
- **불변·정합은 DB가 강제한다(V7).**
  - 봉인 컬럼 7개는 전부-또는-없음이고 상태에 결속된다. VOID만 둘 다 허용한다(Q8).
  - 번호 = 카운터 현재값, 연도 = KST 봉인 연도, 체인 = 직전 머리의 연장(GD095).
  - 카운터 +1만 허용(GD090), 체인 머리는 실재 봉인으로만 전진(GD091), 문서 키(GD092)·산출물(GD093)은 봉인된 확인서에만 둔다.
  - 보존기한은 연장만(GD094), 승인은 고정 룰 버전에 귀속(GD081).
  - 3A 불변 매트릭스(508건)와 결속 CHECK 전수(1,341건)가 이를 증명한다.
- **저장소는 MinIO 대신 SeaweedFS 4.48(digest 고정)이다(Q1).**
  - 어댑터는 벤더 무관 표준 S3 API(AWS SDK v2 2.55.9, URLConnection 클라이언트만)만 쓴다.
  - 추상 `ArtifactStoreContract`(Object Lock 5종 + 덮어쓰기)를 SeaweedFS 구현이 상속한다.
- **정정·무효·재기준.**
  - SUPERSEDE: 다음 버전을 원본 상담일로 다시 해석해 고정한다. 등급·사유는 복사하지 않는다.
  - VOID: 봉인 후에는 예외 승인 역할만 할 수 있고, 번호는 유지된다.
  - REBASE: `RULE_SUPERSEDED_DRAFT` 플래그가 있을 때만 → COMPARED 또는 DRAFT(Q3). 옛 승인은 효력을 잃는다.
- **테스트 10,790건, 실패 0, 스킵 0**(3A: 9,268건). `scanPlaintextLeaks: 87 result files, 14 forbidden strings, 0 hits`.
  - 위반 주입은 22건이다: 아키텍처·스캔·저장소 하네스 8, V7 트리거·제약 9, 봉인 유스케이스 5.
  - 전부 잡혔다. 1건(N3)은 1차 관측이 약해 관측 지점을 강화한 뒤 2차에 완전히 잡혔다(§6).
- **CI**: 첫 run `36855875536`(head `0ea92ac`)은 `build`가 실패했다. 저장소 테스트 13건이 S3 500이었다.
  - 원인은 SeaweedFS 볼륨 한도가 러너의 여유 디스크에서 자동 산정되는 것이었다. 로컬에서 재현한 뒤 한도를 고정했다(`895835c`).
  - 수정 뒤 run `36856909677`(head `895835c`)은 `build`·`pdfa-verify`·`no-docker` 전부 success였다. 두 run 모두 `gh run view`로 직접 조회했다(§6 CI).
- **데모**: 가상 확인서 A-1 봉인 → 정정(v2 REASONED), A-2 관리자 예외 승인 2건 → 봉인. 두 번째 실행은 전부 NOOP이다. 봉인본 열람·gc·reconcile·거부 종료 코드 2도 확인했다(§8).
  - 보고서 작성 중 `seed.sh`가 3B 저장소 설정을 넘기지 않는 것을 발견해 고쳤다(`7660357`). 새 스택에서 스크립트 자체로 2회 실행해 확인했다.
- **엔진**: B4에 따라 E4 첫 커밋 전까지 엔진 저장소에 손대지 않았다.

## 1. 커밋·파일

커밋 목록(`main..work/phase-3B`, 보고서 커밋은 이 표 뒤에 붙는다):

| 커밋 | 요약 |
|---|---|
| `f817113` | docs: 3A 수용 심사 기록, 3B 지시문·계획(승인 대기) |
| `a9fb51d` | docs: 3B 계획 승인 — SeaweedFS가 MinIO를 대체, 식별부 section, 보존기한 단조 |
| `0f82085` | feat: V7 봉인 스키마, `render.bind`, canonical 문서, 결정론 PDF/A 렌더러 |
| `7dbff93` | feat: 봉인·무효·정정·재기준 유스케이스, 암호화 Object Lock 저장소 |
| `5673bd0` | feat: 운영자 CLI(봉인·무효·정정·재기준·산출물), 데모 봉인 단계, compose SeaweedFS |
| `0ea92ac` | test(infra): 열람이 기록 해시와 다른 평문을 거부(`HASH_MISMATCH` 경로 — 보고서 작성 중 발견한 미시험 경로) |
| `895835c` | fix(test): SeaweedFS 볼륨 한도 고정 — 러너 디스크 크기에 따라 쓰기가 실패하던 CI 결함(§6 CI) |
| `7660357` | fix(demo): `seed.sh`가 3B 봉인 단계용 로컬 버킷을 만든다, README 3B |

주요 추가 파일:

| 영역 | 파일 |
|---|---|
| DB | `disclosure-infra/src/main/resources/db/migration/V7__seal.sql`, `docs/db-error-codes.md`(GD081·GD090~GD095) |
| 계약 | `contracts/seal/v1/canonical.schema.json`, `contracts/rules/form-template.schema.json`(`bind`·`section`·`labelRef`·`title`·`columnsPerTable`), `STANDARD.v1@2b7e18fbd96b`(제자리 재해시) |
| 룰 | `rules/render/bind/{Bind, FieldSection, BindingView, Bound, BindingResolver}`, `LayoutSection`, `TemplateLayoutCheck`, `R-FIELD-REQUIRED` 재작성(3A `FieldValueView` 삭제) |
| 봉인 | `seal/canonical/{CanonicalDocumentBuilder, CanonicalDocument, CanonicalView, FieldCodeOrder}`, `seal/renderer/{DisclosurePdfRenderer, HtmlComposer, RenderAssets, RerenderMain}`, 골든 `disclosure-seal/src/test/resources/golden/case-0{1,2,3}/` |
| 도메인 | `DisclosureCommand.REBASE`·상태표 행, `SealStamp`·`VoidMark`·`Lineage`, 애그리게이트 `seal`·`voidWith`·`supersede`·`rebase` |
| 워크플로 | `workflow/artifact/{ArtifactStore, DocumentCryptoPort, DocumentRecordStore, ArtifactRecord, …예외}`, `workflow/disclosure/{SealService, LifecycleService, ArtifactService, SealLedgerPort, DisclosureLoader}`, `SealGate` 룰 버전 귀속 |
| 인프라 | `infra/crypto/DocumentCipher`, `infra/storage/{S3ArtifactStore, S3StorageSettings, ArtifactStoreBootstrap, VerifiedArtifactStore}`, `persistence/{SealLedgerRepository, DocumentRecordRepository}`, testFixtures `SeaweedHarness`·`seaweedfs/s3.json` |
| 앱·데모 | `config/SealConfiguration`, CLI `disclosure seal|void|supersede|rebase`·`artifacts get|gc|reconcile`(`CliRejection` 종료 코드 2), `demo/disclosures.json` 봉인·정정 단계, `docker-compose.yml` SeaweedFS, `docker/seaweedfs/s3.json`(허구 키) |
| 검증 | `verification/pdfa-verify/`(veraPDF 1.30.2, 독립 빌드), CI 잡 `pdfa-verify` |

## 2. `render.bind`와 식별부 (계획 §1, Q2·B3)

- 서식 항목마다 닫힌 어휘 `bind` 14종 중 하나를 둔다. 렌더러와 `R-FIELD-REQUIRED`는 같은 `BindingResolver`로 값을 찾는다. 그래서 "존재 판정"과 "인쇄 값"이 갈라질 수 없다.
- 코드는 항목 코드를 모른다.
  - `NoFieldCodeLiteralsTest`: 렌더러 소스에 번들·픽스처 서식의 `code` 리터럴이 0건이어야 한다.
  - `TemplateLayoutCheck`: 번들 로더가 배치 구조(섹션 종류 ↔ 필드 `section`·`scope`)를 검사한다.
- 식별부 4개(`DISCLOSURE_NO`·`CONSULT_DATE`·`AGENT`·`CUSTOMER_NAME`)를 추가했다.
  - 속성: `section: HEADER`, `required`, `labelRef: "TODO(confirm#2)"`. `pendingConfirmation`과는 섞지 않았다.
  - 성명은 봉인 유스케이스만 채운다. 렌더러에는 복호화 경로가 없다(ArchUnit `SealRules`).
- 레이아웃: `title`("보험상품 비교설명 확인서"), 섹션 HEADER → SUMMARY → COMPARISON, `columnsPerTable` 4.
- S13: `RuleFreezeIT`에서 v2의 `TEST_ONLY_FIELD`(`AGENT_INPUT`)가 추천사유만으로는 충족되지 않는다. 3A D2의 과대 충족이 사라졌다.
- `STANDARD.v1` 제자리 재해시: `released-bundles.txt`가 비어 있다(운영 배포 없음). 영향은 §7 D9.

## 3. 봉인 트랜잭션과 정리 (계획 §3, 설계서 §6.4 3·6·10항)

| 단계 | 내용 | 실패 시 |
|---|---|---|
| 조건 평가 | 6종 전부, 단락 없음. 거부면 커밋(감사 1행, 소급이면 `RULE_SUPERSEDED_DRAFT` 플래그) | 상태·카운터·체인·객체·키 불변 — `SealRejectionIT` 7건 |
| 쓰기 트랜잭션 | 확인서 `FOR UPDATE` → 카운터 → 체인 머리 → 성명 → canonical → 번호 → 렌더 → 암호화 → 업로드(잠금 없음) → 봉인 컬럼·키·산출물·플래그·감사 | 롤백. 잠금 없는 잔여물만 남고 `artifacts gc`가 유예(기본 24시간 > 트랜잭션 제한 60초) 뒤 지운다 |
| 커밋 뒤 | `PutObjectRetention`(COMPLIANCE, `retention_until` 당일 끝 KST) → `retention_applied_at` 기록 | 행이 NULL로 남고 `artifacts reconcile`이 다시 건다 |

- 보존기한은 봉인 때 `봉인일 + retentionYears`로 정하고 이후 연장만 한다(Q6). 단축 경로는 코드에 없고, DB(GD094)와 저장소(계약 테스트) 모두 단축을 거부한다.
- 플래그 해소(Q9·결정 7): 승인이 있는 규칙은 `APPROVED`(해소자 = 승인자), 더는 실패하지 않는 규칙은 `RESOLVED_AT_SEAL`(해소자 `SYSTEM`)로 닫는다.

## 4. 저장·암호화 (계획 §6, Q1·Q4·Q12·B1·B2)

- **문서 키**: 확인서마다 DEK 256비트를 만들어 테넌트 KEK로 감싼다(`DOC-{32 hex}`).
  - 형식은 `0x01 ‖ nonce ‖ 암호문 ‖ tag`이다.
  - AAD = JCS `{disclosureId, kind, tenantId, v:1}`이다.
  - 파기 = 감싼 키 NULL이다. 소유 롤의 `ga_shred_document_key`만 할 수 있고, EXECUTE 부여는 없다.
- **객체 키**: `{tenant}/{disclosure}/{kind}/{cipher_sha256}`(Q4). 버킷 목록에 평문 해시가 없다.
- **열람**: 복호화한 평문의 SHA-256이 기록과 같을 때만 내준다. 거부 사유는 `NO_ARTIFACT`·`KEY_SHREDDED`·`OBJECT_MISSING`·`UNREADABLE`·`HASH_MISMATCH`이고 전부 `ARTIFACT_VIEW_DENIED`로 감사한다.
- **버킷 확인**: Object Lock 활성, 버전 관리 Enabled, 기본 보존 규칙 없음. 저장소 첫 사용 직전에 한 번 확인한다(§7 D4).
- **SeaweedFS**
  - 이미지 `chrislusf/seaweedfs@sha256:4e61d15f…7872d`(4.48)를 버전 카탈로그·하네스·compose 세 곳이 같은 digest로 쓴다. `SeaweedArtifactStoreIT.imageDigestIsPinnedTheSameEverywhere`가 검사한다.
  - S3 인증을 켰고, 서명 없는 요청은 거부된다(`unsignedRequestsAreRejected`).
- **AWS SDK v2 2.55.9**(Q12): 2026-10-01 Maven Central 최신 안정판이다. 계획 작성일의 최신은 2.55.8이었고, 착수 시 다시 확인해 2.55.9로 고정했다.
  - `apache-client`·`netty-nio-client`·`apache5-client`는 제외했다. 락 파일에 netty·apache HTTP 클라이언트가 0건이다.
  - Boot BOM 밖이고, 전이 의존 중 BOM과 겹치는 좌표는 `commons-logging`(BOM 1.3.6 적용)뿐이다.

## 5. 지시문 추가 보고 4항목

### ① 세 지점 고정의 실제 파생식 (`DisclosurePdfRenderer.pin`)

| 지점 | 실제 식 | 검증 |
|---|---|---|
| 문서 정보 `/CreationDate`·`/ModDate` | `new GregorianCalendar(new SimpleTimeZone(9·3600·1000, "KST"), Locale.ROOT)`, `clear()` 후 `set(상담일, 0, 0, 0)` → `D:20260923000000+09'00'`. 정보 사전은 새로 만들어 `/Title`(= 서식 `layout.title`)·`/Producer`(= `ga-disclosure-renderer/1`)·두 날짜 4개 키만 둔다 | `PdfAMarkersTest.structuralMarkersAndPinnedPoints`(키 집합 정확히 4개, 날짜 문자열 일치) |
| XMP | 라이브러리 산출물을 고치지 않고 렌더러가 고정 템플릿으로 통째 만든다. `pdfaid:part=2`·`conformance=B`, `dc:title` = 정보 `/Title`, `pdf:Producer` = 정보 `/Producer`, `xmp:CreateDate`·`ModifyDate`·`MetadataDate` = `{상담일}T00:00:00+09:00` | 같은 테스트(XMP 원문 포함 검사), veraPDF(정보 ↔ XMP 일치 규칙) |
| 트레일러 `/ID` | `h = SHA-256(ASCII(canonical_hash 소문자 hex 64자) ‖ ASCII(disclosure_no))`, `/ID = [<h[0..16)> <h[0..16)>]`. `trailer.setItem(COSName.ID, …)`으로 직접 넣는다. PDFBox 3.0.7 `PDDocument.save`는 기존 `/ID`를 보존한다. 그래서 계획의 대체안(`setDocumentId` 시드)은 쓰지 않았다 | 같은 테스트(두 원소 = 식), 두 JVM 재렌더 바이트 동일 |

- 각주(모든 페이지): `{disclosure_no} · {canonical_hash[0:12]} · {page}/{pages}`(CSS counter)다. 구분자는 리터럴 `U+00B7`이다. 처음에는 CSS 이스케이프가 뒤따르는 공백을 삼켜 수정했다.
- 환경 독립: `PdfAMarkersTest.sameInputSameBytesEvenUnderAnotherDefaultLocaleAndTimeZone`(같은 JVM에서 기본값 교체)과 `RenderDeterminismIT`(별도 JVM 2개)가 확인한다. 아키텍처 규칙 `SealRules`는 봉인 본문·렌더러의 `Instant.now`·`Locale.getDefault`·`TimeZone.getDefault` 등을 금지한다(I1).

### ② 렌더 시간 분포 (`RenderTimingTest`, 워밍업 5회 뒤)

| 실행 | 순차 50회 p50 / p95 / max | 동시 10건 p50 / p95 / max | 환경 |
|---|---|---|---|
| 로컬 클린 빌드(2026-10-01) | 10 / 13 / 21 ms | 27 / 34 / 34 ms | macOS 27.0 arm64, 14코어, Temurin 25.0.4.1 |
| 로컬(구현 중 첫 측정) | 10 / 13 / 22 ms | 25 / 31 / — ms | 같음 |
| CI run `36856909677` | 35 / 59 / 86 ms | 230 / 290 / 290 ms | ubuntu-latest x64, 4코어, Temurin 25.0.4 |

- 입력은 case-02 상당(임시등록 + 산출불가 + 긴 한글 사유)이다. 4코어 CI 러너의 동시 10건 p95도 0.29초로, 계획의 보고 기준(p95 3초)의 10분의 1이다.
- 봉인 트랜잭션에서 시간 대부분은 렌더가 아니라 저장소 왕복(업로드 2건)이다. `NumberingIT` 2건(50건 동시 + 연말 경계)은 합계 약 7초다.

### ③ 골든 기대값 생성 환경

- 생성: `./gradlew :disclosure-seal:regenerateGolden`(수동 전용 태스크). 환경은 Mac OS X 27.0 aarch64, Eclipse Adoptium 25.0.4.1, 기본 로케일 en_US, 시간대 Asia/Seoul, `file.encoding` UTF-8이다(`expected.properties`의 `generatedOn`).
- 기대값을 바꾼 커밋은 `0f82085`(최초 생성) 하나다. 이후 갱신은 없다.

| 사례 | 구성 | canonical SHA-256 | PDF SHA-256 | 바이트 |
|---|---|---|---|---|
| case-01 | 3사 정상 | `60eb2a4fe827f40b…` | `aac1b622bad9cefa…` | 38,037 |
| case-02 | 임시등록 + 산출불가 + 긴 한글 사유 | `8477b0a81b233728…` | `d1fabbf4173f4912…` | 47,664 |
| case-03 | 50항목(여러 쪽) | `d9c43b8386b5dada…` | `3fd66e549e792244…` | 78,461 |

- CI(ubuntu-latest x64, Temurin 25.0.4)의 `SealGoldenTest`가 커밋된 기대값과 3건 모두 일치했다(run `36856909677` 테스트 보고서: 3 tests, 0 failures). 이로써 "CI Linux = 로컬 macOS"가 증명된다.

### ④ Phase 4(서명) 질문 — §10

## 6. 테스트와 완료 기준

### 테스트 수 (`0ea92ac`에서 `./gradlew clean build --no-build-cache` 10,787건 + 이후 커밋 3건, `895835c`에서 `./gradlew build` 재확인)

| 모듈 / 스위트 | Phase 3A | Phase 3B |
|---|---|---|
| platform-core test | 3,058 | 3,058 |
| platform-canonical test | 1,054 | 1,054 |
| platform-spring test | 13 | 13 |
| disclosure-domain test | 1,084 | 1,084 |
| disclosure-rules test | 1,283 | 1,308 |
| disclosure-workflow test | 1,052 | 1,094 |
| disclosure-seal test | — | 32 |
| disclosure-audit test | 3 | 3 |
| disclosure-app archTest | 40 | 44 |
| disclosure-infra integrationTest | 1,674 | 3,092 |
| disclosure-app integrationTest | 7 | 8 |
| **합계** | **9,268** | **10,790** (실패 0, 스킵 0) |

증가분의 큰 몫은 `SealColumnCheckIT` 1,341건(봉인 컬럼 7개 × 상태 10종 전 조합 1,280 + 형식·정합)이다. 그 밖에 `ImmutabilityTriggerIT` V7 편입분(508건), `CanonicalSchemaTest` 18건, `BindingResolverTest` 21건이 있다.

### 완료 기준

| # | 기준 | 증거 |
|---|---|---|
| S1 | 골든 canonical·PDF SHA-256 = 커밋된 기대값(CI Linux ↔ 로컬 macOS) | `SealGoldenTest.canonicalAndPdfMatchTheCommittedExpectations` 3건. CI `build` 잡에서 통과(§6 CI) |
| S2 | 별도 JVM 2회 재렌더 바이트 동일, 상담일·항목 하나 바꾸면 해시 변경 | `RenderDeterminismIT.storedDocumentRerendersByteForByteInTwoDifferentJvms`(봉인 → DB·저장소에서 canonical 복원 → `RerenderMain`을 두 환경으로 실행 → 저장된 PDF와 3자 바이트 동일, 상담일·항목 변경 시 canonical·PDF 해시 모두 다름), `CanonicalDocumentBuilderTest.consultDateItemOrNameChangesTheHash` |
| S3 | canonical 스키마 통과, 금지 키 실패, 최상위 객체 | `CanonicalSchemaTest`(18: 금지 키 번호·상태·시각·해시·전화·생년월일·`registration_key`를 최상위와 항목에서, 미지 필드, 최상위 배열·스칼라, 정수 범위·소수 거부, 비율 원문 문자열) |
| S4 | 봉인 후 본문·항목·사유 UPDATE 거부, 결속 CHECK 위반 조합 전수 거부 | `ImmutabilityTriggerIT`(508, V7 메타 컬럼 판정·`retentionUntilOnlyExtends`·`supersededByIdIsWriteOnce`), `SealColumnCheckIT`(1,341), `SealTriggerIT`(10) |
| S5 | 동시 50건 번호 연속·중복 0·빈 번호 0, 거부 20건 섞여도 빈 번호 0, 연말 경계(Q7) | `NumberingIT.fiftyConcurrentSealsWithTwentyRejectionsMixedInAndAnotherTenantInParallel`(50 + 거부 20 + 다른 테넌트 병행 → 1..50 연속, 거부는 카운터 미사용, 체인 1..50), `yearBoundarySealsTakeTheirOwnYearCountersAndOneChain`(12/31 23:59 KST와 1/1 00:00 KST 동시 → 각 연도 000001, 체인은 하나로 연속) |
| S6 | 조건 6종 각각 실패 시 전부 불변, 목록에 전부, 감사 1행 | `SealRejectionIT`(7: 6종 각각 + `allSixAtOnceAreReportedTogether`). 매 건 상태, 번호 있는 확인서 수, 카운터·체인 머리·`document_key`·`document_artifact` 행 수, 버킷 객체 수가 불변이고 감사는 정확히 1행(`DISCLOSURE_SEAL_REJECTED`) 늘어난다 |
| S7 | 대상 해시·룰 버전 불일치 승인 무시, 재기준 후 기존 승인 무효 | `SealGateTest`(2), `RebaseIT.rebaseRepinsAndOldApprovalsStopCounting`, DB GD081(`SealTriggerIT.approvalsCarryThePinnedRuleVersions`) |
| S8 | 암호문 저장(평문 해시·성명 없음), 복호화 후 일치, 키 파기 시 불가 | `ArtifactEncryptionIT`(4: 버킷 직접 바이트 검사·AAD 교차 실패, 파기 후 모든 사본 `KEY_SHREDDED`, `UNREADABLE`, `HASH_MISMATCH`) |
| S9 | 커밋 실패 → 잠금 없는 고아만, gc가 치움; 커밋 후 잠금 실패 → reconcile; 잠긴 객체 삭제 불가; Object Lock 계약 5종 | `RetentionOrderIT`(3: `failureBeforeCommitLeavesOnlyUnlockedOrphansThatGcRemoves`, `retentionFailureAfterCommitIsReconciled`, `noLockIsTakenBeforeTheCommit` — 잠금 호출 때 활성 트랜잭션 0), `ArtifactStoreContract`(7 = 5종 + put/get/list + 덮어쓰기) ⊂ `SeaweedArtifactStoreIT`(12) |
| S10 | 체인 연속(테넌트별 재계산 일치), `chain_seq` 갭 0 | `SealChainIT.chainRecomputesFromGenesisAndTenantsAreIndependent`(저장소 객체를 복호화해 처음부터 재계산), DB GD095 |
| S11 | Void·Supersede·Rebase 상태표대로, Supersede 재해석 고정, 플래그 해소 | `LifecycleIT`(9), `RebaseIT`(2), 상태표 W1(`DisclosureStateTableTest`·`DisclosureTransitionTest`에 REBASE 편입) |
| S12 | veraPDF PDF/A-2b 실패 0(CI), 구조 마커 | CI `pdfa-verify`(§6 CI), `PdfAMarkersTest`(3). 로컬에서도 골든 3건·데모 봉인본 `failedChecks=0` |
| S13 | `TEST_ONLY_FIELD`가 추천사유만으로 미충족, 렌더러 항목 코드 리터럴 0 | `RuleFreezeIT.boundaryDraftsPinTheirOwnVersionsAndIgnoreLaterRuleData`, `NoFieldCodeLiteralsTest` |
| S14 | Phase 0~3A 무손상, 평문 유출 0(성명 복호화 경로 포함), BOM·jqwik | 위 10,790건. `PlaintextLeakScanIT.sealPathLeavesNoPlaintextOutsideTheDocument`(봉인 경로의 로그·예외·감사·DB에 성명 평문 0, 봉인본에만 존재), `scanPlaintextLeaks` 87 files 0 hits |

**BOM·의존성**: `main` 대비 락 파일은 추가만 있다(106줄). 기존 좌표의 버전 변경·제거는 0건이다.
- 추가: `software.amazon.awssdk`(30좌표, 2.55.9), `org.apache.pdfbox`(3.0.7, openhtmltopdf 전이), `io.github.openhtmltopdf` 1.1.87, `de.rototor.pdfbox:graphics2d`, `commons-logging`(BOM 1.3.6).
- 기존 좌표의 모듈 확장: `json-schema-validator` 등.
- `net.jqwik`: 락 파일 0건.
- PDFBox 3.0.8이 나와 있지만 3A 실측 조합(3.0.7)을 유지했다. 올리려면 골든 갱신과 그 사유가 필요하다.

### 규칙 테스트 위반 주입 기록 (주입 → 실패 확인 → 제거)

아키텍처·스캔·렌더:

| # | 대상 | 주입 | 결과 |
|---|---|---|---|
| I1 | `SealRules` 환경 무의존(archTest) | `HtmlComposer.compose`에 `Locale.getDefault()`·`Instant.now()` | `sealBodyAndRendererAreEnvironmentFree` 실패(2건) |
| I2 | `CanonicalValue` 금지(archTest) | `workflow.seal.InjectedWrap`이 `CanonicalValue.of` 호출 | `sealPathDoesNotUseTheScalarWrappingConvention` 실패 |
| I3 | `pdfa-verify` | 3A `fs-1.pdf`(비준수)를 골든과 함께 검증 | exit 1, `fs-1.pdf failedChecks=3`(6.2.4.3, 6.6.2.1, 6.2.11.4.2), case-01 compliant |
| I4 | `NoFieldCodeLiteralsTest` | `RenderAssets`에 `"PREMIUM"` 상수 | 실패: `RenderAssets.java: "PREMIUM"` |
| I5 | `SealWriteScanTest` | `SealLedgerRepository#injected`에 `UPDATE document_artifact` | `sealTablesAreWrittenOnlyByTheirRepositoryMethods` 실패 |
| I6 | S8 열람 해시 대조 | `ArtifactService.view`의 평문 해시 비교 무력화 | `viewDeniesPlaintextThatDoesNotMatchTheRecordedHash` 실패(Denied 대신 Granted) |
| I7 | 저장소 하네스 볼륨 한도 | 하네스 `-volume.max=14`(CI 러너의 자동 산정 한도를 흉내) | `SeaweedArtifactStoreIT` 6건이 CI와 같은 S3 500으로 실패(`manyFreshBucketsStayWritable` 포함) + `volumeLimitsArePinnedTheSameInHarnessAndCompose` |
| I8 | compose ↔ 하네스 일치 | compose에서 `-master.volumeSizeLimitMB=64` 삭제 | `volumeLimitsArePinnedTheSameInHarnessAndCompose` 실패 |

V7 트리거·제약(V7 파일을 임시로 고쳐 해당 IT 실행 → 원복):

| # | 대상 | 주입 | 실패한 테스트 |
|---|---|---|---|
| J1 | GD090 카운터 | `NEW.seq <> OLD.seq + 1` → `NEW.seq < OLD.seq`(건너뛰기 허용) | `SealTriggerIT.counterStartsAtOneAndAdvancesByExactlyOne` |
| J2 | GD091 체인 머리 | 실재 봉인 EXISTS 검사 무력화 | `SealTriggerIT.chainHeadAdvancesOnlyToAnExistingSeal` |
| J3 | GD092 문서 키 | 봉인 전 INSERT 검사 무력화 | `SealTriggerIT.documentKeyOnlyForSealedDisclosuresAndOnePerDocument` |
| J4 | GD093 산출물 | 다른 확인서·파기된 키 검사 무력화 | `ownerChangesKeysOnlyThroughTheShredFunction`, `artifactsAreRecordedOnlyForSealedDocumentsWithTheirOwnKey` |
| J5 | GD094 보존기한 | 트리거 `WHEN (false)` | `ImmutabilityTriggerIT.retentionUntilOnlyExtends` × 6(봉인 이후 상태 전부) |
| J6 | GD095 번호 ↔ 카운터 | 카운터 대조 무력화 | `SealColumnCheckIT.numberMustBeTheCurrentCounterValueOfTheSealYear` |
| J7 | GD081 승인 룰 버전 | 대조 무력화 | `SealTriggerIT.approvalsCarryThePinnedRuleVersions` |
| J8 | `ck_disclosure_seal_by_status` | VOID에 `disclosure_no NOT NULL` 요구 | `SealColumnCheckIT` "VOID" mask=0 외 2건 |
| J9 | GD095 체인 식 | `chain_hash` 대조 제거(순번만) | `chainMustExtendTheHead`, `sealingAnExistingRowIsCheckedTheSameWay` |

봉인 유스케이스(소스를 임시로 고쳐 IT 실행 → 원복):

| # | 주입 | 실패한 테스트 |
|---|---|---|
| N1 | 번호 선할당(조건 평가 전 `issueNumber`) | `NumberingIT` 2건 전부, `SealRejectionIT` 7건 전부(카운터 불변 단언) |
| N2 | 첫 거부에서 단락 | `SealRejectionIT.ruleSuperseded`(감사 행 없음), `allSixAtOnceAreReportedTogether` |
| N3 | 커밋 전 잠금(체인 머리 전진 뒤 `applyRetention`) | **1차는 1건만 잡혔다**(`retentionFailureAfterCommitIsReconciled`). 관측 지점이 "산출물 기록 시점의 잠금 유무"여서 기록 뒤에 거는 잠금을 놓쳤다. 테스트를 좁히지 않고, 관측을 "잠금 호출 때 DB 트랜잭션이 열려 있었는가"(`TransactionSynchronizationManager.isActualTransactionActive`)로 강화했다. **2차**: `noLockIsTakenBeforeTheCommit`도 실패 |
| N4 | `SealGate`가 룰 버전 귀속 무시 | `SealGateTest`(단위) |
| N4b | 같은 주입 | `RebaseIT.rebaseRepinsAndOldApprovalsStopCounting`(통합) |

- 모든 주입은 제거한 뒤 전체 빌드가 통과했다.
- 거짓 양성 1건이 있었다. 규칙은 그대로 두고 코드를 옮겼다: `noOrderingInClassesUsing`이 `CanonicalDocumentBuilder`의 항목 코드 `TreeMap` 정렬을 잡아, 정렬을 허용 클래스 `FieldCodeOrder`로 옮겼다.

### CI (1차 증거, `gh run view`)

**1차 run `36855875536`**(pull_request, head `0ea92ac`) — `build` failure, `pdfa-verify`·`no-docker` success.
- `build`: `3090 tests completed, 13 failed`. 실패는 전부 SeaweedFS를 쓰는 테스트였다(`SeaweedArtifactStoreIT` 7, `RetentionOrderIT` 3, `RenderDeterminismIT`·`SealChainIT`·`SealFlowIT`). 모두 `PutObject`에서 `S3Exception: We encountered an internal error … Status Code: 500`이었다. 같은 실행의 다른 봉인 테스트(`NumberingIT`·`SealRejectionIT`·`ArtifactEncryptionIT` 등)는 통과했다.
- 원인(로컬 재현으로 확인)
  - `weed server`의 볼륨 수 한도 기본값은 여유 디스크 ÷ 볼륨 크기(1 GiB)로 자동 산정된다. 로컬 Docker VM은 793이다.
  - 버킷(= 컬렉션)마다 볼륨이 7개 생긴다. 테스트는 격리를 위해 테스트마다 새 버킷을 만든다.
  - 여유 디스크가 작은 CI 러너에서는 십수 번째 버킷부터 새 볼륨을 잡지 못해 첫 쓰기가 500이 된다.
  - 로컬에서 `-volume.max=14`로 띄우면 세 번째 버킷의 첫 PUT이 같은 500 `InternalError`를 낸다.
- 수정(`895835c`): 하네스와 compose가 `-volume.max=2000 -master.volumeSizeLimitMB=64`를 쓴다. 볼륨은 미리 할당하지 않는다(버킷 30개 = 볼륨 210개, 디스크 2 MB 실측).
  - `SeaweedArtifactStoreIT`에 두 테스트를 더했다: compose ↔ 하네스 한도 일치, 새 버킷 20개 연속 쓰기.
  - 주입 I7·I8로 두 테스트가 각각 실패함을 확인했다.
  - 알려진 제약으로 남기지 않고 고쳤다.

**2차 run `36856909677`**(pull_request, head `895835c`) — 전부 success.
- `build`(job `110351460759`)
  - `net.jqwik` 의존 그래프 검사 통과.
  - `scanPlaintextLeaks: 87 result files, 14 forbidden strings, 0 hits`, `BUILD SUCCESSFUL in 3m`.
  - C11 발행 아티팩트 소비 빌드 `BUILD SUCCESSFUL`.
  - 골든 `SealGoldenTest` 3/3, `RenderDeterminismIT` 포함 통합 테스트 전부 통과.
- `pdfa-verify`(job `110351461010`): `case-01.pdf`·`case-02.pdf`·`case-03.pdf` 각각 `flavour=2b declared=2b compliant=true failedChecks=0`, `3 PDFs, 0 non-compliant`.
- `no-docker`(job `110351461211`): `disclosure-infra` 통합 테스트 `200 tests completed, 200 failed`, `result files: 37, with failures/errors: 37, skipped: 0`, `OK: integration tests failed (not skipped) because Docker is missing`.
- 보고서·README 커밋이 올린 새 head의 CI는 PR 코멘트로 덧붙인다(코드는 `seed.sh` 인자 1개뿐이다).

### CLAUDE.md 규칙 9 기록

빌드·테스트·데모 로그(클린 빌드 227행, 데모 181행)에서 지시문 형태의 문장은 0건이었다. 데모 고객 파일의 이름·전화·생년월일 7개 값도 로그에서 0건이었다.

## 7. 설계서와 달리 구현했거나 해석한 지점

| # | 지점 | 이유 |
|---|---|---|
| D1 | 고정 룰 로드(`RuleResolver.load`)가 "상담일에 시행 중"이 아니라 **시작일 ≤ 상담일**만 확인한다(설계서 §6.6 ⑧) | 소급 GLOBAL 룰이 옛 버전의 구간을 상담일 이전으로 닫으면, 이전 구현에서는 그 초안의 모든 명령이 룰 해석 실패(명령 오류)가 된다. 봉인 조건 ①(`RULE_SUPERSEDED`)과 재기준으로 판정·탈출하게 하려면 고정 버전 자체는 계속 로드돼야 한다. 시작 전 버전(미래 룰)은 여전히 거부한다(`RuleResolverTest` 추가) |
| D2 | 모듈 의존 Seal → Rules(설계서 §3.3 ⑥) | 렌더러와 `R-FIELD-REQUIRED`가 같은 `BindingResolver`를 써야 해서다(계획 §1 원칙). Rules는 Spring·DB 무의존이고 역방향 의존은 ArchUnit이 막는다 |
| D3 | 봉인 거부 때 올리는 `RULE_SUPERSEDED_DRAFT` 플래그의 감사는 별도 `FLAG_RAISE` 행이 아니라 거부 1행의 `detail.flag`에 싣는다 | S6 "거부 시 감사 1행"과 3A 규약(플래그마다 `FLAG_RAISE`)이 충돌한다. 지시문의 문언을 따랐다. 플래그 행 자체는 생긴다 |
| D4 | 버킷 확인을 **기동 때가 아니라 저장소 첫 사용 직전**에 한다(`VerifiedArtifactStore`, 설계서 §9) | 계획 §6은 "다르면 기동 실패"였다. 그러면 룰 배포·카탈로그 수입 같은 저장소와 무관한 CLI 명령과 앱 부팅이 저장소 가용성에 묶인다. 확인이 실패하면 그 조작이 어떤 쓰기보다 먼저 실패하므로 커밋 전 잠금 금지는 그대로 보장된다. 버킷 생성은 개발·데모 설정(`create-bucket`)에서만 한다 |
| D5 | **정정 사유를 저장하는 컬럼이 없다.** 감사에 SHA-256·길이만 남긴다(무효 사유는 V6 `void_reason` 컬럼) | V7 설계에 정정 사유 컬럼이 없었고, 자유 텍스트를 감사 원문에 두지 않는 3A 규약을 따랐다. 사유 원문 보관 여부는 질문 §10-5 |
| D6 | 실패 주입 대역을 `FailingArtifactStore` 하나가 아니라 `FailingPorts.Store`·`Records` 둘로 했다 | 커밋 전 실패 지점(업로드 뒤·산출물 기록)은 기록 포트에 있어야 롤백을 일으킨다. 잠금 호출의 트랜잭션 관측(N3 강화)은 저장소 쪽에 둔다 |
| D7 | 봉인 거부·무효·정정 거부의 CLI 종료 코드를 2로 했다(인자·명령 오류는 1) | 스크립트가 업무 거부와 오류를 구분하게 하려는 것이다. Spring이 "Application run failed" 스택을 찍는 것은 3A `CliFailure`와 같다. 메시지에는 거부 코드만 있다 |
| D8 | AWS SDK 2.55.9(계획 2.55.8) | Q12 "착수 시 최신 안정판 확인·고정"(§4) |
| D9 | `STANDARD.v1` 제자리 재해시로 **3A 시절 로컬 DB 볼륨은 그대로 쓸 수 없다** | 직접 확인했다: `main`(3A) jar로 시드한 볼륨에 3B `seed.sh`를 돌리면 V7은 깨끗이 적용된다. 그러나 서식 재배포가 `template STANDARD.v1 already exists with bundle STANDARD.v1@f850a2f9b53e; a changed template needs a new version`으로 거부된다. 불변 규칙이 의도대로 동작한 것이고, 운영 배포 전 형식 변경 조건(설계서 §5)의 결과다. 로컬은 `docker compose down -v` 후 다시 시드한다(README에 명시) |

**CLAUDE.md 문구**: "통합 테스트는 Testcontainers(PostgreSQL·MinIO)"가 남아 있다. 저장소 규칙 파일이라 손대지 않았다(계획 §6). "PostgreSQL·SeaweedFS(S3 호환 + Object Lock)"으로 고칠지 승인을 구한다.

## 8. 데모

로컬 `docker compose -p ga3b`(PostgreSQL 18.6 + SeaweedFS digest 고정, 새 볼륨)에서 부트 jar(JDK 25)를 썼다. KEK는 저장소 밖 임시 디렉터리에 두었다. 순서는 KEK 생성 → 테넌트·룰 배포·승인·활성 → 카탈로그 → 고객 → `demo disclosures` 2회다. 이어서 `disclosure-demo/scripts/seed.sh 2026-09-23` 자체를 새 스택(`-p ga3c`)에서 2회 돌렸다. 1회는 같은 결과(번호 000001·000002, A-1 v2), 2회는 전부 NOOP이었고 둘 다 exit 0이다(§7 D9의 업그레이드 경로 확인도 이 스크립트로 했다).

```
(1회)
DEMO_DISCLOSURE DEMO1 A-1 CREATED id=ed8c3357-… status=REASONED
  A-1 seal -> SEALED no=DEMO1-2026-000001
  A-1 supersede -> SUPERSEDED next=f19cbcc6-…
DEMO_DISCLOSURE DEMO1 A-1 CORRECTED id=f19cbcc6-… version=2 status=REASONED
DEMO_DISCLOSURE DEMO1 A-2 CREATED id=eb37ef6f-… status=REASONED
  A-2 approve R-GRADE-UNAVAILABLE by demo-manager
  A-2 approve R-TEMP-PRODUCT by demo-manager
  A-2 seal -> SEALED no=DEMO1-2026-000002
DEMO_DISCLOSURE DEMO1 A-1-REQUEST CREATED id=99060f3b-… status=REASONED
(2회)
DEMO_DISCLOSURE DEMO1 A-1 NOOP existing=ed8c3357-…   / A-1 seal NOOP (already sealed) / A-1 supersede NOOP (a corrected version exists)
DEMO_DISCLOSURE DEMO1 A-2 NOOP existing=eb37ef6f-…   / A-2 seal NOOP (already sealed)
DEMO_DISCLOSURE DEMO1 A-1-REQUEST NOOP existing=99060f3b-…
```

같은 스택에서 이어서 실행한 운영자 명령:

```
artifacts get --kind PDF (A-2)            -> ARTIFACT_GET … PDF sha256=8e56c34b…fe80 bytes=38976   exit 0  (veraPDF: compliant, failedChecks=0)
artifacts get --kind CANONICAL_JSON (A-1 v1, SUPERSEDED) -> sha256=23480371…694a bytes=2784       exit 0
disclosure seal (A-1-REQUEST, 승인 없음)   -> SEAL DEMO1 99060f3b-… REJECTED [APPROVAL_MISSING]      exit 2
artifacts reconcile --tenants all          -> DEMO1 applied=0 failed=0, DEMO2 applied=0 failed=0      exit 0
artifacts gc --tenants all                 -> DEMO1 scanned=4 deleted=0 referenced=4 young=0 locked=0  exit 0
```

- 정정 사유·예외 승인 사유는 데이터 파일 안의 허구 텍스트다. CLI 운영 명령은 사유를 `--reason-file`로만 받는다.
- 두 실행 로그에서 데모 고객 이름·전화·생년월일 문자열은 0건이었다.
- `OperatorCliIT.demoSeedSealsTwoSupersedesOneAndIsIdempotent`가 같은 흐름을 Testcontainers로 반복한다.

## 9. 엔진

- B4에 따라 엔진 저장소는 손대지 않았다.
- E4 첫 커밋에서 할 일(수용심사 §4 승인분):
  - ① 공통 마이그레이션 번호 규칙 문서화(CONTRIBUTING·CLAUDE.md, 다음 번호 V104부터).
  - ② V104 `DISC_GRADE_SNAPSHOT_ITEM.product_key` 폭 40.

## 10. Phase 4(서명·관리자 확인) 질문

1. **서명 귀속 해시와 PDF.**
   - 규칙 3의 `signed_doc_hash = canonical_hash`만으로는 고객이 실제로 본 PDF 바이트가 서명에 묶이지 않는다. PDF는 체인으로 확인서에 묶여 있을 뿐이다.
   - 권장: 서명 행에 `signed_pdf_sha256`(봉인 PDF 평문 해시)을 함께 두고, DB가 `document_artifact`(PDF)와 일치를 강제한다. 귀속 키는 계속 canonical 해시다.
2. **SIGNED_PDF 생성 방식.**
   - 권장: 봉인 PDF를 바꾸지 않고, 서명 페이지(서명 이미지·시각·채널·본인확인 방식)를 결정론 렌더러로 따로 만든 뒤 **증분 저장**으로 덧붙인다. 원본 바이트가 접두로 남아 봉인 해시 검증이 그대로 된다.
   - 대안: 전체 재렌더. 서명 시각이 들어가 바이트가 매번 달라지고 원본과의 관계를 따로 증명해야 한다.
3. **보존기한 재계산.**
   - 설계서 §9는 "완료일 + `retentionYears`"이고, 3B는 봉인일 하한이다(Q6).
   - 권장: COMPLETED 전이 트랜잭션이 `retention_until`을 완료일 기준으로 연장하고(GD094가 연장만 허용), 커밋 뒤 모든 산출물(PDF·CANONICAL_JSON·SIGNED_PDF·EVIDENCE_ZIP)에 `PutObjectRetention`을 다시 건다. 실패는 3B `reconcile`이 처리하도록, 산출물별 "적용된 기한" 컬럼을 추가한다(현재는 `retention_applied_at`만 있다).
4. **서명 스트로크·증거의 암호화 키.**
   - 설계서는 SIGNED_PDF·EVIDENCE_ZIP이 같은 문서 키를 쓴다고 한다.
   - 권장: 스트로크 원자료도 같은 문서 키로 암호화한다. 그래야 파기 한 번으로 그 확인서의 모든 생체 유사 데이터가 함께 무의미해진다.
5. **정정 사유 원문(D5).** 지금은 감사에 해시·길이만 있다.
   - 권장: V8에 새 버전 행의 `supersede_reason`(무효 사유와 같은 평문 컬럼, 봉인 후 불변)을 둔다. 정정 사유는 민원 재현에 필요한 업무 사실이다.
   - 대안: 현 상태 유지(사유는 증거 패키지에만).
6. **서명 진행 중 VOID·SUPERSEDE.**
   - 권장: 진행 중인 서명 세션과 토큰을 같은 트랜잭션에서 폐기하고, 이미 받은 서명 행은 지우지 않는다(append-only). 정정본은 서명을 처음부터 다시 받는다(해시가 다르다).
7. **식별부 라벨 확정(`TODO(confirm#2)`).** 협회 표준서식 확인 전까지 가정이다. 서명 화면(Phase 4)도 같은 라벨을 쓰게 되므로, 확인 일정이 Phase 4 안에 들어오는지 묻는다.
