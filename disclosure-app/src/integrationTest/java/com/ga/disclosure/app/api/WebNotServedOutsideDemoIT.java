package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7: 화면 서빙·데모 로그인은 데모 프로파일에만 있다. 그 밖의 프로파일에서는 화면 산출물이 jar에 있어도 같은 경로가 없는 경로와 같은 404 바이트다
 * (운영 분리·인그레스는 Phase 8).
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebNotServedOutsideDemoIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void screenAndDemoLoginPathsAreUnrouted() {
        ApiTestSupport.Response unrouted = ApiTestSupport.get(port, "/no-such-path", null);
        assertThat(unrouted.status()).isEqualTo(404);
        for (String path : new String[] {"/", "/staff", "/s", "/oidc-callback", "/assets/x.js", "/demo/oidc/authorize"}) {
            ApiTestSupport.Response r = ApiTestSupport.get(port, path, null);
            assertThat(r.status()).as(path).isEqualTo(404);
            assertThat(r.fingerprint()).as(path).isEqualTo(unrouted.fingerprint());
        }
        assertThat(ApiTestSupport.post(port, "/demo/oidc/token", null, "{}", java.util.Map.of()).status()).isEqualTo(404);
    }
}
