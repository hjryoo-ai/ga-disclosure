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

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-Werror", "-parameters"))
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
val springFreeModules = listOf("platform-core", "disclosure-domain", "disclosure-rules", "disclosure-seal")

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
// C11: platform-core·platform-spring을 mavenLocal에 발행한 뒤, 발행 아티팩트만으로 disclosure-domain을
// 별도 빌드(verification/published-consumer)에서 컴파일한다. ~/.m2에 쓰므로 build에 묶지 않고 CI 잡으로 돌린다.
// ---------------------------------------------------------------------------------------------
tasks.register<Exec>("verifyPublishedPlatform") {
    group = "verification"
    description = "Publishes platform modules to mavenLocal and compiles disclosure-domain against them only."
    dependsOn(":platform-core:publishToMavenLocal", ":platform-spring:publishToMavenLocal")
    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        "--project-dir", "verification/published-consumer",
        "--no-configuration-cache",
        "clean", "compileJava", "verifyPlatformSpringResolves",
    )
}
