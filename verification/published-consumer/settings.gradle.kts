// C11 검증 전용 독립 빌드. 루트 빌드의 프로젝트를 참조하지 않고, mavenLocal에 발행된
// com.ga.platform:platform-core / platform-spring 아티팩트만으로 disclosure-domain 소스를 컴파일한다.
// 실행: ./gradlew verifyPublishedPlatform (루트)
rootProject.name = "published-platform-consumer"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // com.ga.platform 그룹은 오직 mavenLocal에서만 찾는다(원격에 있어도 쓰지 않는다).
        exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroup("com.ga.platform") }
        }
        mavenCentral()
    }
}
