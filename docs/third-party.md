# 서드파티 목록

버전 카탈로그(`gradle/libs.versions.toml`)에서 직접 고정하는 라이브러리, 즉 **Spring Boot BOM 밖** 라이브러리와 그 주요 전이 의존의 라이선스이다. Boot BOM이 관리하는 라이브러리(Spring·Flyway·JUnit·Testcontainers·AssertJ·Jackson·PostgreSQL 드라이버 등)는 BOM 버전을 그대로 쓰며 여기 적지 않는다. 실제로 해석된 버전의 정본은 모듈별 `gradle.lockfile`이다.

라이선스 출처는 각 아티팩트의 POM `<licenses>`(없으면 부모 POM) 또는 jar 매니페스트 `Bundle-License`이다.

| 좌표 | 버전 | 라이선스 | 쓰는 곳 | 출처 |
|---|---|---|---|---|
| `org.bouncycastle:bcpkix-jdk18on`·`bcprov-jdk18on`·`bcutil-jdk18on` | 1.86 | Bouncy Castle Licence(MIT 계열) | `disclosure-audit`의 `..audit.tsa..`만(ArchUnit) — RFC 3161 토큰 생성(스텁)·검증 | POM |
| `com.tngtech.archunit:archunit` | 1.5.1 | Apache-2.0 | `disclosure-app` archTest | POM |
| `com.networknt:json-schema-validator` | 3.0.6 | Apache-2.0 | 룰 데이터·계약 스키마 검증 | 매니페스트 |
| `io.github.erdtman:java-json-canonicalization` | 1.1 | Apache-2.0 | `platform-canonical`(RFC 8785 JCS) | POM |
| `software.amazon.awssdk:s3`·`url-connection-client`(BOM 2.55.9) | 2.55.9 | Apache-2.0 | `disclosure-infra` 산출물 저장소(S3 API) | 부모 POM |
| `io.github.openhtmltopdf:openhtmltopdf-pdfbox`·`-core` | 1.1.87 | LGPL-2.1 이상 | `disclosure-seal` 렌더러(HTML→PDF/A) | POM |
| ↳ `org.apache.pdfbox:pdfbox`·`fontbox`·`pdfbox-io`·`xmpbox`(전이) | 3.0.7 | Apache-2.0 | 위 렌더러 | 매니페스트 |
| ↳ `de.rototor.pdfbox:graphics2d`(전이) | 3.0.1 | Apache-2.0 | 위 렌더러 | 부모 POM |

## BouncyCastle 1.86 (Phase 5)

4 수용심사 승인 ①에 따라 추가했다. Boot BOM 비관리이다(`spring-boot-dependencies-4.1.1.pom`에 `bouncycastle` 0건).

**최신판 확인(2026-10-03).** Maven Central `org/bouncycastle/bcpkix-jdk18on/maven-metadata.xml`의 `<release>`는 1.86이고 `lastUpdated`는 20260911045750이다.

**OSV(`api.osv.dev/v1/query`, ecosystem Maven) 조회 결과(2026-10-03).** 질의가 실제로 동작하는지 보려고 1.77도 함께 조회했다.

| 아티팩트 | 1.86 | 대조: 1.77 |
|---|---|---|
| `bcpkix-jdk18on` | 0 | 2 |
| `bcprov-jdk18on` | 0 | 9 |
| `bcutil-jdk18on` | 0 | 0 |

**사용 범위.**
- `org.bouncycastle..` 참조는 운영 코드 중 `com.ga.disclosure.audit.tsa`와 `com.ga.disclosure.audit.tsa.stub`에서만 허용된다. 테스트 코드는 이 검사의 대상이 아니다. 근거는 `ArchitectureRulesTest.bouncyCastleOnlyInTsaPackages`이다.
- BC 보안 제공자는 전역 등록하지 않는다. 서명 검증과 PKIX 경로 검증은 JCA 기본 제공자로 한다.
- 고정값은 `BouncyCastlePinTest`가 카탈로그·락·클래스패스 jar 세 곳에서 대조한다.
