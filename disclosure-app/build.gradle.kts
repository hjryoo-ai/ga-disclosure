// Spring Boot 조립(진입점·설정 배선·CLI + /actuator/health). 컨트롤러는 disclosure-api(6A), 업무 로직은 disclosure-workflow.
// archTest: 전 모듈 아키텍처 규칙(ArchUnit) + disclosure-infra SQL 테넌트 조건 스캔.
// integrationTest: Testcontainers PostgreSQL로 부팅 스모크(Flyway는 disclosure_migrator, 데이터소스는 disclosure_app)·CLI·HTTP(6A).
plugins {
    alias(libs.plugins.spring.boot)
    `jvm-test-suite`
}

val allModules = listOf(
    ":platform-core", ":platform-spring", ":platform-canonical",
    ":disclosure-domain", ":disclosure-rules", ":disclosure-workflow", ":disclosure-seal", ":disclosure-sign",
    ":disclosure-audit", ":disclosure-compliance", ":disclosure-api", ":disclosure-infra", ":disclosure-demo",
)

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":disclosure-api"))
    implementation(project(":disclosure-infra"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.security.oauth2.resource.server)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
    implementation(project(":disclosure-rules"))
    implementation(project(":disclosure-audit"))
    implementation(project(":disclosure-compliance"))
    implementation(project(":disclosure-workflow"))
    implementation(project(":platform-spring"))
    // Phase 7: 화면 산출물(classpath:/ga-web/) — 데모 프로파일에서만 서빙한다(DemoWebController). 운영 분리는 Phase 8
    runtimeOnly(project(":disclosure-web"))
    runtimeOnly(libs.flyway.postgresql)
    runtimeOnly(libs.postgresql)
}

testing {
    suites {
        register<JvmTestSuite>("archTest") {
            dependencies {
                implementation(project())
                allModules.forEach { implementation(project(it)) }
                // archunit의 전이 의존(slf4j-api)까지 Boot BOM 버전으로 고정한다.
                implementation(enforcedPlatform(libs.spring.boot.bom))
                implementation(libs.archunit)
                implementation(libs.junit.jupiter)
                implementation(libs.assertj.core)
                runtimeOnly(libs.junit.platform.launcher)
            }
            targets.all {
                testTask.configure {
                    inputs.dir(rootProject.layout.projectDirectory.dir("disclosure-infra/src/main"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                    // 6B FlagTypeTableTest: 설계서 flag-types 블록 ↔ 모든 룰 번들 ↔ 마이그레이션 CHECK ↔ 코드 상수
                    inputs.file(rootProject.layout.projectDirectory.file("docs/설계서.md")).withPathSensitivity(PathSensitivity.RELATIVE)
                    inputs.dir(rootProject.layout.projectDirectory.dir("contracts/rules/bundles")).withPathSensitivity(PathSensitivity.RELATIVE)
                    inputs.dir(rootProject.layout.projectDirectory.dir("disclosure-demo/src/main/resources/demo/bundles"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                    inputs.dir(rootProject.layout.projectDirectory.dir("disclosure-infra/src/integrationTest/resources/rule-as-data"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                }
            }
        }
        register<JvmTestSuite>("integrationTest") {
            dependencies {
                implementation(project())
                implementation(testFixtures(project(":disclosure-infra")))
                // 6A: 작업 잠금을 직접 쥐어 CLI의 "그 테넌트만 실패"를 본다(JobLockGateway·JobKind)
                implementation(project(":disclosure-infra"))
                implementation(project(":disclosure-workflow"))
                implementation(project(":platform-spring"))
                // 6A: 시험용 JWT 서명(Nimbus — oauth2-jose의 전이 의존, BOM 정렬)
                implementation(libs.spring.security.oauth2.jose)
                // 6A: 바인딩 순서 주입(TenantBindingOrderIT — 서블릿 필터를 시험 구성으로 끼운다)
                implementation(project(":disclosure-api"))
                implementation(libs.spring.boot.starter.webmvc)
                // Phase 7 G7: 미리보기 PDF의 쪽 텍스트(렌더러와 같은 좌표 openhtmltopdf-pdfbox — PDFBox)
                implementation(libs.openhtmltopdf.pdfbox)
                // 6A: 응답마다 OpenAPI 계약 스키마 검증(ApiContracts — 계약 정본 YAML을 그대로 읽는다)
                implementation(libs.json.schema.validator)
                implementation(libs.jackson.dataformat.yaml)
                implementation(project(":disclosure-domain"))
                implementation(libs.jackson.databind)
                implementation(platform(libs.spring.boot.bom))
                implementation(libs.spring.boot.starter.test)
                implementation(libs.junit.jupiter)
                implementation(libs.assertj.core)
                runtimeOnly(libs.junit.platform.launcher)
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.test)
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("archTest"), testing.suites.named("integrationTest"))
}

// 운영자 CLI(bootRun --args="--spring.profiles.active=cli ...")의 상대 경로(contracts/rules/bundles 등)는 저장소 루트 기준이다.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}

// HTTP 데모(disclosure-demo/scripts/http-demo.sh, 6A): 웹 앱을 부트 jar로 백그라운드에 띄운다(bootRun은 Gradle 데몬 아래라 스크립트가 끝낼 PID가 없다).
// jar는 툴체인 JDK로 컴파일되므로 그 실행 파일 경로를 알려 준다(PATH의 java가 더 낮을 수 있다).
tasks.register("demoJavaLauncher") {
    description = "Prints the toolchain java executable that runs the boot jar (http-demo.sh)."
    val launcher = javaToolchains.launcherFor(java.toolchain)
    doLast {
        println(launcher.get().executablePath.asFile.absolutePath)
    }
}
