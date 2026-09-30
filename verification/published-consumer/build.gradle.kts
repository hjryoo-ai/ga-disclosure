plugins {
    `java-library`
}

val fromGitHub = providers.gradleProperty("ga.platformRepo").orNull == "github"
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
    api("com.ga.platform:platform-core:0.1.0")
}

val platformSpring = configurations.create("platformSpring") {
    isCanBeConsumed = false
}

dependencies {
    platformSpring("com.ga.platform:platform-spring:0.1.0")
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
        require(names.any { it.startsWith("platform-spring-0.1.0") }) { "platform-spring not resolved: $names" }
        require(names.any { it.startsWith("platform-core-0.1.0") }) { "platform-core (transitive) not resolved: $names" }
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
    platformCanonical("com.ga.platform:platform-canonical:0.1.0")
}

tasks.register("verifyPlatformCanonical") {
    val files = platformCanonical
    doLast {
        val names = files.resolve().map { it.name }.sorted()
        require(names.any { it.startsWith("platform-canonical-0.1.0") }) { "platform-canonical not resolved: $names" }
        require(names.any { it.startsWith("java-json-canonicalization-1.1") }) { "JCS library (transitive) not resolved: $names" }
        require(names.any { it.startsWith("jackson-databind-3.") }) { "jackson-databind 3 (transitive) not resolved: $names" }
        println("platform-canonical resolves from $platformSource with ${names.size} artifacts: $names")
    }
}
