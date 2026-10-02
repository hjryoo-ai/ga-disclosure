// 서명 순수 규칙(Phase 4): 세션 상태표·토큰·본인확인 정책·대리 서명 탐지·게이트·보존 앵커·서명 기한. Spring·DB·환경 무의존.
// 난수는 포트(TokenSource), 시각은 인자로 받는다. 렌더·패키징은 disclosure-seal(설계서 §3.3 v1.9).
dependencies {
    api(project(":disclosure-domain"))

    testImplementation(testFixtures(project(":platform-core")))
}

// SessionStateTableTest가 설계서 §6.5의 session-state-table 블록(정본)을 파싱한다 — 문서가 바뀌면 테스트를 다시 돌린다.
tasks.test {
    inputs.file(rootProject.layout.projectDirectory.file("docs/설계서.md")).withPathSensitivity(PathSensitivity.RELATIVE)
}
