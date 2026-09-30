// PDF/A 변환기 후보 비교(3A 선행 소과제 D) — 메인 빌드에 포함하지 않는 독립 검증 빌드. 폰트·ICC는 저장소 동봉 자산
// (disclosure-seal/src/main/resources/render)만 쓰고 빌드 시 내려받지 않는다. 버전은 실측 시점 값으로 고정한다.
plugins { application }
repositories { mavenCentral() }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
dependencies {
    implementation("io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.87")   // LGPL-2.1 + Apache-2.0(PDFBox 3.0.7)
    implementation("org.xhtmlrenderer:flying-saucer-pdf:10.5.0")            // LGPL-2.1 / OpenPDF MPL-2.0
    implementation("org.verapdf:validation-model:1.30.2")                  // 준수 검증(PDF/A-2b 프로파일)
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
}
application { mainClass = "Cmp" }
tasks.named<JavaExec>("run") { workingDir = projectDir }
