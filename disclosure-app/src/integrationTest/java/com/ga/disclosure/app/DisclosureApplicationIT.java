package com.ga.disclosure.app;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.platform.spring.jdbc.TenantSessionBinder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
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
 * /actuator/health가 UP이며, 트랜잭션 매니저가 TenantSessionBinder다. 컨트롤러는 없다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DisclosureApplicationIT {

    private static final PostgresHarness DB = PostgresHarness.get();

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.flyway.url", DB::jdbcUrl);
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
    void noApplicationControllersExist() {
        assertThat(context.getBeansWithAnnotation(org.springframework.stereotype.Controller.class).values())
                .noneMatch(bean -> bean.getClass().getName().startsWith("com.ga."));
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
