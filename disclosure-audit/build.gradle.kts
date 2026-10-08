// 감사 체인·앵커(머클)·TSA·verify 순수 계산(Phase 5). Spring·DB 무의존 — 배치 조정은 disclosure-workflow(5 계획 승인 Q1).
dependencies {
    api(project(":disclosure-domain"))
    api(project(":platform-canonical"))
    // RFC 3161 토큰 생성(스텁)·검증. 참조는 com.ga.disclosure.audit.tsa.. 안에서만(ArchitectureRulesTest) — 포트·결과 타입에 BC 타입이 없어 implementation
    implementation(libs.bcpkix)
    implementation(libs.bcprov)
    implementation(libs.bcutil)
    // verify package: 증거 매니페스트·영수증 내보내기·보고서 스키마 검증(생산자 코드인 seal과 독립 — 같은 계약 파일을 직접 싣는다)
    implementation(libs.json.schema.validator)

    testImplementation(testFixtures(project(":platform-core")))
    // VerifyPackageTest: 생산자(seal)의 실제 빌더로 만든 패키지를 검증한다 — main은 seal에 의존하지 않는다(레이어 규칙·ArchUnit)
    testImplementation(project(":disclosure-seal"))
}

// 계약 스키마(contracts/seal·verify)를 클래스패스 ga-contracts/ 아래에 싣는다 — verify package가 생산자(seal)를 거치지 않고 직접 검증한다.
val contractResources = tasks.register<Sync>("contractResources") {
    from(rootProject.layout.projectDirectory.dir("contracts")) {
        include("seal/**", "verify/**")
        into("ga-contracts")
    }
    into(layout.buildDirectory.dir("generated/contract-resources"))
}

sourceSets {
    main {
        resources.srcDir(contractResources)
    }
}

// MerkleSpecTableTest가 설계서 §6.7의 merkle-spec 블록(정본)을, BouncyCastlePinTest가 카탈로그·락을 읽는다 — 바뀌면 다시 돌린다.
tasks.test {
    inputs.file(rootProject.layout.projectDirectory.file("docs/설계서.md")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.layout.projectDirectory.file("gradle/libs.versions.toml")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(layout.projectDirectory.file("gradle.lockfile")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.layout.projectDirectory.dir("contracts")).withPathSensitivity(PathSensitivity.RELATIVE)
}

// 실 TSA 계약 시험(6A 계획 §12 9단계·결정 8 보강, 설계서 §14 #4): 외부 RFC 3161 TSA에서 실제로 토큰을 받아 운영 경로(HttpTimestampAuthority →
// TimestampClient 수락·검증)를 확인한다. 네트워크와 외부 서비스가 필요하므로 check에 걸지 않는다 — 걸면 환경이 없을 때 "스킵"이 생긴다.
// 실행: GA_TSA_URL=<TSA URL> GA_TSA_TRUST_PEM=<그 TSA의 신뢰 앵커 PEM 경로> ./gradlew :disclosure-audit:tsaContractTest
// 둘 중 하나라도 없으면 시험은 스킵이 아니라 실패한다. 결과는 매번 새로 받는다(캐시하지 않는다).
testing {
    suites {
        register<JvmTestSuite>("tsaContractTest") {
            dependencies {
                implementation(project())
                implementation(platform(libs.spring.boot.bom))
                implementation(libs.junit.jupiter)
                implementation(libs.assertj.core)
                runtimeOnly(libs.junit.platform.launcher)
            }
            targets.all {
                testTask.configure {
                    systemProperty("ga.tsa.contract.url", System.getenv("GA_TSA_URL") ?: "")
                    systemProperty("ga.tsa.contract.trust-pem", System.getenv("GA_TSA_TRUST_PEM") ?: "")
                    outputs.upToDateWhen { false }
                }
            }
        }
    }
}
