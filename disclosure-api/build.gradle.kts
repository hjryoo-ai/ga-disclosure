// 설계사/관리자/준법 API(/api/v1), 서비스 주체 API(/internal/v1), 고객 서명 공개 엔드포인트(/public/v1). 6A: 보안 체인·테넌트 바인딩·오류 모델·컨트롤러.
dependencies {
    api(project(":disclosure-workflow"))
    api(project(":disclosure-compliance"))
    api(project(":platform-spring"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.security.oauth2.resource.server)
}
