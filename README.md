# ga-disclosure

대형 GA(소속 설계사 500인 이상) 설계사가 계약 체결 전에 **동종·유사상품 3개 이상 비교표 → 판매수수료 등급·순위 표기 → 추천사유 → 고객·설계사·관리자 확인(서명) → 불변 보관·감사**를 한 흐름으로 끝내는 멀티테넌트 워크플로 서비스다. 등급·순위의 산출은 `ga-commission-engine`이 하고 이 저장소는 그 결과를 스냅샷으로 받아 검증·표시·보관만 하며, 봉인된 확인서는 불변이고 서명은 문서 해시에 귀속된다. 정본 설계는 [`docs/설계서.md`](docs/설계서.md), 작업 규약은 [`CLAUDE.md`](CLAUDE.md), 진행 단위는 `docs/phase-NN-지시문.md`다.

## 면책

학습·포트폴리오 목적 저장소. 규제 내용(비교·설명 의무, 등급 임계치, 서식 항목, 서명 요건)은 금융위원회·보험GA협회 공개 자료 기반의 예시적 정리이며, 실제 적용 기준은 「보험업감독규정」·시행세칙·협회 표준서식·유권해석 원문을 따른다. 확인서 번호 체계·기한·보존기간 등 수치는 가상의 예시값이다. README와 화면 어디에도 "법령 요건 충족"을 시스템이 단언하는 문구를 두지 않는다.

## 실행

요구사항: **Docker**(통합 테스트가 Testcontainers로 PostgreSQL 18과 SeaweedFS(S3 호환 + Object Lock)를 띄운다 — Docker가 없으면 통합 테스트는 스킵되지 않고 **실패**한다). JDK는 Gradle 툴체인이 Java 25를 자동으로 받는다(실행용 JDK는 17 이상이면 된다).

```bash
./gradlew build                     # 단위 + 아키텍처(archTest) + 통합(integrationTest) + 계약 체크섬 검사
./gradlew verifyPublishedPlatform   # platform-core·platform-canonical·platform-spring을 mavenLocal에 발행하고 발행물만으로 검증
./gradlew contractChecksums         # contracts/ 변경 후 contracts/CHECKSUMS 갱신
./gradlew resolveAndLockAll --write-locks   # 의존성 추가 후 락 파일 갱신
docker compose up -d postgres seaweedfs   # 로컬 DB(롤 초기화 포함) + 봉인 산출물 저장소(SeaweedFS, digest 고정, 허구 S3 키)
disclosure-demo/scripts/seed.sh    # 데모 테넌트 2개 + 규제 번들 배포·사규 승인·활성화·대사 + 카탈로그 수입 + 로컬 KEK + 가상 고객 + 데모 확인서 봉인·정정 + 서명·완료·만료(운영자 CLI, 멱등)
```

운영자 CLI는 `cli` 프로파일로 웹 서버 없이 실행된다(모든 행위는 `audit_log`에 `actor_role=OPERATOR`로 남는다 — `--role`은 6A에서 폐기, 업무 역할은 `--operator` 주체의 `identity_link`). 작업 잠금은 전용 롤 `disclosure_job_lock`으로 따로 연결한다(`DISCLOSURE_JOB_LOCK_USER`·`DISCLOSURE_JOB_LOCK_PASSWORD`, 로컬 기본값은 `init-roles.sql`의 허구 자격 증명).

```bash
./gradlew :disclosure-app:bootRun --args="--spring.profiles.active=cli rules distribute --bundle rules/DISC-2027-01.bundle.json --tenants all --operator me"
# rules approve --tenant T1 --rule <id> | rules activate [--as-of 2027-01-01] | rules reconcile | demo seed --file <json>
# catalog import --tenant T1 --file <json> | customer rekey --tenant T1 [--batch 500] | crypto init-kek --file <path> [--kek-id KEK-LOCAL-1]
# (3B) disclosure seal|rebase --tenant T1 --id <uuid> | disclosure void|supersede --tenant T1 --id <uuid> --reason-code <CODE> [--reason-file <path>] --operator <id>
#      artifacts get --tenant T1 --id <uuid> --kind PDF|CANONICAL_JSON|SIGNED_PDF|EVIDENCE_ZIP --out <path> | artifacts gc|reconcile --tenants all
# (4) sign session --tenant T1 --id <uuid> --channel TOUCH_PAD|REMOTE_LINK|PAPER_SCAN | sign open|verify|capture|scan --token <token> (입력은 --*-file)
#     sign agent|manager|review-scan --tenant T1 --id <uuid> | disclosure complete --tenant T1 --id <uuid> | disclosure expire [--as-of <instant>|P30D]
# (5) anchor run [--date YYYY-MM-DD] | anchor receipt export --tenant T1 --id <uuid> --out <json> (스텁 TSA: --ga.tsa.mode=stub)
#     verify package --package <zip> [--receipt <json>] [--tsa-trust <pem>] (0 일치, 2 불일치, 3 입력 오류) | verify tenant [--tenants all]
#     retention destroy [--tenants all] [--dry-run yes] | legal-hold place --tenant T1 --id <uuid> --reason-code <CODE> | legal-hold release --hold <uuid>
# (6A) jobs list --tenant T1 [--limit 20] | jobs show --tenant T1 --id <uuid> | jobs report --tenant T1 --id <uuid> --out <json>
#      notify dispatch [--tenants all] [--limit 100] — 원격 링크는 발급 때 아웃박스에 적재되고(sign session … queued=) 이 명령이 보낸다(링크는 …/s#{token})
#      배치 명령(anchor run·verify tenant·retention destroy·disclosure expire·artifacts reconcile)은 작업 실행기를 지나며 테넌트마다 `JOB <id> <status>` 줄을 더한다
# 업무 거부(봉인 조건 실패 등)·같은 종류 작업이 이미 도는 테넌트·FAILED 작업은 종료 코드 2, 인자·명령 오류는 1
```

고객 필드 암호화의 로컬 KEK는 **저장소 밖** 파일이다(`GA_LOCAL_KEK_FILE`, 기본 `~/.ga-disclosure/kek.json`, 권한 600이 아니면 기동 실패). 운영 KMS 연동은 `KeyProviderPort` 구현 교체로 한다(설계서 §9).

`init-roles.sql`에 롤이 추가되면(Phase 1: `disclosure_operator`) 기존 로컬 볼륨에는 반영되지 않는다 — `docker compose down -v` 후 다시 올린다. Phase 3B에서 표준 서식 `STANDARD.v1`을 제자리로 다시 해시했으므로(운영 배포 전 형식 변경) 3A 이전에 시드한 로컬 볼륨도 `down -v`가 필요하다. Phase 4도 룰 번들 `DISC-2026-07`·`DISC-2027-01`과 서식을 제자리로 다시 해시했다(서명 룰 키) — 3B 이전 볼륨은 `down -v`. Phase 5는 파기 롤 `disclosure_destroyer`·`disclosure_destroy_definer`와 멤버십을 `init-roles.sql`에 더했다 — V9가 롤이 없으면 실패하므로 4 이전 볼륨은 `down -v`. Phase 6A는 작업 잠금 롤 `disclosure_job_lock`을 더했고(V12가 롤이 없으면 실패), 룰 번들 네 개를 제자리로 다시 해시했으며(6A 룰 키), 앵커 날짜를 생성 시각의 KST 날짜로 묶는 CHECK가 옛 데모의 소급 앵커("어제 날짜로 지금 머리")를 거부한다 — 5 이전 볼륨은 `down -v`(사용자 결정, 스크립트는 볼륨을 지우지 않는다).

## 룰은 코드가 아니라 데이터다 (Phase 1)

- 규제(GLOBAL) 룰과 표준 서식의 정본은 `contracts/rules/bundles/`의 번들 파일이다. 배포 명령이 테넌트마다 같은 ID로 복제하고 `bundle_hash = SHA-256(JCS(body))`를 남기며, 복제본은 DB 트리거로 불변이다. 준법 배치가 해시를 다시 계산해 번들과 대조하고, 다르면 `RULE_DRIFT` 플래그를 올린다.
- 사규(TENANT) 룰은 규제 룰이 `tenantOverridable`로 열어 둔 키만 덮어쓸 수 있다. 그 목록도, 목록에 넣을 수 없는 규제 핵심 키도 데이터(스키마)다 — "사규는 규제를 완화할 수 없다".
- 최소 비교 개수·관리자 확인 모드·서식 항목·사유 코드를 바꾸는 것은 번들 교체뿐이고 코드 변경은 없다. `RuleAsDataIT`가 같은 빌드로 두 데이터셋을 실행해 이를 증명한다.

## 방어선은 서로 다른 것을 본다

테넌트 격리는 세 겹이다: 저장소 기반 클래스의 런타임 가드(`:tenantId` 바인드 변수 강제), 아키텍처 테스트의 정적 SQL 스캔(`tenant_id =` 조건 강제), PostgreSQL RLS. Phase 0의 위반 주입에서 `… WHERE status = 'ACTIVE' AND :tenantId IS NOT NULL`은 **런타임 가드**(`:tenantId` 존재 여부만 확인)를 통과했지만 **정적 스캔**이 잡았다. 두 방어가 서로 보완한다는 증거이고, 마지막 방어선은 RLS다([Phase 0 보고서 §5](docs/phase-00-보고서.md)).

## 카탈로그·고객 참조 (Phase 2)

- 상품군·보험사 패널·상품은 파일(`contracts/catalog/v1/catalog-file.schema.json`) 수입으로만 들어온다. 행 단위 upsert가 유효기간 `[from, to)`를 닫고 새 구간을 열며, 같은 파일(kind·SHA-256)은 NOOP, 마지막 수입보다 이른 기준일은 거절된다. 카탈로그 행은 DB 트리거로 삭제할 수 없고, 변경 이력은 감사 로그(`CATALOG_IMPORT`)에 남는다. 비교 검증(`R-PANEL`)은 기준일 시점의 패널로 판정한다.
- 고객 이름·연락처·생년월일은 테넌트별 데이터 키(AES-256-GCM, AAD에 테넌트·테이블·컬럼·고객 참조 ID를 묶음)로 컬럼 암호화되고, 데이터 키는 KEK로 감싼다. 도메인에서는 `Sensitive<T>`로만 다니며 `toString`은 마스킹, 평문은 허용된 패키지의 `reveal`로만 꺼낸다(ArchUnit). 화면 마스킹 규칙도 룰 데이터(`masking`)다.
- 모든 테스트 출력(결과 XML·로그)과 DB 덤프·PostgreSQL 서버 로그를 평문 센티널로 스캔한다(`scanPlaintextLeaks`, `PlaintextLeakScanIT`). 한 건이라도 나오면 빌드가 실패한다.

## 봉인 (Phase 3B)

- 봉인은 확인서 본문을 RFC 8785(JCS)로 정규화한 canonical 문서와, 그것만으로 렌더한 PDF/A-2b를 만든다. 렌더러는 벽시계·로케일·시간대를 읽지 않으며(ArchUnit), 다른 JVM·다른 OS에서도 같은 바이트가 나온다(골든 기대값 + 두 JVM 재렌더 테스트, CI에서 veraPDF 검증).
- 번호는 (테넌트, 봉인 연도)별 무간격 카운터이고, 봉인마다 테넌트 체인 `SHA-256(직전 ‖ canonical ‖ pdf)`이 이어진다. 둘 다 DB 트리거가 강제한다. 봉인 조건(소급 룰·서식, 스냅샷 노후, 막는 검증, 승인 누락, 성명 복호화 불가)은 단락 없이 전부 평가하고, 거부되면 번호·산출물·키가 하나도 생기지 않는다.
- 산출물은 확인서마다 새 데이터 키로 암호화해 S3 호환 저장소에 올리고, DB 커밋 **뒤에** Object Lock(COMPLIANCE)을 건다. 커밋 실패의 잔여물은 `artifacts gc`가, 잠금 실패는 `artifacts reconcile`이 처리한다. 데이터 키를 파기하면 모든 사본이 읽을 수 없게 된다.
- 정정은 새 버전(SUPERSEDE), 취소는 VOID뿐이다. 소급 룰로 봉인이 막힌 초안은 재기준(REBASE)으로 새 룰에 다시 고정되고, 옛 승인은 효력을 잃는다.

## 앵커·검증·파기 (Phase 5)

- **일일 앵커**: 매일 테넌트마다 봉인 체인 머리와 감사 체인 머리를 한 스냅샷에서 기록한다. 그 날짜의 앵커 전부를 잎으로 머클 루트 하나를 만들고 RFC 3161 타임스탬프를 받는다. 테넌트는 자기 잎의 경로와 루트·토큰이 담긴 영수증만 갖는다. 잎과 경로는 DB 트리거가 다시 계산한다.
- **검증**: `verify package`는 DB·저장소 없이 증거 패키지와 (있으면) 영수증만으로 내용·체인 구간·토큰을 확인하는 독립 검증기다(생산자 코드 의존 금지, ArchUnit). 결론은 "이 문서는 {토큰 시각} 이전에 이 내용으로 존재했다"이다. `verify tenant`는 감사·봉인 체인, 채번, 객체, 앵커, 영수증을 한 스냅샷에서 걷는다.
- **파기**: 보존기간이 끝나면 문서 키 파기 → 객체의 모든 버전·마커 삭제 → 묘비 순으로 진행한다. 묘비는 지정 개인정보 컬럼만 NULL이 되고 번호·상태·해시·시각·체인은 남는다. 파기는 전용 롤의 DB 함수만 할 수 있고 감사에는 지운 값의 해시를 남긴다. 지정 컬럼 전수는 설계서의 기계 판독 표가 정본이며 테스트가 DB 함수·카탈로그와 양방향 대조한다. 법적 보류(DB가 통제, 저장소 legal hold는 보조)는 파기를 막는다.
- 보존기간·대기 일수·보류 사유 코드는 룰 데이터다. 데모의 짧은 보존(0년 1일)은 데모 전용 번들과, 데모 프로파일에서만 존재하는 시계 오프셋으로 만든다.

## 플랫폼 아티팩트 소비

`platform-core`·`platform-canonical`·`platform-spring`은 같은 SemVer 버전(현재 `0.1.0`)으로 함께 발행한다(`com.ga.platform`). 소비자(`ga-agent-portal` 등)는 **mavenLocal을 먼저**, 없으면 **GitHub Packages**를 쓴다.

1. mavenLocal: 이 저장소에서 `./gradlew publishToMavenLocal`(또는 `verifyPublishedPlatform`).
2. GitHub Packages: 태그 `platform-vX.Y.Z` push 시 `.github/workflows/publish-platform.yml`이 발행한다. 읽으려면 `read:packages` 권한 토큰이 필요하다(공개 저장소도 동일).

```kotlin
// 소비 측 settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenLocal { content { includeGroup("com.ga.platform") } }
        maven {
            url = uri("https://maven.pkg.github.com/hjryoo-ai/ga-disclosure")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull   // read:packages
            }
            content { includeGroup("com.ga.platform") }
        }
        mavenCentral()
    }
}
```

`./gradlew verifyPublishedPlatformFromGitHub`는 mavenLocal 없이 GitHub Packages만으로 `disclosure-domain`을 컴파일해 발행물을 검증한다(같은 환경변수 필요).

## 모듈

| 모듈 | 역할 | 의존 제약 |
|---|---|---|
| `platform-core` | (공유) `Won`·`Ratio`·`Ym`·`TenantId`·`AgentId`, `TenantContext`(ScopedValue), ArchUnit 규칙 라이브러리 `ArchRules`, 테스트 픽스처 `SeededCases` | Spring·DB 무의존. `com.ga.platform:platform-core:0.1.0` 발행 |
| `platform-canonical` | (공유) RFC 8785 JCS `Canonicalizer`, `Sha256` | Spring·DB 무의존. rules·seal·audit·compliance가 의존. `com.ga.platform:platform-canonical:0.1.0` 발행 |
| `platform-spring` | (공유) `TenantSessionBinder`(트랜잭션마다 `app.tenant_id` 설정), `TenantScopedRepository`·`TenantJdbcGateway`, `TenantDirectoryReader`, `IdentityResolver`, OIDC 골격 | infra·api·app만 의존 가능. `com.ga.platform:platform-spring:0.1.0` 발행 |
| `disclosure-domain` | 값객체·상태 열거형·`GradeSnapshot`/`GradeSnapshotItem`/`RatioLabel`, 개인정보 래퍼 `Sensitive<T>`(Phase 2) | Spring·DB 무의존 |
| `disclosure-rules` | 번들 로더, 기준일 룰 해석기(scope별 단건·Ambiguous fail-fast·`tenantOverridable` 병합), 검증 규칙 12종·단계별 레지스트리(`ValidationStage`), 서식 해석, 마스킹(`MaskedView`), `GradeConsistencyCheck`, 계약 스키마 테스트 | Spring·DB 무의존 |
| `disclosure-workflow` | 유스케이스·포트: 카탈로그 수입·조회, 고객 참조 등록·조회·재암호화(Phase 2), 확인서(Phase 3~4) | |
| `disclosure-seal` | JCS 정규화·해시·체인·채번, 렌더러(`renderer` 하위, Phase 3) | 렌더러 외 Spring·DB 무의존 |
| `disclosure-sign` | 서명 세션·채널·증거(Phase 4) | |
| `disclosure-audit` | 감사 해시체인 append(Phase 1), 앵커·verify(Phase 5) | |
| `disclosure-compliance` | 룰 거버넌스(번들 배포·사규 승인·활성화 배치·번들 대사, Phase 1), 대상 판정·징구율·큐·리포트(Phase 6) | |
| `disclosure-api` | API·DTO 매퍼(Phase 6) | |
| `disclosure-infra` | Flyway(스키마·RLS·불변 트리거·배타 제약), 저장소, 컬럼 암호화(`crypto`, Phase 2), (Phase 3~) 엔진 클라이언트·S3 | app만 의존 가능 |
| `disclosure-app` | Spring Boot 조립, `/actuator/health`, 운영자 CLI(`cli` 프로파일), 아키텍처 테스트(`archTest`) | |
| `disclosure-demo` | 데모 테넌트·사규 시드와 시드 스크립트(Phase 1), 가상 카탈로그 파일(Phase 2), 확인서·엔진 스텁(Phase 8) | 어떤 모듈도 의존하지 않음 |
| `contracts/` | 엔진·내부 OpenAPI, 이벤트 스키마(v1, 포털 §4.1 Envelope), 룰·서식·번들 스키마, 규제 번들, `CHECKSUMS` | |
| `web/` | 프론트(Phase 7) | |

의존 방향: `app → api → workflow/compliance → rules/seal/sign/audit → domain → platform-core` (Gradle 프로젝트 의존 + ArchUnit 이중 강제).
