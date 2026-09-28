// 유스케이스(작성·구성·산출·봉인·서명·완료·정정·무효)와 포트 인터페이스. 구현은 Phase 3~4.
dependencies {
    api(project(":disclosure-rules"))
    api(project(":disclosure-seal"))
    api(project(":disclosure-sign"))
    api(project(":disclosure-audit"))
}
