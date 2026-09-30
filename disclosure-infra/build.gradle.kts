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
    // 포트-어댑터: rules·audit·compliance·workflow가 선언한 포트를 구현한다(설계서 §3.3).
    implementation(project(":disclosure-rules"))
    implementation(project(":disclosure-audit"))
    implementation(project(":disclosure-compliance"))
    implementation(project(":disclosure-workflow"))
    implementation("org.springframework:spring-context")
    // Phase 3A 엔진 클라이언트: 응답을 계약 스키마(contracts/api, OpenAPI YAML)로 수신 즉시 검증한다
    implementation(libs.json.schema.validator)
    implementation(libs.jackson.dataformat.yaml)
    runtimeOnly(libs.postgresql)

    // PostgresHarness: 컨테이너 1회 기동 → init-roles.sql → disclosure_migrator로 Flyway → disclosure_app 데이터소스
    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi(libs.testcontainers.postgresql)
    testFixturesApi(libs.postgresql)
    testFixturesImplementation(project(":disclosure-domain"))
    testFixturesImplementation(project(":disclosure-workflow"))
    testFixturesImplementation(libs.jackson.dataformat.yaml)
    testFixturesImplementation(libs.json.schema.validator)
    testFixturesImplementation(libs.flyway.core)
    testFixturesImplementation(libs.flyway.postgresql)
}

// 엔진 계약(contracts/api)을 클래스패스 ga-contracts/api/에 싣는다 — 클라이언트와 FakeEngine이 같은 스키마로 검증한다.
val contractResources = tasks.register<Sync>("contractResources") {
    from(rootProject.layout.projectDirectory.dir("contracts/api")) {
        into("ga-contracts/api")
    }
    into(layout.buildDirectory.dir("generated/contract-resources"))
}

sourceSets {
    main {
        resources.srcDir(contractResources)
    }
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
                implementation(project(":disclosure-audit"))
                implementation(project(":disclosure-compliance"))
                implementation(project(":disclosure-workflow"))
                implementation(testFixtures(project(":disclosure-rules")))
                implementation(platform(libs.spring.boot.bom))
                implementation(libs.junit.jupiter)
                implementation(libs.assertj.core)
                implementation(libs.spring.jdbc)
                // PlaintextLeakScanIT가 로그 출력(스프링 JDBC 바인드 값 TRACE 포함)을 잡아 평문을 찾는다(Phase 2 P4)
                implementation(libs.logback.classic)
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
