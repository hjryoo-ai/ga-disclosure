// 룰 버전 해석기·검증 룰 실행기·서식 템플릿 모델(Phase 1). Spring·DB 무의존.
// Phase 0: GradeConsistencyCheck 자리 + contracts/ 스키마 검증 테스트(ContractSchemaTest).
dependencies {
    api(project(":disclosure-domain"))

    testImplementation(libs.json.schema.validator)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.dataformat.yaml)
}

tasks.test {
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts")).withPathSensitivity(PathSensitivity.RELATIVE)
}
