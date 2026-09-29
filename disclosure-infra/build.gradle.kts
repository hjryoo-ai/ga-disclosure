// PostgreSQL(Spring Data JDBC)·Flyway 마이그레이션·RLS, (Phase 3~) S3·엔진 클라이언트·스텁 어댑터.
// 통합 테스트(integrationTest)는 Testcontainers PostgreSQL. Docker가 없으면 실패한다(스킵 금지).
plugins {
    `java-test-fixtures`
    `jvm-test-suite`
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":platform-spring"))
    implementation(project(":disclosure-domain"))
    // 포트-어댑터: rules·audit·compliance가 선언한 포트를 구현한다(설계서 §3.3).
    implementation(project(":disclosure-rules"))
    implementation("org.springframework:spring-context")
    runtimeOnly(libs.postgresql)

    // PostgresHarness: 컨테이너 1회 기동 → init-roles.sql → disclosure_migrator로 Flyway → disclosure_app 데이터소스
    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.postgresql)
    testFixturesImplementation(libs.flyway.core)
    testFixturesImplementation(libs.flyway.postgresql)
}

testing {
    suites {
        register<JvmTestSuite>("integrationTest") {
            dependencies {
                implementation(project())
                implementation(testFixtures(project()))
                implementation(project(":platform-spring"))
                implementation(project(":disclosure-domain"))
                implementation(project(":disclosure-rules"))
                implementation(platform(libs.spring.boot.bom))
                implementation(libs.junit.jupiter)
                implementation(libs.assertj.core)
                implementation(libs.spring.jdbc)
                runtimeOnly(libs.junit.platform.launcher)
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.test)
                    // 같은 JVM에서 컨테이너를 재사용한다(PostgresHarness 싱글턴).
                    maxParallelForks = 1
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
}
