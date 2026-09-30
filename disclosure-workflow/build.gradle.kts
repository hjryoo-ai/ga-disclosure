// 유스케이스(작성·구성·산출·봉인·서명·완료·정정·무효)와 포트 인터페이스.
// Phase 2: 카탈로그 수입·조회 포트, 고객 참조 등록·조회·키 순환. 계약 스키마(contracts/catalog)를 클래스패스 ga-contracts/catalog/에 싣는다.
plugins {
    `java-test-fixtures`
}

dependencies {
    api(project(":disclosure-rules"))
    api(project(":disclosure-seal"))
    api(project(":disclosure-sign"))
    api(project(":disclosure-audit"))
    api(project(":platform-canonical"))
    implementation(libs.json.schema.validator)

    testFixturesApi(project(":disclosure-domain"))
    testFixturesApi(project(":disclosure-audit"))

    testImplementation(testFixtures(project(":platform-core")))
}

val contractResources = tasks.register<Sync>("contractResources") {
    from(rootProject.layout.projectDirectory.dir("contracts/catalog")) {
        into("ga-contracts/catalog")
    }
    into(layout.buildDirectory.dir("generated/contract-resources"))
}

sourceSets {
    main {
        resources.srcDir(contractResources)
    }
}

tasks.test {
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts")).withPathSensitivity(PathSensitivity.RELATIVE)
}
