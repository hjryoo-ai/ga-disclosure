import java.net.URI
import java.security.MessageDigest

// Spring Boot 조립(진입점·설정 배선·CLI + /actuator/health). 컨트롤러는 disclosure-api(6A), 업무 로직은 disclosure-workflow.
// archTest: 전 모듈 아키텍처 규칙(ArchUnit) + disclosure-infra SQL 테넌트 조건 스캔.
// integrationTest: Testcontainers PostgreSQL로 부팅 스모크(스키마는 하네스가 disclosure_migrator로, 데이터소스는 disclosure_app — 앱은 기동 때 마이그레이션하지 않는다)·CLI·HTTP(6A).
plugins {
    alias(libs.plugins.spring.boot)
    `jvm-test-suite`
}

val allModules = listOf(
    ":platform-core", ":platform-spring", ":platform-canonical",
    ":disclosure-domain", ":disclosure-rules", ":disclosure-workflow", ":disclosure-seal", ":disclosure-sign",
    ":disclosure-audit", ":disclosure-compliance", ":disclosure-api", ":disclosure-infra", ":disclosure-demo",
)

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":disclosure-api"))
    implementation(project(":disclosure-infra"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.security.oauth2.resource.server)
    implementation(libs.spring.boot.starter.actuator)
    // Phase 8 G8: 운영 미터(라벨 키 닫힌 목록)를 관리 포트에서 Prometheus 형식으로
    runtimeOnly(libs.micrometer.registry.prometheus)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(project(":disclosure-rules"))
    implementation(project(":disclosure-audit"))
    implementation(project(":disclosure-compliance"))
    implementation(project(":disclosure-workflow"))
    implementation(project(":platform-spring"))
    // Phase 7: 화면 산출물(classpath:/ga-web/) — 데모 프로파일에서만 서빙한다(DemoWebController). 운영 분리는 Phase 8
    runtimeOnly(project(":disclosure-web"))
    runtimeOnly(libs.postgresql)
}

// Phase 8 ③: 이미지 2개(app·web). 빌드 문맥은 저장소 루트가 아니라 필요한 파일만 모은 디렉터리다(문맥에 비밀·node_modules가 실릴 길이 없다).
val appImageName = "ga-disclosure/app:dev"
val webImageName = "ga-disclosure/web:dev"
val appImageContext = tasks.register<Sync>("appImageContext") {
    from(tasks.named("bootJar")) { rename { "app.jar" } }
    into(layout.buildDirectory.dir("image/app"))
}
val webImageContext = tasks.register<Sync>("webImageContext") {
    dependsOn(":disclosure-web:webBuild")
    from(rootProject.layout.projectDirectory.dir("disclosure-web/build/web/dist")) { into("dist") }
    from(rootProject.layout.projectDirectory.file("deploy/images/web/nginx.conf"))
    into(layout.buildDirectory.dir("image/web"))
}
tasks.register<Exec>("appImage") {
    group = "build"
    description = "Builds the app image (boot jar layers, non-root) from a staged context."
    dependsOn(appImageContext)
    commandLine("docker", "build", "--quiet", "-t", appImageName, "-f", rootProject.file("deploy/images/app.Dockerfile").absolutePath,
        layout.buildDirectory.dir("image/app").get().asFile.absolutePath)
}
tasks.register<Exec>("webImage") {
    group = "build"
    description = "Builds the web image (Vite dist on non-root nginx) from a staged context."
    dependsOn(webImageContext)
    commandLine("docker", "build", "--quiet", "-t", webImageName, "-f", rootProject.file("deploy/images/web.Dockerfile").absolutePath,
        layout.buildDirectory.dir("image/web").get().asFile.absolutePath)
}

// Phase 8 ③: 배포 도구(kind·kubeconform·kubectl) — deploy/tools.lock에서 이 기계 플랫폼 줄만 받아 SHA-256을 대조한 뒤 build/tools에 둔다(전역 설치·brew
// 없음). 값이 다르면 받은 파일을 지우고 실패한다. 압축(.tar.gz)은 도구 이름의 실행 파일만 꺼낸다.
val toolsDir = rootProject.layout.buildDirectory.dir("tools")
val deployTools = tasks.register("deployTools") {
    group = "deploy"
    description = "Downloads the pinned deploy tools for this platform and verifies their SHA-256 (deploy/tools.lock)."
    val lock = rootProject.file("deploy/tools.lock")
    inputs.file(lock)
    outputs.dir(toolsDir)
    doLast {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch")
        val platform = (if (os.contains("mac")) "darwin" else if (os.contains("linux")) "linux" else throw GradleException("unsupported OS $os")) + "-" +
            (when (arch) { "aarch64", "arm64" -> "arm64"; "amd64", "x86_64" -> "amd64"; else -> throw GradleException("unsupported arch $arch") })
        val dir = toolsDir.get().asFile
        dir.mkdirs()
        val rows = lock.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.trim().split(Regex("\\s+")) }.filter { it[2] == platform }
        if (rows.map { it[0] }.toSet() != setOf("kind", "kubeconform", "kubectl")) {
            throw GradleException("deploy/tools.lock has no complete tool set for $platform")
        }
        for ((name, _, _, url, sha) in rows) {
            val download = File(temporaryDir, url.substringAfterLast('/'))
            URI(url).toURL().openStream().use { input -> download.outputStream().use { input.copyTo(it) } }
            val actual = MessageDigest.getInstance("SHA-256").digest(download.readBytes()).joinToString("") { "%02x".format(it) }
            if (actual != sha) {
                download.delete()
                throw GradleException("$name: SHA-256 differs from deploy/tools.lock")
            }
            val target = File(dir, name)
            if (url.endsWith(".tar.gz")) {
                copy { from(tarTree(resources.gzip(download))) { include(name) }; into(temporaryDir) }
                File(temporaryDir, name).copyTo(target, overwrite = true)
            } else {
                download.copyTo(target, overwrite = true)
            }
            target.setExecutable(true, true)
            download.delete()
        }
    }
}

testing {
    suites {
        register<JvmTestSuite>("archTest") {
            dependencies {
                implementation(project())
                allModules.forEach { implementation(project(it)) }
                // archunit의 전이 의존(slf4j-api)까지 Boot BOM 버전으로 고정한다.
                implementation(enforcedPlatform(libs.spring.boot.bom))
                implementation(libs.archunit)
                implementation(libs.junit.jupiter)
                implementation(libs.assertj.core)
                runtimeOnly(libs.junit.platform.launcher)
            }
            targets.all {
                testTask.configure {
                    inputs.dir(rootProject.layout.projectDirectory.dir("disclosure-infra/src/main"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                    // 6B FlagTypeTableTest: 설계서 flag-types 블록 ↔ 모든 룰 번들 ↔ 마이그레이션 CHECK ↔ 코드 상수
                    inputs.file(rootProject.layout.projectDirectory.file("docs/설계서.md")).withPathSensitivity(PathSensitivity.RELATIVE)
                    inputs.dir(rootProject.layout.projectDirectory.dir("contracts/rules/bundles")).withPathSensitivity(PathSensitivity.RELATIVE)
                    inputs.dir(rootProject.layout.projectDirectory.dir("disclosure-demo/src/main/resources/demo/bundles"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                    inputs.dir(rootProject.layout.projectDirectory.dir("disclosure-infra/src/integrationTest/resources/rule-as-data"))
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                    // GlobalKekPathScanTest(코드·설정·스크립트)·PortfolioDocsTest(README·docs)는 저장소 전체를 걷는다 — 입력을 다 열거할 수 없으므로 매번 돈다
                    // (12단계: README에 금지어를 넣는 주입이 "최신"으로 건너뛰어 통과했다 — CI는 매번 새로 돌아 영향 없었다)
                    outputs.upToDateWhen { false }
                }
            }
        }
        // Phase 8 ③·G3: 이미지 시험 — 웹 이미지 헤더 = 데모 서빙 문자열, app 이미지가 비루트·읽기 전용 루트로 db migrate, 이미지 레이어 전수 스캔.
        // 이미지를 먼저 만든다(appImage·webImage — docker CLI). check에 넣지 않는다 — CI는 별도 잡 images.
        register<JvmTestSuite>("imageTest") {
            dependencies {
                implementation(project())
                implementation(testFixtures(project(":disclosure-infra")))
                implementation(platform(libs.spring.boot.bom))
                implementation(libs.spring.boot.starter.webmvc)
                implementation(libs.testcontainers)
                implementation(libs.jackson.databind)
                implementation(libs.junit.jupiter)
                implementation(libs.assertj.core)
                runtimeOnly(libs.junit.platform.launcher)
            }
            targets.all {
                testTask.configure {
                    dependsOn("appImage", "webImage")
                    // 레이어 스캐너가 레이어 하나를 통째로 메모리에 푼다(JRE 레이어 ≈ 150MB)
                    maxHeapSize = "1g"
                    systemProperty("ga.repoRoot", rootDir.absolutePath)
                    systemProperty("ga.image.app", appImageName)
                    systemProperty("ga.image.web", webImageName)
                    outputs.upToDateWhen { false }
                }
            }
        }
        // Phase 8 ③·G4: 배포 매니페스트 — 오버레이 렌더(kubectl kustomize) → kubeconform(쿠버네티스 스키마는 커밋 고정 URL, CRD는 저장소의 사본에서
        // 만든 스키마) → 우리 규칙(JobKind ↔ CronJob, 공개 라우트에 /internal 0, 헤더 지움, digest, tools.lock 해시, 비밀 볼륨 items, 보안 문맥).
        register<JvmTestSuite>("deployTest") {
            dependencies {
                implementation(project(":disclosure-workflow"))
                implementation(project(":disclosure-app"))
                implementation(platform(libs.spring.boot.bom))
                // 운영 기동 가드를 렌더된 파드 환경으로 그대로 돌린다(ProdStartupGuard.problems — 스프링 Environment)
                implementation(libs.spring.boot.autoconfigure)
                implementation(libs.jackson.databind)
                implementation(libs.jackson.dataformat.yaml)
                implementation(libs.junit.jupiter)
                implementation(libs.assertj.core)
                runtimeOnly(libs.junit.platform.launcher)
            }
            targets.all {
                testTask.configure {
                    dependsOn(deployTools)
                    systemProperty("ga.repoRoot", rootDir.absolutePath)
                    systemProperty("ga.tools", toolsDir.get().asFile.absolutePath)
                    inputs.dir(rootProject.layout.projectDirectory.dir("deploy")).withPathSensitivity(PathSensitivity.RELATIVE)
                }
            }
        }
        register<JvmTestSuite>("integrationTest") {
            dependencies {
                implementation(project())
                implementation(testFixtures(project(":disclosure-infra")))
                // 6A: 작업 잠금을 직접 쥐어 CLI의 "그 테넌트만 실패"를 본다(JobLockGateway·JobKind)
                implementation(project(":disclosure-infra"))
                implementation(project(":disclosure-workflow"))
                implementation(project(":platform-spring"))
                // 6A: 시험용 JWT 서명(Nimbus — oauth2-jose의 전이 의존, BOM 정렬)
                implementation(libs.spring.security.oauth2.jose)
                // 6A: 바인딩 순서 주입(TenantBindingOrderIT — 서블릿 필터를 시험 구성으로 끼운다)
                implementation(project(":disclosure-api"))
                implementation(libs.spring.boot.starter.webmvc)
                // Phase 7 G7: 미리보기 PDF의 쪽 텍스트(렌더러와 같은 좌표 openhtmltopdf-pdfbox — PDFBox)
                implementation(libs.openhtmltopdf.pdfbox)
                // Phase 8 G7: 헬스 지표를 직접 불러 상태·상세를 본다(HealthEndpointsIT)
                implementation(libs.spring.boot.starter.actuator)
                // 6A: 응답마다 OpenAPI 계약 스키마 검증(ApiContracts — 계약 정본 YAML을 그대로 읽는다)
                implementation(libs.json.schema.validator)
                implementation(libs.jackson.dataformat.yaml)
                implementation(project(":disclosure-domain"))
                implementation(libs.jackson.databind)
                implementation(platform(libs.spring.boot.bom))
                implementation(libs.spring.boot.starter.test)
                implementation(libs.junit.jupiter)
                implementation(libs.assertj.core)
                runtimeOnly(libs.junit.platform.launcher)
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.test)
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(testing.suites.named("archTest"), testing.suites.named("integrationTest"))
}

// 운영자 CLI(bootRun --args="--spring.profiles.active=cli ...")의 상대 경로(contracts/rules/bundles 등)는 저장소 루트 기준이다.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    workingDir = rootProject.projectDir
}

// HTTP 데모(disclosure-demo/scripts/http-demo.sh, 6A): 웹 앱을 부트 jar로 백그라운드에 띄운다(bootRun은 Gradle 데몬 아래라 스크립트가 끝낼 PID가 없다).
// jar는 툴체인 JDK로 컴파일되므로 그 실행 파일 경로를 알려 준다(PATH의 java가 더 낮을 수 있다).
tasks.register("demoJavaLauncher") {
    description = "Prints the toolchain java executable that runs the boot jar (http-demo.sh)."
    val launcher = javaToolchains.launcherFor(java.toolchain)
    doLast {
        println(launcher.get().executablePath.asFile.absolutePath)
    }
}
