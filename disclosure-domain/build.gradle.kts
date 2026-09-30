// 애그리게이트·상태 열거형·값객체. Spring·DB 무의존(루트 verifySpringFree + ArchUnit).
dependencies {
    api(project(":platform-core"))

    testImplementation(testFixtures(project(":platform-core")))
}

// 3A W1: DisclosureStateTableTest가 설계서 §6.1의 state-table 블록(정본)을 파싱한다 — 문서가 바뀌면 테스트를 다시 돌린다.
tasks.test {
    inputs.file(rootProject.layout.projectDirectory.file("docs/설계서.md")).withPathSensitivity(PathSensitivity.RELATIVE)
}
