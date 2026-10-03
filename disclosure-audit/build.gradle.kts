// 감사 체인·앵커(머클)·TSA·verify 순수 계산(Phase 5). Spring·DB 무의존 — 배치 조정은 disclosure-workflow(5 계획 승인 Q1).
dependencies {
    api(project(":disclosure-domain"))
    api(project(":platform-canonical"))
    // RFC 3161 토큰 생성(스텁)·검증. 참조는 com.ga.disclosure.audit.tsa.. 안에서만(ArchitectureRulesTest) — 포트·결과 타입에 BC 타입이 없어 implementation
    implementation(libs.bcpkix)
    implementation(libs.bcprov)
    implementation(libs.bcutil)
    // verify package: 증거 매니페스트·영수증 내보내기·보고서 스키마 검증(생산자 코드인 seal과 독립 — 같은 계약 파일을 직접 싣는다)
    implementation(libs.json.schema.validator)

    testImplementation(testFixtures(project(":platform-core")))
    // VerifyPackageTest: 생산자(seal)의 실제 빌더로 만든 패키지를 검증한다 — main은 seal에 의존하지 않는다(레이어 규칙·ArchUnit)
    testImplementation(project(":disclosure-seal"))
}

// 계약 스키마(contracts/seal·verify)를 클래스패스 ga-contracts/ 아래에 싣는다 — verify package가 생산자(seal)를 거치지 않고 직접 검증한다.
val contractResources = tasks.register<Sync>("contractResources") {
    from(rootProject.layout.projectDirectory.dir("contracts")) {
        include("seal/**", "verify/**")
        into("ga-contracts")
    }
    into(layout.buildDirectory.dir("generated/contract-resources"))
}

sourceSets {
    main {
        resources.srcDir(contractResources)
    }
}

// MerkleSpecTableTest가 설계서 §6.7의 merkle-spec 블록(정본)을, BouncyCastlePinTest가 카탈로그·락을 읽는다 — 바뀌면 다시 돌린다.
tasks.test {
    inputs.file(rootProject.layout.projectDirectory.file("docs/설계서.md")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("gradle/libs.versions.toml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("gradle.lockfile")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts")).withPathSensitivity(PathSensitivity.RELATIVE)
}
