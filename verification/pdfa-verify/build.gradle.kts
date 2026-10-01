// 3B S12: 골든 PDF(./gradlew :disclosure-seal:renderGolden → disclosure-seal/build/golden)를 veraPDF PDF/A-2b 프로파일로 검증한다.
// 실패 규칙이 하나라도 있으면 종료 코드 1 — CI 잡 pdfa-verify가 이 빌드를 돌린다. 버전은 확인 시점 최신판으로 고정(2026-10-01).
plugins { application }
repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
dependencies {
    implementation("org.verapdf:validation-model:1.30.2")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}
application { mainClass = "PdfaVerify" }
tasks.named<JavaExec>("run") { workingDir = projectDir }
