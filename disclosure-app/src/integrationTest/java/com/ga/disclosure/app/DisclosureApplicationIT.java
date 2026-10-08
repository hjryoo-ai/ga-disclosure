package com.ga.disclosure.app;

import com.ga.disclosure.app.api.ApiTestSupport;
import com.ga.platform.spring.jdbc.TenantSessionBinder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 부팅 스모크: 애플리케이션이 disclosure_app 데이터소스와 disclosure_migrator Flyway로 뜨고,
 * /actuator/health가 UP이며, 트랜잭션 매니저가 TenantSessionBinder다. 보안 체인 밖 경로는 헬스 외 전부 내부 404와 같은 본문이다(6A 계획 §5.1).
 * 컨트롤러 배치 규칙은 ApiLayerRulesTest (a)가 맡는다(자리표시 단언 noApplicationControllersExist 폐기 — 6A 계획 §9.4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DisclosureApplicationIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    ApplicationContext context;

    @Test
    void healthIsUpAndTenantBinderIsTheTransactionManager() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");

        assertThat(context.getBeansOfType(PlatformTransactionManager.class).values())
                .singleElement().isInstanceOf(TenantSessionBinder.class);
    }

    @Test
    void pathsOutsideTheChainsAreTheInternal404() {
        ApiTestSupport.Response notFound = ApiTestSupport.get(port, "/actuator/env", null);
        assertThat(notFound.status()).isEqualTo(404);
        assertThat(notFound.text()).isEqualTo("{\"code\":\"NOT_FOUND\",\"details\":{},\"message\":\"Resource not found.\"}");
        assertThat(ApiTestSupport.get(port, "/", null).fingerprint()).isEqualTo(notFound.fingerprint());
        assertThat(ApiTestSupport.get(port, "/sign/abc", null).fingerprint()).isEqualTo(notFound.fingerprint());
        assertThat(ApiTestSupport.send(port, "POST", "/actuator/health", null, "{}", java.util.Map.of()).status()).isEqualTo(404);
    }

    /** Phase 2 P5: 앱이 쓰는 JSON 매퍼(Boot 자동 구성)는 개인정보 값객체 직렬화를 거부한다(가드 모듈 등록 확인). */
    @Test
    void applicationJsonMapperRefusesPersonalData() {
        tools.jackson.databind.json.JsonMapper mapper = context.getBean(tools.jackson.databind.json.JsonMapper.class);
        assertThatThrownBy(() -> mapper.writeValueAsString(com.ga.disclosure.domain.pii.CustomerName.of("홍길동")))
                .isInstanceOf(tools.jackson.databind.DatabindException.class)
                .hasMessageContaining("must not be serialized").hasMessageNotContaining("홍길동");
        assertThatThrownBy(() -> mapper.writeValueAsString(java.util.Map.of("phone", com.ga.disclosure.domain.pii.PhoneNumber.of("010-5550-0101"))))
                .isInstanceOf(tools.jackson.databind.DatabindException.class).hasMessageNotContaining("5550");
    }
}
