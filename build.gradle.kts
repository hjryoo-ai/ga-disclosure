import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import java.security.MessageDigest

plugins {
    base
    alias(libs.plugins.spring.boot) apply false
}

// ---------------------------------------------------------------------------------------------
// 공통: 의존성 락 + net.jqwik 차단 (CLAUDE.md 절대 규칙 9)
// ---------------------------------------------------------------------------------------------
allprojects {
    group = "com.ga.disclosure"
    version = "0.1.0-SNAPSHOT"

    dependencyLocking {
        lockAllConfigurations()
    }

    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "net.jqwik") {
                throw GradleException(
                    "CLAUDE.md 절대 규칙 9 위반: '${requested.group}:${requested.name}' 의존은 금지다 " +
                        "(설정 '${this@configureEach.name}'). jqwik은 1.10부터 AI 코딩 에이전트 사용 배제 조항과 " +
                        "테스트 출력 삽입 지시문을 가지며 JUnit Platform 1.14를 요구해 Boot 4의 JUnit 6과 비호환이다. " +
                        "속성 테스트는 platform-core 테스트 픽스처의 SeededCases를 쓴다."
                )
            }
        }
    }

    // ./gradlew resolveAndLockAll --write-locks 로 모든 해석 가능한 설정의 락 파일을 갱신한다.
    tasks.register("resolveAndLockAll") {
        group = "dependency locking"
        description = "Resolves every resolvable configuration so that --write-locks records it."
        notCompatibleWithConfigurationCache("resolves configurations at execution time")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "--write-locks 와 함께 실행한다" }
        }
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }

    // CI가 전 모듈 의존성 트리에 net.jqwik이 없음을 확인할 때 쓴다.
    tasks.register<DependencyReportTask>("allDependencies")
}

// ---------------------------------------------------------------------------------------------
// Java 모듈 공통 규약
// ---------------------------------------------------------------------------------------------
val catalog = libs

subprojects {
    apply(plugin = "java-library")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(catalog.versions.java.get())
        }
    }

    // -Xpkginfo:always: 아직 코드가 없는 패키지도 package-info.class가 생겨, 아키텍처 규칙 허용 목록의 폐기 항목 검사가
    // 그 패키지의 존재를 확인할 수 있다(ArchRules.stalePackages).
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-Werror", "-parameters", "-Xpkginfo:always"))
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("ga.repoRoot", rootDir.absolutePath)
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
        }
    }

    dependencies {
        "testImplementation"(platform(catalog.spring.boot.bom))
        "testImplementation"(catalog.junit.jupiter)
        "testImplementation"(catalog.assertj.core)
        "testRuntimeOnly"(catalog.junit.platform.launcher)
    }
}

// ---------------------------------------------------------------------------------------------
// Spring·DB 무의존 모듈: 컴파일·런타임 클래스패스에 org.springframework 그룹이 없어야 한다.
// (java.sql / javax.sql 은 JDK 모듈이라 클래스패스로 막을 수 없으므로 ArchUnit이 막는다.)
// ---------------------------------------------------------------------------------------------
val springFreeModules = listOf("platform-core", "platform-canonical", "disclosure-domain", "disclosure-rules", "disclosure-seal")

// Boot BOM(spring-boot-dependencies)은 버전 제약만 담은 POM이라 클래스가 없다 — 검사에서 제외한다.
val constraintOnlyPlatforms = setOf("org.springframework.boot:spring-boot-dependencies")

fun collectGroups(root: ResolvedComponentResult): Set<String> {
    val seen = mutableSetOf<ResolvedComponentResult>()
    val queue = ArrayDeque(listOf(root))
    val groups = mutableSetOf<String>()
    while (queue.isNotEmpty()) {
        val c = queue.removeFirst()
        if (!seen.add(c)) continue
        val mv = c.moduleVersion
        if (mv != null && "${mv.group}:${mv.name}" !in constraintOnlyPlatforms) groups += mv.group
        c.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue += it.selected }
    }
    return groups
}

springFreeModules.forEach { path ->
    project(":$path") {
        val verify = tasks.register("verifySpringFree") {
            group = "verification"
            description = "Fails when org.springframework artifacts appear on main classpaths."
            val roots = listOf("compileClasspath", "runtimeClasspath").map { name ->
                configurations.named(name).flatMap { it.incoming.resolutionResult.rootComponent }
            }
            doLast {
                val offending = roots.flatMap { collectGroups(it.get()) }
                    .filter { it.startsWith("org.springframework") }
                    .toSortedSet()
                if (offending.isNotEmpty()) {
                    throw GradleException("$path 은 Spring 무의존 모듈이다. 발견된 그룹: $offending")
                }
            }
        }
        tasks.named("check") { dependsOn(verify) }
    }
}

// platform-core는 런타임 의존이 0이다(ArchUnit·BOM은 compileOnly). 포털과 공유하는 최하층이 무엇도 끌고 오지 않게 한다.
// platform-canonical이 생기면서(Phase 1) JSON·해시 의존은 그쪽에만 둔다 — platform-core로 새어 들어오면 여기서 실패한다.
project(":platform-core") {
    val verify = tasks.register("verifyNoRuntimeDependencies") {
        group = "verification"
        description = "Fails when platform-core has any runtime dependency."
        val root = configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
        doLast {
            val external = collectGroups(root.get()) - setOf("com.ga.platform", "com.ga.disclosure")
            if (external.isNotEmpty()) {
                throw GradleException("platform-core must have no runtime dependencies, found groups: ${external.sorted()}")
            }
        }
    }
    tasks.named("check") { dependsOn(verify) }
}

// ---------------------------------------------------------------------------------------------
// contracts/CHECKSUMS — 엔진·포털 저장소와 계약 파일 일치 검사용
// ---------------------------------------------------------------------------------------------
val contractsDir = layout.projectDirectory.dir("contracts")
val checksumsFile = contractsDir.file("CHECKSUMS")

fun contractChecksumText(dir: File): String {
    val sha = { f: File -> MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) } }
    return dir.walkTopDown()
        .filter { it.isFile && it.name != "CHECKSUMS" && it.name != ".DS_Store" }
        .map { it.relativeTo(dir).invariantSeparatorsPath to it }
        .sortedBy { it.first }
        .joinToString(separator = "") { (rel, f) -> "${sha(f)}  $rel\n" }
}

tasks.register("contractChecksums") {
    group = "contracts"
    description = "Writes contracts/CHECKSUMS (sha256sum format, sorted by path)."
    val dir = contractsDir.asFile
    val out = checksumsFile.asFile
    inputs.dir(dir).withPathSensitivity(PathSensitivity.RELATIVE)
    doLast { out.writeText(contractChecksumText(dir)) }
}

val verifyContractChecksums = tasks.register("verifyContractChecksums") {
    group = "verification"
    description = "Fails when contracts/CHECKSUMS is stale. Run ./gradlew contractChecksums to refresh."
    val dir = contractsDir.asFile
    val out = checksumsFile.asFile
    inputs.dir(dir).withPathSensitivity(PathSensitivity.RELATIVE)
    doLast {
        val expected = contractChecksumText(dir)
        val actual = if (out.exists()) out.readText() else ""
        if (expected != actual) {
            throw GradleException("contracts/CHECKSUMS 가 계약 파일과 다르다. ./gradlew contractChecksums 로 갱신하라.")
        }
    }
}
tasks.named("check") { dependsOn(verifyContractChecksums) }

// ---------------------------------------------------------------------------------------------
// Phase 2 P4: 평문 유출 스캔 — 모든 모듈의 테스트 결과 XML(표준 출력·오류·실패 메시지·테스트 이름)에 센티널 고객의
// 이름·전화·생년월일(원문·변형·UTF-8 16진)이 한 번도 나타나지 않아야 한다. 센티널은 disclosure-infra 테스트 픽스처의
// pii-sentinels.properties(PlaintextLeakScanIT와 같은 파일). 모든 Test 태스크 뒤에 돌고 check에 묶인다.
// ---------------------------------------------------------------------------------------------
val sentinelsFile = layout.projectDirectory.file("disclosure-infra/src/testFixtures/resources/pii-sentinels.properties")

val scanPlaintextLeaks = tasks.register("scanPlaintextLeaks") {
    group = "verification"
    description = "Fails when a PII sentinel appears in any test result XML (Phase 2 P4)."
    val sentinels = sentinelsFile.asFile
    val root = layout.projectDirectory.asFile
    inputs.file(sentinels)
    doLast {
        val props = java.util.Properties()
        sentinels.reader(Charsets.UTF_8).use { props.load(it) }
        val plain = linkedSetOf(props.getProperty("name"), props.getProperty("phone"), props.getProperty("birthDate"))
        props.getProperty("variants").split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { plain.add(it) }
        val forbidden = plain + plain.map { v -> v.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) } }
        val results = root.listFiles().orEmpty()
            .map { it.resolve("build/test-results") }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.name.endsWith(".xml") }.toList() }
        if (results.isEmpty()) {
            throw GradleException("scanPlaintextLeaks: no test result XML found — run the tests first")
        }
        val hits = results.flatMap { f ->
            val text = f.readText(Charsets.UTF_8)
            forbidden.filter { text.contains(it) }.map { "${f.relativeTo(root)}: ${it.take(3)}…" }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("평문 개인정보 센티널이 테스트 출력에 나타났다(값은 앞 3자만 표시):\n" + hits.joinToString("\n"))
        }
        logger.lifecycle("scanPlaintextLeaks: ${results.size} result files, ${forbidden.size} forbidden strings, 0 hits")
    }
}
subprojects {
    tasks.withType<Test>().configureEach { finalizedBy(scanPlaintextLeaks) }
}
scanPlaintextLeaks.configure { mustRunAfter(subprojects.map { p -> p.tasks.withType<Test>() }) }
tasks.named("check") { dependsOn(scanPlaintextLeaks) }

// ---------------------------------------------------------------------------------------------
// C11: platform-core·platform-spring을 mavenLocal에 발행한 뒤, 발행 아티팩트만으로 disclosure-domain을
// 별도 빌드(verification/published-consumer)에서 컴파일한다. ~/.m2에 쓰므로 build에 묶지 않고 CI 잡으로 돌린다.
// ---------------------------------------------------------------------------------------------
tasks.register<Exec>("verifyPublishedPlatform") {
    group = "verification"
    description = "Publishes platform modules to mavenLocal and compiles disclosure-domain against them only."
    dependsOn(":platform-core:publishToMavenLocal", ":platform-canonical:publishToMavenLocal", ":platform-spring:publishToMavenLocal")
    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        "--project-dir", "verification/published-consumer",
        "--no-configuration-cache",
        "clean", "compileJava", "verifyPlatformSpringResolves", "verifyPlatformCanonical",
    )
}

// ---------------------------------------------------------------------------------------------
// 선행 C(Phase 2): 플랫폼 세 모듈을 GitHub Packages에도 발행한다(설계서 §11). 태그 `platform-v*`에서
// .github/workflows/publish-platform.yml이 실행한다. 자격증명은 환경변수에서만 읽고, 없으면 저장소를 구성하지 않는다
// (로컬·일반 CI에서는 publishToMavenLocal만 쓰인다). 세 모듈은 SemVer 동일 버전으로 움직인다.
// ---------------------------------------------------------------------------------------------
val platformModules = listOf("platform-core", "platform-canonical", "platform-spring")
val githubPackagesUrl = "https://maven.pkg.github.com/hjryoo-ai/ga-disclosure"
configure(platformModules.map { project(":$it") }) {
    pluginManager.withPlugin("maven-publish") {
        val actor = providers.environmentVariable("GITHUB_ACTOR")
        val token = providers.environmentVariable("GITHUB_TOKEN")
        if (actor.isPresent && token.isPresent) {
            extensions.configure<PublishingExtension> {
                repositories {
                    maven {
                        name = "GitHubPackages"
                        url = uri(githubPackagesUrl)
                        credentials {
                            username = actor.get()
                            password = token.get()
                        }
                    }
                }
            }
        }
    }
}

// 발행 태그(platform-vX.Y.Z)와 세 모듈의 빌드 버전이 모두 같은지 확인한다. 워크플로가 발행 전에 호출한다.
tasks.register("checkPlatformVersion") {
    group = "verification"
    description = "Fails unless every platform module has the version given by -PexpectedPlatformVersion."
    val expected = providers.gradleProperty("expectedPlatformVersion")
    val versions = platformModules.associateWith { project(":$it").version.toString() }
    doLast {
        val want = expected.orNull ?: throw GradleException("-PexpectedPlatformVersion is required")
        val wrong = versions.filterValues { it != want }
        if (wrong.isNotEmpty()) throw GradleException("platform version mismatch: tag=$want, modules=$versions")
        logger.lifecycle("platform modules all at $want: ${versions.keys}")
    }
}

// 발행된 아티팩트를 mavenLocal 없이 GitHub Packages에서만 해석해 disclosure-domain을 컴파일한다(발행물 소비 가능 증명).
// GITHUB_ACTOR/GITHUB_TOKEN(read:packages) 환경변수가 필요하다.
tasks.register<Exec>("verifyPublishedPlatformFromGitHub") {
    group = "verification"
    description = "Compiles disclosure-domain against platform artifacts resolved from GitHub Packages only."
    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        "--project-dir", "verification/published-consumer",
        "--no-configuration-cache", "--refresh-dependencies",
        "-Pga.platformRepo=github",
        "clean", "compileJava", "verifyPlatformSpringResolves", "verifyPlatformCanonical",
    )
}
