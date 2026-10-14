import java.security.MessageDigest

plugins {
    `java-library`
}

val fromGitHub = providers.gradleProperty("ga.platformRepo").orNull == "github"
// 루트 태스크가 플랫폼 모듈의 버전을 넘긴다(0.2.0부터 — 버전을 여기 두 번 적지 않는다).
val platformVersion: String = providers.gradleProperty("ga.platformVersion").orNull
    ?: throw GradleException("-Pga.platformVersion is required (run through the root verifyPublishedPlatform* tasks)")
val platformSource = if (fromGitHub) "GitHub Packages" else "mavenLocal"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(25) }
}

// 루트 저장소의 disclosure-domain 소스만 가져온다(프로젝트 의존 없음).
sourceSets {
    main {
        java.setSrcDirs(listOf(rootDir.resolve("../../disclosure-domain/src/main/java")))
    }
}

dependencies {
    api("com.ga.platform:platform-core:$platformVersion")
}

val platformSpring = configurations.create("platformSpring") {
    isCanBeConsumed = false
}

dependencies {
    platformSpring("com.ga.platform:platform-spring:$platformVersion")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-Werror"))
}

// platform-spring이 발행 메타데이터(POM·모듈)만으로 해석되는지 확인한다(전이 의존 버전 포함).
tasks.register("verifyPlatformSpringResolves") {
    val files = platformSpring
    doLast {
        val names = files.resolve().map { it.name }.sorted()
        require(names.any { it.startsWith("platform-spring-$platformVersion") }) { "platform-spring not resolved: $names" }
        require(names.any { it.startsWith("platform-core-$platformVersion") }) { "platform-core (transitive) not resolved: $names" }
        require(names.any { it.startsWith("spring-jdbc-") }) { "spring-jdbc (transitive) not resolved: $names" }
        println("platform-spring resolves from $platformSource with ${names.size} artifacts")
    }
}

tasks.named("compileJava") {
    doFirst {
        val core = configurations.compileClasspath.get().resolve().filter { it.name.startsWith("platform-core") }
        // mavenLocal 경로면 ~/.m2, GitHub Packages 경로면 Gradle 캐시(원격 해석)여야 한다.
        val ok = core.isNotEmpty() && core.all { it.path.contains("/.m2/") != fromGitHub }
        require(ok) { "platform-core must come from $platformSource: $core" }
        println("compiling disclosure-domain against ${core.map { it.path }} (from $platformSource)")
    }
}

// platform-canonical이 발행 메타데이터만으로 해석되고, JCS 라이브러리·Jackson(BOM 버전)이 전이로 따라오는지 확인한다.
val platformCanonical = configurations.create("platformCanonical") {
    isCanBeConsumed = false
}

dependencies {
    platformCanonical("com.ga.platform:platform-canonical:$platformVersion")
}

tasks.register("verifyPlatformCanonical") {
    val files = platformCanonical
    doLast {
        val names = files.resolve().map { it.name }.sorted()
        require(names.any { it.startsWith("platform-canonical-$platformVersion") }) { "platform-canonical not resolved: $names" }
        require(names.any { it.startsWith("java-json-canonicalization-1.1") }) { "JCS library (transitive) not resolved: $names" }
        require(names.any { it.startsWith("jackson-databind-3.") }) { "jackson-databind 3 (transitive) not resolved: $names" }
        println("platform-canonical resolves from $platformSource with ${names.size} artifacts: $names")
    }
}

// 소비자가 받은 플랫폼 jar(테스트 픽스처 포함)의 SHA-256 — 발행 워크플로 로그에 남겨 포털이 의존성 검증 메타데이터에 고정할 값의 1차 출처로 쓴다.
val platformFixtures = configurations.create("platformFixtures") {
    isCanBeConsumed = false
}

dependencies {
    platformFixtures("com.ga.platform:platform-core:$platformVersion") {
        capabilities { requireCapability("com.ga.platform:platform-core-test-fixtures") }
    }
}

tasks.register("printPlatformSha256") {
    val files = configurations.compileClasspath.get().incoming.files + platformSpring.incoming.files + platformCanonical.incoming.files + platformFixtures.incoming.files
    doLast {
        files.files.filter { it.name.startsWith("platform-") }.sortedBy { it.name }.forEach { f ->
            val hex = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            println("PLATFORM_SHA256 $hex  ${f.name} (from $platformSource)")
        }
    }
}
