// 감사 체인·앵커(머클)·TSA·verify 순수 계산(Phase 5). Spring·DB 무의존 — 배치 조정은 disclosure-workflow(5 계획 승인 Q1).
dependencies {
    api(project(":disclosure-domain"))
    api(project(":platform-canonical"))
    // RFC 3161 토큰 생성(스텁)·검증. 참조는 com.ga.disclosure.audit.tsa.. 안에서만(ArchitectureRulesTest) — 포트·결과 타입에 BC 타입이 없어 implementation
    implementation(libs.bcpkix)
    implementation(libs.bcprov)
    implementation(libs.bcutil)

    testImplementation(testFixtures(project(":platform-core")))
}

// MerkleSpecTableTest가 설계서 §6.7의 merkle-spec 블록(정본)을, BouncyCastlePinTest가 카탈로그·락을 읽는다 — 바뀌면 다시 돌린다.
tasks.test {
    inputs.file(rootProject.layout.projectDirectory.file("docs/설계서.md")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("gradle/libs.versions.toml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("gradle.lockfile")).withPathSensitivity(PathSensitivity.RELATIVE)
}
