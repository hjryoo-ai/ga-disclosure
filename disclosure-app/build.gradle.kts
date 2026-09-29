// Spring Boot 조립(진입점 + /actuator/health). 컨트롤러·비즈니스 로직 없음(Phase 0).
// archTest: 전 모듈 아키텍처 규칙(ArchUnit) + disclosure-infra SQL 테넌트 조건 스캔.
// integrationTest: Testcontainers PostgreSQL로 부팅 스모크(Flyway는 disclosure_migrator, 데이터소스는 disclosure_app).
plugins {
    alias(libs.plugins.spring.boot)
    `jvm-test-suite`
}

val allModules = listOf(
    ":platform-core", ":platform-spring",
    ":disclosure-domain", ":disclosure-rules", ":disclosure-workflow", ":disclosure-seal", ":disclosure-sign",
    ":disclosure-audit", ":disclosure-compliance", ":disclosure-api", ":disclosure-infra", ":disclosure-demo",
)

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":disclosure-api"))
    implementation(project(":disclosure-infra"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.flyway)
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
                implementation(project(":platform-spring"))
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
