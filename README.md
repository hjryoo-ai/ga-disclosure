# ga-disclosure

대형 GA(소속 설계사 500인 이상) 설계사가 계약 체결 전에 **동종·유사상품 3개 이상 비교표 → 판매수수료 등급·순위 표기 → 추천사유 → 고객·설계사·관리자 확인(서명) → 불변 보관·감사**를 한 흐름으로 끝내는 멀티테넌트 워크플로 서비스다. 등급·순위의 산출은 `ga-commission-engine`이 하고 이 저장소는 그 결과를 스냅샷으로 받아 검증·표시·보관만 하며, 봉인된 확인서는 불변이고 서명은 문서 해시에 귀속된다. 정본 설계는 [`docs/설계서.md`](docs/설계서.md), 작업 규약은 [`CLAUDE.md`](CLAUDE.md), 진행 단위는 `docs/phase-NN-지시문.md`다.

## 면책

학습·포트폴리오 목적 저장소. 규제 내용(비교·설명 의무, 등급 임계치, 서식 항목, 서명 요건)은 금융위원회·보험GA협회 공개 자료 기반의 예시적 정리이며, 실제 적용 기준은 「보험업감독규정」·시행세칙·협회 표준서식·유권해석 원문을 따른다. 확인서 번호 체계·기한·보존기간 등 수치는 가상의 예시값이다. README와 화면 어디에도 "법령 요건 충족"을 시스템이 단언하는 문구를 두지 않는다.

## 실행

요구사항: **Docker**(통합 테스트가 Testcontainers로 PostgreSQL 18을 띄운다 — Docker가 없으면 통합 테스트는 스킵되지 않고 **실패**한다). JDK는 Gradle 툴체인이 Java 25를 자동으로 받는다(실행용 JDK는 17 이상이면 된다).

```bash
./gradlew build                     # 단위 + 아키텍처(archTest) + 통합(integrationTest) + 계약 체크섬 검사
./gradlew verifyPublishedPlatform   # platform-core·platform-spring을 mavenLocal에 발행하고 발행물만으로 disclosure-domain 컴파일
./gradlew contractChecksums         # contracts/ 변경 후 contracts/CHECKSUMS 갱신
./gradlew resolveAndLockAll --write-locks   # 의존성 추가 후 락 파일 갱신
docker compose up -d postgres       # 로컬 DB(롤 초기화 포함) — 앱은 disclosure_app, Flyway는 disclosure_migrator로 접속
```

## 모듈

| 모듈 | 역할 | 의존 제약 |
|---|---|---|
| `platform-core` | (공유) `Won`·`Ratio`·`Ym`·`TenantId`·`AgentId`, `TenantContext`(ScopedValue), ArchUnit 규칙 라이브러리 `ArchRules`, 테스트 픽스처 `SeededCases` | Spring·DB 무의존. `com.ga.platform:platform-core:0.1.0` 발행 |
| `platform-spring` | (공유) `TenantSessionBinder`(트랜잭션마다 `app.tenant_id` 설정), `TenantScopedRepository`, `IdentityResolver`, OIDC 골격 | infra·api·app만 의존 가능. `com.ga.platform:platform-spring:0.1.0` 발행 |
| `disclosure-domain` | 값객체·상태 열거형·`GradeSnapshotItem`/`RatioLabel` | Spring·DB 무의존 |
| `disclosure-rules` | 룰 버전 해석·검증 룰 실행기·서식 모델(Phase 1), `GradeConsistencyCheck`(Phase 3), 계약 스키마 테스트 | Spring·DB 무의존 |
| `disclosure-workflow` | 유스케이스·포트(Phase 3~4) | |
| `disclosure-seal` | JCS 정규화·해시·체인·채번, 렌더러(`renderer` 하위, Phase 3) | 렌더러 외 Spring·DB 무의존 |
| `disclosure-sign` | 서명 세션·채널·증거(Phase 4) | |
| `disclosure-audit` | 감사 체인·앵커·verify(Phase 5) | |
| `disclosure-compliance` | 대상 판정·징구율·큐·리포트(Phase 6) | |
| `disclosure-api` | API·DTO 매퍼(Phase 6) | |
| `disclosure-infra` | Flyway(스키마·RLS·불변 트리거·배타 제약), 저장소, (Phase 3~) 엔진 클라이언트·S3 | app만 의존 가능 |
| `disclosure-app` | Spring Boot 조립, `/actuator/health`, 아키텍처 테스트(`archTest`) | |
| `disclosure-demo` | 데모 시드·엔진 스텁(Phase 8) | |
| `contracts/` | 엔진·내부 OpenAPI, 이벤트 스키마(v1), 룰·서식 스키마와 샘플, `CHECKSUMS` | |
| `web/` | 프론트(Phase 7) | |

의존 방향: `app → api → workflow/compliance → rules/seal/sign/audit → domain → platform-core` (Gradle 프로젝트 의존 + ArchUnit 이중 강제).
