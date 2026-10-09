// Phase 7 화면: 직원 화면(React)·고객 서명 화면(프레임워크 없음)의 Node 빌드를 Gradle로 감싼다(CI 한 번에).
// 산출물(Vite dist)은 이 모듈 jar의 classpath:/ga-web/ 아래로 들어가고, 데모 프로파일의 앱이 같은 출처로 서빙한다(운영 분리는 Phase 8).
// 모든 태스크는 원천·계약·락 파일을 입력으로 선언한다(엔진 E3.2 J2 — 계약만 바뀌어도 생성·시험이 다시 돈다).
import com.github.gradle.node.npm.task.NpmTask

plugins {
    alias(libs.plugins.node.gradle)
}

node {
    version = libs.versions.nodejs.get()
    download = true
    distBaseUrl = null // 저장소는 settings.gradle.kts(FAIL_ON_PROJECT_REPOS)
    npmInstallCommand = "ci"
}

val contracts = rootProject.layout.projectDirectory.dir("contracts/api/v1")
val templateBundles = listOf(
    rootProject.layout.projectDirectory.dir("contracts/rules/bundles/templates"),
    rootProject.layout.projectDirectory.dir("disclosure-infra/src/integrationTest/resources/rule-as-data/templates"),
)
val sealFonts = rootProject.layout.projectDirectory.dir("disclosure-seal/src/main/resources/render/fonts")
val webSources = fileTree("src") { exclude("gen/**") }
val webConfig = files("package.json", "package-lock.json", "tsconfig.json", "vite.config.ts", "eslint.config.js", "contracts-client.lock.json")

fun NpmTask.webInputs() {
    dependsOn(tasks.named("npmInstall"))
    inputs.files(webConfig).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(webSources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("scripts").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(contracts).withPathSensitivity(PathSensitivity.RELATIVE)
}

// 계약 → 타입(src/gen, git 무시). 잠금 파일과 대조한다: 계약·생성기가 같은데 생성물이 다르면 실패, 계약이 바뀌었는데 잠금이 그대로면 실패.
val clientCheck = tasks.register<NpmTask>("clientCheck") {
    description = "Generates the API client types from the contracts and checks them against contracts-client.lock.json."
    webInputs()
    args = listOf("run", "client:check")
    outputs.dir("src/gen")
}

// 계약을 바꾼 커밋에서만 손으로 돌린다(잠금 갱신이 그 커밋에 드러난다).
tasks.register<NpmTask>("clientLock") {
    description = "Regenerates the API client types and rewrites contracts-client.lock.json."
    webInputs()
    args = listOf("run", "client:lock")
    outputs.upToDateWhen { false }
}

val webTypecheck = tasks.register<NpmTask>("webTypecheck") {
    description = "tsc --noEmit (strict)."
    webInputs()
    dependsOn(clientCheck)
    args = listOf("run", "typecheck")
    outputs.file(layout.buildDirectory.file("web/typecheck.ok"))
    doLast { layout.buildDirectory.file("web/typecheck.ok").get().asFile.writeText("ok\n") }
}

val webLint = tasks.register<NpmTask>("webLint") {
    description = "ESLint (no any, no hand-written fetch, no browser storage)."
    webInputs()
    dependsOn(clientCheck)
    args = listOf("run", "lint")
    outputs.file(layout.buildDirectory.file("web/lint.ok"))
    doLast { layout.buildDirectory.file("web/lint.ok").get().asFile.writeText("ok\n") }
}

val webTest = tasks.register<NpmTask>("webTest") {
    description = "Vitest unit, component, contract and scan tests."
    webInputs()
    dependsOn(clientCheck)
    inputs.files(templateBundles).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(sealFonts).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("docs/설계서.md")).withPathSensitivity(PathSensitivity.RELATIVE)
    args = listOf("run", "test")
    outputs.dir(layout.buildDirectory.dir("reports/vitest"))
}

val webLicenses = tasks.register<NpmTask>("webLicenses") {
    description = "Checks every installed package's license against the allow list and THIRD-PARTY.md."
    webInputs()
    inputs.file("THIRD-PARTY.md").withPathSensitivity(PathSensitivity.RELATIVE)
    args = listOf("run", "licenses")
    outputs.file(layout.buildDirectory.file("web/licenses.ok"))
    doLast { layout.buildDirectory.file("web/licenses.ok").get().asFile.writeText("ok\n") }
}

val webBuild = tasks.register<NpmTask>("webBuild") {
    description = "Vite build of the staff and public sign bundles (build/web/dist), then the output scan."
    webInputs()
    dependsOn(clientCheck)
    inputs.dir(sealFonts).withPathSensitivity(PathSensitivity.RELATIVE)
    args = listOf("run", "build")
    outputs.dir(layout.buildDirectory.dir("web/dist"))
}

tasks.named<ProcessResources>("processResources") {
    from(webBuild) { into("ga-web") }
}

tasks.named("check") {
    dependsOn(webTypecheck, webLint, webTest, webLicenses)
}
