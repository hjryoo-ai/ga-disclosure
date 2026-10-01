// JCS 정규화·해시·체인·채번(Spring·DB 무의존) + 렌더러(HTML→PDF/A, renderer 하위 패키지). Phase 3B.
// 봉인 본문 스키마(contracts/seal)를 클래스패스 ga-contracts/seal/ 아래에 싣는다 — 빌더가 산출물을 즉시 검증한다.
// 서식 결속(rules.template)을 렌더러와 R-FIELD-REQUIRED가 함께 쓰므로 rules에 의존한다(3B 계획 §1, 레이어 규칙 Seal → Rules).
dependencies {
    api(project(":disclosure-domain"))
    api(project(":disclosure-rules"))
    api(project(":platform-canonical"))
    implementation(libs.json.schema.validator)
    // 렌더러(renderer 패키지): openhtmltopdf + PDFBox. 전이 의존 중 Boot BOM이 관리하는 좌표(commons-logging)는 BOM 버전으로 맞춘다.
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.openhtmltopdf.pdfbox)

    testImplementation(testFixtures(project(":platform-core")))
    testImplementation(testFixtures(project(":disclosure-rules")))
}

val contractResources = tasks.register<Sync>("contractResources") {
    from(rootProject.layout.projectDirectory.dir("contracts/seal")) {
        into("ga-contracts/seal")
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

// 3B S1 골든: 기대값 생성은 수동 전용(빌드·CI가 부르지 않는다), 골든 PDF 쓰기는 CI pdfa-verify 잡이 부른다.
val testRuntime = sourceSets.test.map { it.runtimeClasspath }
tasks.register<JavaExec>("regenerateGolden") {
    group = "verification"
    description = "Regenerates golden canonical/PDF expectations (manual only; state the reason in the commit message)."
    classpath(testRuntime)
    mainClass = "com.ga.disclosure.seal.golden.GoldenWriter"
    systemProperty("ga.repoRoot", rootDir.absolutePath)
}
tasks.register<JavaExec>("renderGolden") {
    group = "verification"
    description = "Writes the golden PDFs to build/golden for veraPDF (CI pdfa-verify)."
    classpath(testRuntime)
    mainClass = "com.ga.disclosure.seal.golden.GoldenWriter"
    systemProperty("ga.repoRoot", rootDir.absolutePath)
    args("render", layout.buildDirectory.dir("golden").get().asFile.absolutePath)
}
