// C11 검증 전용 독립 빌드. 루트 빌드의 프로젝트를 참조하지 않고, mavenLocal에 발행된
// com.ga.platform 아티팩트(또는 -Pga.platformRepo=github이면 GitHub Packages 발행물)만으로 disclosure-domain 소스를 컴파일한다.
// 실행: ./gradlew verifyPublishedPlatform (루트)
rootProject.name = "published-platform-consumer"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // com.ga.platform 그룹은 정확히 한 저장소에서만 찾는다. 기본은 mavenLocal(루트 verifyPublishedPlatform),
        // -Pga.platformRepo=github이면 GitHub Packages만(루트 verifyPublishedPlatformFromGitHub, 발행 워크플로).
        // 자격증명은 환경변수 GITHUB_ACTOR/GITHUB_TOKEN(read:packages)에서만 읽는다.
        val fromGitHub = providers.gradleProperty("ga.platformRepo").orNull == "github"
        exclusiveContent {
            if (fromGitHub) {
                forRepository {
                    maven {
                        name = "GitHubPackages"
                        url = uri("https://maven.pkg.github.com/hjryoo-ai/ga-disclosure")
                        credentials {
                            username = providers.environmentVariable("GITHUB_ACTOR").orNull
                            password = providers.environmentVariable("GITHUB_TOKEN").orNull
                        }
                    }
                }
            } else {
                forRepository { mavenLocal() }
            }
            filter { includeGroup("com.ga.platform") }
        }
        mavenCentral()
    }
}
