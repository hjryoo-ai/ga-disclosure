// 공유 플랫폼(Spring·DB 무의존): RFC 8785 JCS 정규화 + SHA-256. 룰 번들 해시·봉인·감사 체인이 공용으로 쓴다.
// com.ga.platform:platform-canonical 로 발행한다(포털도 같은 정규화를 쓴다).
plugins {
    `maven-publish`
}

group = "com.ga.platform"
version = "0.1.0"

java {
    withSourcesJar()
}

dependencies {
    // JsonNode를 API에 노출한다(Jackson 3, Boot BOM 버전).
    api(platform(libs.spring.boot.bom))
    api(libs.jackson.databind)
    implementation(libs.jcs)
}

publishing {
    publications {
        create<MavenPublication>("platformCanonical") {
            artifactId = "platform-canonical"
            from(components["java"])
            versionMapping {
                allVariants { fromResolutionResult() }
            }
            pom {
                name = "platform-canonical"
                description = "GA platform RFC 8785 JSON canonicalization (JCS) and SHA-256 hashing (Spring/DB free)."
                licenses { license { name = "MIT"; url = "https://opensource.org/licenses/MIT" } }
            }
        }
    }
}

tasks.test {
    inputs.dir(layout.projectDirectory.dir("src/test/resources/jcs")).withPathSensitivity(PathSensitivity.RELATIVE)
}
