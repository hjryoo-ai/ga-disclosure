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
        // disclosure-web(Phase 7): node-gradle 플러그인이 내려받는 Node.js 배포본. 프로젝트 저장소 금지 규약 때문에 여기 두고, 그 모듈 하나로 좁힌다.
        ivy {
            name = "Node.js"
            setUrl("https://nodejs.org/dist/")
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
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
    "disclosure-web",
)
