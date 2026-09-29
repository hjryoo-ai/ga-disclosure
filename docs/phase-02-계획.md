# Phase 2 구현 계획 (승인 대기)

> 지시문: `docs/phase-02-지시문.md` v1.0. 선행: `docs/phase-01-수용심사.md`. 브랜치 `work/phase-2`(main `83bba98`에서 분기), 태그 `phase-2`.
> 이미 한 것: PR #1 merge commit 병합(`83bba98`), `refs/tags/phase-1` → `5a26bfe`가 `origin/main`에서 도달 가능함을 확인, 원격·로컬 `phase-1` **브랜치** 삭제(태그는 유지, 이름 모호성 해소).

## 0. 착수 전 확인 결과

- **D4 확인 결과 — 규약 불일치.** `RuleApprovalService.approve`의 no-op 경로(이미 APPROVED·ACTIVE·RETIRED)는 **감사 행을 남기지 않는다**. 배포 no-op은 `RULE_DISTRIBUTE` + `detail.outcome=NOOP`을 남긴다. → 선행 A에서 no-op에도 `RULE_APPROVE` + `{outcome: NOOP, status}` 1행을 남기도록 고치고 테스트로 고정.
- **RLS 테스트의 테이블 목록은 자동이 아니다.** `SeedData.TENANT_TABLES`(18개)를 손으로 적은 목록이고 `V2__rls.sql` 배열도 손목록. SQL 스캔(`SqlTenantScanner.tablesDefinedIn`)은 마이그레이션의 `CREATE TABLE`에서 읽으므로 자동. → P7에서 RLS 테스트를 카탈로그(`pg_class`) 조회로 바꾼다(§5).
- **`RULE_DRIFT`는 대사 실행마다 새 플래그를 올린다**(중복 제거 없음). `RULE_ACTIVATION_MISSED`를 일 배치로 올리면 같은 문제가 매일 생긴다 → §1-B의 대상 컬럼 + 열린 플래그 유일 인덱스.
- 지시문의 "마스킹 규칙 … §14 #8"은 이 저장소 설계서에서는 **동의 문구** 항목이다(포털 설계서 번호로 보임). 마스킹은 §14에 새 항목 #12로 추가한다.

## 1. 선행 소과제

### A. 수용 심사 §3 반영 (설계서·CLAUDE.md·스키마·번들·코드)

| 항목 | 변경 |
|---|---|
| 검증 단계 | 닫힌 enum `ValidationStage {COMPARE, GRADE, REASON, SEAL, COMPLETE}`(`disclosure-domain` enums, 승인된 닫힌 어휘). 스키마 `validations.items` = `{id, stages}`(`stages` minItems 1·uniqueItems·enum, `additionalProperties:false`, 기본값 없음). `EffectiveRule.validations()` → `List<ValidationStep(id, stages)>`, `ValidationRegistry.plan(stage)`·`run(stage, …)`은 그 단계에 나열된 규칙만 룰 순서대로 실행. |
| 단계 배정(부록 D) | COMPARE+SEAL: MIN-COMPARE·DISTINCT-INSURER·SAME-GROUP·PANEL·TEMP-PRODUCT / GRADE+SEAL: GRADE-REQUIRED·GRADE-UNAVAILABLE·RANK-MONOTONIC / REASON+SEAL: REASON·**REQUESTED**(사유 코드 자동 부가를 검사하므로)·**FIELD-REQUIRED**(AGENT·ENGINE 출처 항목이 사유 단계까지 채워지므로) / COMPLETE: SIGNER-SET |
| `exceptionApproval` | 스키마 필수 키 `{ "role": enum[MANAGER] }`, `tenantOverridable` 금지 목록(`not/enum`)에 추가. `EffectiveRule.exceptionApprovalRole()`(기본값 없음). §6.5에 서명 ≠ 예외 승인 분리 서술. |
| 번들 제자리 수정 | `DISC-2026-07`·`DISC-2027-01`·(테스트) `DISC-TEST-REASON`. 해시가 바뀌므로 `bundleId`(@hash12)·`CHECKSUMS`·테스트 기대값 갱신. C1(`RuleAsDataIT`)은 그대로 통과해야 하고, 두 데이터셋 차이(4군데·1개·1개) 단언도 유지. |
| 제자리 수정 조건 | §5 주석 "번들 형식 변경은 첫 운영 배포 전에만 제자리 수정, 이후는 새 `rule_version_id`". **(제안)** 이를 테스트로도 강제: `contracts/rules/released-bundles.txt`(현재 빈 목록)에 적힌 bundleId의 파일 해시가 바뀌면 실패하는 `ReleasedBundlesAreFrozenTest`. |
| D2 규약 | CLAUDE.md 규칙 5·설계서 §3.3·§9에 "직접 접근 허용 목록은 닫힌 FQN 열거이고, 각 항목은 테넌트 데이터를 읽지 않으며 전용 롤을 쓴다". §9에 표(현재 `TenantDirectoryReader`, 예정 Phase 8 `DatabaseHealthIndicator`). §12 Phase 8의 "유일한 허용 예외" 문구도 정정. |
| D3 | §5 활성화 배치 주석에 `RULE_ACTIVATION_MISSED` (구현은 B) |
| D4 | 위 0절 — 재승인 no-op 감사 행 |
| 아티팩트 | §11에 GitHub Packages·`platform-v*`·mavenLocal 우선·SemVer 동일 버전 (구현은 C) |
| 문서 버전 | 심사 지시대로 §3 5건은 **v1.5 변경 이력에 한 줄 추가**. Phase 2 자체 변경(V5·암호화·카탈로그)은 v1.6. |

### B. `RULE_ACTIVATION_MISSED`
- V5: `compliance_flag`에 `target_kind TEXT`, `target_id TEXT`(둘 다 NULL이거나 둘 다 값, CHECK) + 열린 플래그 유일 인덱스 `(tenant_id, type, target_kind, target_id) WHERE resolved_at IS NULL AND target_id IS NOT NULL`.
- `ComplianceFlagPort.raiseOpen(type, severity, targetKind, targetId, at)` → 같은 대상의 열린 플래그가 있으면 그 ID(새 행 없음). 활성화 배치가 만료 APPROVED 룰마다 `RULE_ACTIVATION_MISSED`(HIGH, `RULE_VERSION`/id)를 올리고 `ActivationReport.expired`에 플래그 ID 포함, 감사 `FLAG_RAISE`.
- **(제안)** `RULE_DRIFT`도 같은 `raiseOpen`으로(대사 재실행이 플래그를 복제하지 않음). Phase 1 동작 변경이므로 승인 필요.

### C. 플랫폼 아티팩트 발행
- 루트 빌드: `maven-publish` 저장소 `GitHubPackages` = `https://maven.pkg.github.com/hjryoo-ai/ga-disclosure`, 자격은 환경변수(`GITHUB_ACTOR`/`GITHUB_TOKEN`)가 있을 때만 구성.
- 새 워크플로 `publish-platform.yml`(on push tags `platform-v*`, `permissions: packages: write`): 태그 버전 = 빌드 버전(0.1.0) 검사 → 세 모듈 테스트 → 발행 → `verification/published-consumer`를 **mavenLocal 없이 GitHub Packages만으로** 해석·컴파일(발행물 소비 가능 증명).
- 태그 `platform-v0.1.0`은 C가 끝난 `work/phase-2` 커밋에 붙여 push → 실행 로그가 P8 증거. GitHub Packages는 같은 버전 재발행이 안 되므로 실패 시 0.1.1로 올린다. **공개 발행이라 이 계획 승인을 태그 push 승인으로 간주한다.**
- README: "mavenLocal 우선(`./gradlew publishToMavenLocal`), 없으면 GitHub Packages(`read:packages` 토큰)" 두 경로와 소비 측 `repositories {}` 예시.

## 2. 암호화 방식

| 항목 | 결정 |
|---|---|
| 알고리즘 | AES-256-GCM(JCA `AES/GCM/NoPadding`), 랜덤 96비트 nonce(`SecureRandom`), 128비트 태그. 추가 라이브러리 없음. |
| 저장 형식 | `BYTEA = 0x01(형식 버전) ‖ nonce(12) ‖ ciphertext‖tag` |
| AAD | JCS 정규 JSON `{"column","customerRef","keyId","table":"customer_ref","tenantId","v":1}`의 UTF-8 — 구분자 모호성 없음. 다른 행·컬럼·**키 ID 컬럼**으로 옮겨도 태그 검증 실패. |
| 평문 정규화 | 이름 NFC·trim, 전화 숫자만(010…), 생년월일 `yyyy-MM-dd` |
| 키 계층 | 테넌트별 DEK(256비트) ← KEK. DEK는 KEK로 감싼(wrapped) 형태로만 DB `customer_data_key`에 저장. 감싸기 AAD = `{tenantId, keyId, kekId}`(DEK를 다른 테넌트로 옮기면 풀리지 않음). |
| 포트 | `KeyProviderPort`(workflow): `currentKekId()`, `wrap(tenant, keyId, dek)`, `unwrap(tenant, keyId, wrapped)`. 운영 = KMS 어댑터(인터페이스만, 암호화 컨텍스트 = 위 AAD), 개발·테스트 = `LocalFileKeyProvider`(KEK JSON 파일, 권한 0600 검사, 저장소 밖 경로 설정 `ga.crypto.local-kek-file`, CLI `crypto init-kek`). |
| 키 ID | 행마다 `customer_ref.enc_key_id`(세 컬럼 공통) → FK `customer_data_key`. 테넌트당 ACTIVE 1개(부분 유일 인덱스). 첫 등록 시 DEK 자동 생성. |
| 순환 `customer rekey` | ① 같은 트랜잭션에서 ACTIVE→RETIRED, 새 DEK ACTIVE ② 구 키 행을 배치(트랜잭션별)로 복호화→새 키 재암호화 ③ 남은 행 0이면 구 키 DESTROYED(`wrapped_key` NULL). DB 트리거가 참조 행이 남은 키의 DESTROY를 거부. 중단 시 재실행으로 이어감. 모든 단계 감사(`CUSTOMER_KEY_ROTATE`·`CUSTOMER_REKEY`). |
| 코드 위치 | JCA 사용은 `disclosure-infra`의 `infra.crypto`만(ArchUnit으로 `javax.crypto` 사용처 제한). |

**값객체(`disclosure-domain` `domain.pii`)**
- `Sensitive<T>`: final class, `Serializable` 아님, 게터 없음(Jackson 기본 매퍼는 빈 빈으로 실패), `toString()` = 고정 문자열 `Sensitive[****]`(부분 노출 없음), `equals` = `MessageDigest.isEqual`, `hashCode` 상수. 원문 접근은 `reveal(Function)`뿐이고 호출처를 FQN 허용 목록으로 제한.
- `CustomerName`·`PhoneNumber`·`BirthDate`: final class(record 아님), 생성은 `Sensitive<…>`를 반환하는 정적 팩터리뿐, 파싱 오류 메시지에 입력값 없음.
- `BirthDate.matches(Sensitive<BirthDate>, String input)`: 두 값을 8바이트 `yyyyMMdd`로 정규화해 `MessageDigest.isEqual`. 형식이 틀린 입력도 같은 길이의 더미로 비교해 false(분기 시간 차 최소화). 결과 boolean만 반환.
- **마스킹**: `toString`은 전부 가림이고, 화면용 부분 마스킹(예: 홍*동, 010-****-1234)은 데이터. 규제 번들에 기본값 `masking`을 두고 `tenantOverridable`에 `masking`을 열어 테넌트가 덮어쓴다(기준일 해석·번들 대사 재사용). `MaskedView`가 `EffectiveRule`의 규칙으로 문자열을 만든다. `TODO(confirm#12)`.
- 서비스(`disclosure-workflow` `customer`): `CustomerRefService.register/lookup/phoneForNotification`. ID는 PII와 무관한 무작위(`CR-` + UUID 16진). 감사: `CUSTOMER_REGISTER`, `CUSTOMER_VIEW`, `CUSTOMER_PHONE_READ`(`purpose=REMOTE_LINK` + 호출자가 준 참조 ID) — detail에 PII 없음.

## 3. V5 DDL 초안

```sql
-- 키 저장소(감싼 DEK)
CREATE TABLE customer_data_key (
  tenant_id TEXT NOT NULL, key_id TEXT NOT NULL,
  kek_id TEXT NOT NULL, wrapped_key BYTEA,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE','RETIRED','DESTROYED')),
  created_at TIMESTAMPTZ NOT NULL, retired_at TIMESTAMPTZ, destroyed_at TIMESTAMPTZ,
  PRIMARY KEY (tenant_id, key_id),
  CHECK ((status = 'DESTROYED') = (wrapped_key IS NULL))
);
CREATE UNIQUE INDEX ux_customer_data_key_active ON customer_data_key (tenant_id) WHERE status = 'ACTIVE';
-- 트리거: status 순방향만(ACTIVE→RETIRED→DESTROYED), kek_id·created_at 불변, wrapped_key는 DESTROY 때만 NULL로,
--         DESTROY는 이 키를 쓰는 customer_ref 행이 0일 때만, DELETE 거부

-- 고객 참조
ALTER TABLE customer_ref DROP COLUMN birth_year;
ALTER TABLE customer_ref ADD COLUMN birth_date_enc BYTEA;
ALTER TABLE customer_ref ADD COLUMN enc_key_id TEXT NOT NULL;
ALTER TABLE customer_ref ADD CONSTRAINT fk_customer_ref_key
  FOREIGN KEY (tenant_id, enc_key_id) REFERENCES customer_data_key (tenant_id, key_id);
-- phone_enc·birth_date_enc NULL 허용. 트리거: DELETE 거부(파기는 Phase 5 배치)

-- 카탈로그(외부 정본의 캐시): 반개구간 [from, to), 출처·파일 해시·동기 시각 필수, DELETE 거부
ALTER TABLE product_group   ADD COLUMN source_ref TEXT NOT NULL, ADD COLUMN synced_at TIMESTAMPTZ NOT NULL,
                            ADD CHECK (apply_to IS NULL OR apply_to >= apply_from);
ALTER TABLE product_catalog ALTER COLUMN sale_from SET NOT NULL,
                            ADD COLUMN source_ref TEXT NOT NULL,          -- '{파일명}@sha256:{hex}'
                            ADD CHECK (sale_to IS NULL OR sale_to >= sale_from),
                            ADD FOREIGN KEY (tenant_id, group_code) REFERENCES product_group (tenant_id, group_code);
CREATE INDEX ix_catalog_group ON product_catalog (tenant_id, group_code, insurer_code);
ALTER TABLE insurer_panel   ADD COLUMN source TEXT NOT NULL, ADD COLUMN source_ref TEXT NOT NULL,
                            ADD COLUMN synced_at TIMESTAMPTZ NOT NULL,
                            ADD CHECK (active_to IS NULL OR active_to >= active_from),
                            ADD EXCLUDE USING gist (tenant_id WITH =, insurer_code WITH =,
                                                    daterange(active_from, active_to, '[)') WITH &&);
-- (빈 구간 [x,x)는 "시작 전 철회된 상품"을 닫는 데 쓴다)

CREATE TABLE catalog_import (          -- 수입 이력(append-only). 멱등 판정의 근거
  tenant_id TEXT NOT NULL, import_id UUID NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('PRODUCT_GROUPS','PRODUCTS','INSURER_PANEL')),
  file_name TEXT NOT NULL, file_sha256 TEXT NOT NULL, source TEXT NOT NULL, as_of DATE NOT NULL,
  imported_at TIMESTAMPTZ NOT NULL, inserted INT NOT NULL, updated INT NOT NULL, closed INT NOT NULL, unchanged INT NOT NULL,
  PRIMARY KEY (tenant_id, import_id), UNIQUE (tenant_id, kind, file_sha256)
);

-- 준법 플래그 대상(선행 B)
ALTER TABLE compliance_flag ADD COLUMN target_kind TEXT, ADD COLUMN target_id TEXT,
  ADD CHECK ((target_kind IS NULL) = (target_id IS NULL));
CREATE UNIQUE INDEX ux_flag_open_target ON compliance_flag (tenant_id, type, target_kind, target_id)
  WHERE resolved_at IS NULL AND target_id IS NOT NULL;

-- 새 테이블 2개: V2와 같은 RLS(ENABLE+FORCE, tenant_isolation 정책), disclosure_app DML 권한
```

V1~V4는 수정하지 않는다. 기존 테이블들은 아직 쓰는 코드가 없어 비어 있으므로 NOT NULL 추가가 안전하다(운영 데이터가 있었다면 기본값·백필이 필요 — 보고서에 명시).

## 4. 카탈로그 파일 형식: **JSON 한 가지**

CSV를 쓰지 않는 이유: `defaults`(보험료 예시·해약환급 표)가 중첩 구조이고, 룰 번들과 같은 JSON Schema 검증기(networknt)와 엄격 파서(중복 키 거부)를 재사용할 수 있다.

`contracts/catalog/v1/catalog-file.schema.json` — `kind`로 분기:

```json
{ "schemaVersion": 1, "kind": "PRODUCTS", "source": "DEMO_ASSOC_FILE", "asOf": "2026-09-01",
  "products": [
    { "productKey": "INS-A:PRD-1001", "insurerCode": "INS-A", "groupCode": "PG-HEALTH-SIMPLE-NR",
      "productName": "(가상) 간편 건강보험 무해지형", "saleFrom": "2026-01-01", "saleTo": null,
      "defaults": { "PREMIUM_EXAMPLE_WON": 32100, "INSURANCE_PERIOD": "20년납 100세만기" } } ] }
```
- `kind=PRODUCT_GROUPS`: `groups[{groupCode, name, line(LIFE|NONLIFE), applyFrom, applyTo}]`
- `kind=INSURER_PANEL`: `insurers[{insurerCode, insurerName, line, activeFrom, activeTo}]`
- 키 형식은 엔진 계약과 같은 패턴(`productKey` = `INSURER:PRODUCT`). `defaults` 값은 문자열·정수·불리언과 그 배열·객체만(실수 금지 — 금액은 정수 원). 상품군 코드는 형식만 검사하고 목록을 코드에 두지 않는다.

**수입 규칙**(`catalog import --tenant T1 --file <path> --operator me`, 서비스 `CatalogImportService`)
1. 엄격 파싱·스키마 검증 → 파일 SHA-256.
2. 같은 `(kind, file_sha256)`가 이미 있으면 **NOOP**(감사 `CATALOG_IMPORT` + `outcome=NOOP`).
3. **(해석)** `asOf`가 같은 kind의 마지막 수입 `asOf`보다 이르면 거부 — 옛 파일 재수입으로 캐시가 되돌아가는 것을 막는다.
4. 행 단위 upsert: 새 키 INSERT, 바뀐 행 UPDATE(`productKey`의 `insurerCode` 변경은 거부 — 키 정체성), 같은 행은 unchanged. 모든 행에 `source`·`source_ref`·`synced_at`(주입된 Clock) 기록.
5. 파일에 없고 `asOf` 시점에 열려 있는 행은 `sale_to`(`apply_to`·`active_to`)를 `asOf`로 닫는다. 삭제하지 않는다(DELETE는 트리거가 거부).
6. `catalog_import` 1행 + 감사 1행, 전부 한 트랜잭션.
- **(해석)** `product_catalog`은 상품당 1행(설계서 §5 PK)이므로 값 변경 이력은 행에 남지 않고 감사 detail(이전→이후)과 `catalog_import`에 남는다. 확인서는 봉인 시 상품명 등을 `disclosure_item`에 복사하므로 카탈로그 이력에 의존하지 않는다.

**조회 포트**(`disclosure-workflow` `catalog`, 전부 `TenantId` + `asOf` 필수, 기본값 없음): `ProductCatalogPort.listGroups/searchProducts/getProduct`, `InsurerPanelPort.isOnPanel`. `ValidationSubject.isInsurerOnPanel`은 `InsurerPanelPort`를 테넌트에 묶은 함수로 연결하고, 테스트 픽스처 `TestSubject`가 그 함수를 받는다. `PanelValidationIT`가 DB 어댑터로 R-PANEL을 실행.

데모: `disclosure-demo`에 가상 `PG-…` 상품군 2개·보험사 6개·상품 약 12개 파일, `seed-phase1.sh` → `seed.sh`로 카탈로그 수입·데모 고객 등록 추가(부록 B 규모는 Phase 8).

## 5. 평문 유출 검증 (P4·P5·P6·P7)

- **센티널**: 이름 `갸냐댜QZX7F3K`, 전화 `01047291836`, 생년월일 `1931-07-19`. 스캔 대상 문자열 = 각 값 + 변형(`010-4729-1836`, `4729-1836`, `19310719`) + **UTF-8 16진 표현**(평문을 BYTEA로 잘못 넣으면 덤프에 `\x…`로 나오므로).
- **`PlaintextLeakScanIT`가 검사하는 출력 경로**: ① logback 전 로거(TRACE)를 메모리 어펜더로 수집 ② `System.out`/`System.err` ③ 고의로 일으킨 실패의 예외 메시지·스택(잘못된 형식, AAD 불일치, 파기된 키, 다른 테넌트, 존재하지 않는 ref) ④ 반환 객체 전부의 `toString()` ⑤ `audit_log` 전 행 ⑥ 컨테이너 안 `pg_dump --data-only`(superuser, RLS 우회) ⑦ PostgreSQL 컨테이너 로그.
- **Gradle 수준**: `scanPlaintextLeaks` 태스크가 infra·app의 `integrationTest` 뒤에 `build/test-results/**/*.xml`(표준 출력·오류·실패 메시지 포함)을 센티널로 스캔하고 `check`에 연결 — 다른 IT가 새는 경로도 잡는다.
- **ArchUnit 추가**(위반 주입 → 실패 → 제거 기록): `Sensitive`를 컴포넌트로 가진 `record` 금지(전 모듈, 허용 목록 없음), `CustomerName`·`PhoneNumber`·`BirthDate` 필드는 `domain.pii` 밖 금지, `Sensitive.reveal` 호출처 FQN 허용 목록, `javax.crypto`는 `infra.crypto`만, `BirthDate.matches`는 `MessageDigest.isEqual`을 호출하고 `String.equals`를 호출하지 않음(P6).
- **Jackson**: 기본 `JsonMapper`와 앱이 쓰는 매퍼(`infra.json`에 `Sensitive` 직렬화 시 예외를 던지는 모듈 등록) 둘 다 예외, Java 직렬화도 `NotSerializableException`.
- **P7**: RLS 테스트의 테이블 목록을 `pg_class`에서 읽는다(public 스키마 전 테이블 − `flyway_schema_history`). 각 테이블의 첫 컬럼 `tenant_id`, FORCE RLS, `tenant_isolation` 정책을 검사하고, 테이블 수 20을 고정 단언해 새 테이블 추가를 드러낸다. `SeedData.TENANT_TABLES`는 이 조회로 대체.

## 6. 완료 기준 → 증거 계획

| # | 테스트 |
|---|---|
| P1 | `CatalogAsOfIT` — 상품·상품군·패널 각각 `from-1`/`from`/`to-1`/`to` 네 날짜, 빈 구간, 패널 겹침 23P01 |
| P2 | `CatalogImportIT` — 같은 파일 NOOP(행·감사), 사라진 상품 `sale_to`=asOf(행 존재), DELETE 거부, `source_ref`·`synced_at`, 이른 asOf 거부, insurer 변경 거부 |
| P3 | `CustomerEncryptionIT` — 왕복, 행·컬럼·키 ID 이식 → 복호화 실패, rekey 도중 구·신 혼재 복호화, 완료 후 구 키 DESTROYED·전 행 신 키, 참조 남은 키 DESTROY 트리거 거부 |
| P4 | `PlaintextLeakScanIT` + `scanPlaintextLeaks` |
| P5 | `ArchitectureRulesTest` 확장 + `SensitiveSerializationTest` |
| P6 | `IdentityMatchTest` + P4 |
| P7 | `RlsIsolationIT`(카탈로그 조회), `TenantPredicateScanTest`(새 테이블 자동 포함 확인) |
| P8 | `RuleAsDataIT`(C1), `RuleActivationIT`(MISSED 플래그·재실행 중복 없음), `RuleApprovalNoopAuditIT`, CI `publish-platform` 실행 로그 |
| P9 | `./gradlew clean build`, BOM 대조, `net.jqwik` 0 |

## 7. 승인이 필요한 해석·제안

1. 검증 단계 배정에서 R-REQUESTED·R-FIELD-REQUIRED를 REASON+SEAL로 둔 것.
2. `released-bundles.txt` 동결 테스트(제안).
3. `compliance_flag` 대상 컬럼 + 열린 플래그 유일 인덱스, `RULE_DRIFT`에도 적용(Phase 1 동작 변경).
4. 마스킹 = 규제 번들 `masking` 기본값 + `tenantOverridable`(tenant.params가 아니라 룰 데이터), §14 #12 신설.
5. 카탈로그 JSON, 이른 `asOf` 거부, 카탈로그 3테이블 DELETE 거부, 상품 행 이력은 감사로만.
6. 태그 `platform-v0.1.0` push(공개 발행) — 이 계획 승인으로 진행.
7. **엔진 계약 변경(E3 계획에서 요청)**: `engine-disclosure.openapi.yaml`에 401·403(양 연산), 422(POST — 정책 0건, 자기 검증 실패) 응답 추가. 심사 규칙("계약에 손댄 쪽이 먼저 보고")에 따라 이 저장소에서 **먼저** 작은 별도 PR로 처리하고 E3가 그 커밋을 고정 참조하는 것을 권장.
