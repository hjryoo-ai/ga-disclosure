// 공유 플랫폼(Spring 의존). com.ga.platform:platform-spring 으로 발행해 ga-agent-portal이 후속 PR에서 소비한다.
plugins {
    `maven-publish`
}

group = "com.ga.platform"
version = "0.1.0"

java {
    withSourcesJar()
}

dependencies {
    api(project(":platform-core"))

    // 소비자에게도 같은 BOM 정렬을 전파한다(발행 POM에는 versionMapping으로 해석된 버전이 박힌다).
    api(platform(libs.spring.boot.bom))
    api(libs.spring.jdbc)
    api(libs.spring.tx)
    implementation(libs.spring.boot.autoconfigure)

    // OIDC 리소스 서버 설정은 골격만 둔다(프로파일 "oidc"로만 활성, 구현·필터는 Phase 6).
    compileOnly(libs.spring.security.config)
    compileOnly(libs.spring.security.web)
    compileOnly(libs.spring.security.oauth2.resource.server)
    compileOnly(libs.spring.security.oauth2.jose)

    testImplementation("org.springframework.boot:spring-boot-test")
    testImplementation(libs.spring.security.config)
    testImplementation(libs.spring.security.web)
    testImplementation(libs.spring.security.oauth2.resource.server)
    testImplementation(libs.spring.security.oauth2.jose)
}

publishing {
    publications {
        create<MavenPublication>("platformSpring") {
            artifactId = "platform-spring"
            from(components["java"])
            versionMapping {
                allVariants { fromResolutionResult() }
            }
            pom {
                name = "platform-spring"
                description = "GA platform tenant session binding (RLS), TenantScopedRepository and identity contracts (Spring)."
                licenses { license { name = "MIT"; url = "https://opensource.org/licenses/MIT" } }
            }
        }
    }
}
