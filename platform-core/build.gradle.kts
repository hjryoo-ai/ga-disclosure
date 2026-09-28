// 공유 플랫폼(Spring·DB 무의존). com.ga.platform:platform-core 로 발행해 ga-agent-portal이 후속 PR에서 소비한다.
plugins {
    `java-test-fixtures`
    `maven-publish`
}

group = "com.ga.platform"
version = "0.1.0"

java {
    withSourcesJar()
}

dependencies {
    // ArchRules는 규칙 라이브러리다. 소비자(아키텍처 테스트 소스셋)가 archunit을 직접 선언한다.
    // archunit이 끌어오는 slf4j-api도 Boot BOM 버전으로 고정한다(BOM 관리 버전 준수, CLAUDE.md 코드 규약).
    compileOnly(enforcedPlatform(libs.spring.boot.bom))
    compileOnly(libs.archunit)

    // SeededCases: JUnit 6 파라미터화 속성 테스트용 시드 고정 생성기(포털과 공유)
    testFixturesApi(platform(libs.spring.boot.bom))
    testFixturesApi(libs.junit.jupiter.params)

    testImplementation(enforcedPlatform(libs.spring.boot.bom))
    testImplementation(libs.archunit)
}

publishing {
    publications {
        create<MavenPublication>("platformCore") {
            artifactId = "platform-core"
            from(components["java"])
            versionMapping {
                allVariants { fromResolutionResult() }
            }
            pom {
                name = "platform-core"
                description = "GA platform shared value objects, TenantContext and ArchUnit rule library (Spring/DB free)."
                licenses { license { name = "MIT"; url = "https://opensource.org/licenses/MIT" } }
            }
        }
    }
}
