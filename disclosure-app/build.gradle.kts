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
