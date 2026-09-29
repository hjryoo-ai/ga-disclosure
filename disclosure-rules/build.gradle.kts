// 룰 번들·기준일 해석기·검증 룰 실행기·서식 템플릿 해석(Phase 1). Spring·DB 무의존.
// 계약 스키마(contracts/rules)를 클래스패스 ga-contracts/rules/ 아래에 싣는다 — 번들 로더와 사규 승인이 런타임에 검증한다.
plugins {
    `java-test-fixtures`
}

dependencies {
    api(project(":disclosure-domain"))
    api(project(":platform-canonical"))
    implementation(libs.json.schema.validator)

    // ValidationSubject 픽스처(Phase 3 애그리게이트 전까지 구현체는 테스트 픽스처에만 둔다)
    testFixturesApi(project(":disclosure-domain"))

    testImplementation(testFixtures(project(":platform-core")))
    testImplementation(libs.json.schema.validator)
    testImplementation(libs.jackson.dataformat.yaml)
}

val contractResources = tasks.register<Sync>("contractResources") {
    from(rootProject.layout.projectDirectory.dir("contracts/rules")) {
        into("ga-contracts/rules")
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
