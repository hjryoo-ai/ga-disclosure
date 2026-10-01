// 독립 빌드: 메인 빌드에 포함하지 않는다(veraPDF는 GPL-3.0/MPL-2.0 이중 라이선스 — 메인 의존성에 넣지 않는다, 3B 계획 §5).
rootProject.name = "pdfa-verify"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
