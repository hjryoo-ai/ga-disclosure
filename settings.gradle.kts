rootProject.name = "ga-disclosure"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // JDK 25 툴체인 자동 프로비저닝(로컬에 JDK 25가 없어도 빌드 가능)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

include(
    "platform-core",
    "platform-spring",
    "platform-canonical",
    "disclosure-domain",
    "disclosure-rules",
    "disclosure-workflow",
    "disclosure-seal",
    "disclosure-sign",
    "disclosure-audit",
    "disclosure-compliance",
    "disclosure-api",
    "disclosure-infra",
    "disclosure-app",
    "disclosure-demo",
)
