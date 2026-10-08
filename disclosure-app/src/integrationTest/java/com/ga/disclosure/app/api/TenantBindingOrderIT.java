package com.ga.disclosure.app.api;

import com.ga.disclosure.api.security.ApiSecurityConfiguration;
import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.infra.persistence.AuthzFactsRepository;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.platform.core.tenant.TenantNotBoundException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static com.ga.disclosure.app.api.ApiTestSupport.get;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3(6A 계획 §8): RLS 바인딩이 인가보다 먼저다.
 * <ul>
 *   <li>토큰 테넌트 ≠ 자원 테넌트 → 404이고, 바이트가 없는 자원의 404와 같다(다른 테넌트 자원은 인가 이전에 "없음").</li>
 *   <li>주입: 바인딩 전에 조회하는 필터를 끼우면 {@link TenantNotBoundException}으로 500 — 바인딩 없이는 데이터에 닿을 수 없다.</li>
 * </ul>
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TenantBindingOrderIT.PreBindingProbe.class)
class TenantBindingOrderIT {

    static final String TENANT_A = SeedData.uniqueTenant("BINDA");
    static final String TENANT_B = SeedData.uniqueTenant("BINDB");
    static final String PROBE_HEADER = "X-Test-PreBinding-Probe";
    static final AtomicReference<Throwable> PROBE_FAILURE = new AtomicReference<>();
    static UUID jobOfA;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @BeforeAll
    static void seed() {
        DB.seed(TENANT_A, c -> {
            SeedData.tenant(c, TENANT_A);
            SeedData.roleLink(c, TENANT_A, "compliance-a", "COMPLIANCE");
            jobOfA = SeedData.asyncJob(c, TENANT_A, "VERIFY_TENANT");
        });
        DB.seed(TENANT_B, c -> {
            SeedData.tenant(c, TENANT_B);
            SeedData.roleLink(c, TENANT_B, "compliance-b", "COMPLIANCE");
        });
    }

    /** 바인딩 전(보안 체인 앞)에 테넌트 저장소를 부르는 필터 — 헤더가 있을 때만. */
    @TestConfiguration
    static class PreBindingProbe {
        @Bean
        FilterRegistrationBean<OncePerRequestFilter> preBindingProbe(AuthzFactsRepository facts, PlatformTransactionManager tx) {
            OncePerRequestFilter probe = new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                        throws ServletException, IOException {
                    if (request.getHeader(PROBE_HEADER) != null) {
                        try {
                            new TransactionTemplate(tx).execute(s -> facts.tenantExists());
                        } catch (RuntimeException e) {
                            PROBE_FAILURE.set(e);
                            throw e;
                        }
                    }
                    chain.doFilter(request, response);
                }
            };
            FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(probe);
            registration.setOrder(ApiSecurityConfiguration.BARRIER_ORDER + 1);
            return registration;
        }
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void anotherTenantsResourceIsTheSame404AsAMissingOne() {
        String a = TestJwts.token(TENANT_A, "compliance-a");
        String b = TestJwts.token(TENANT_B, "compliance-b");
        assertThat(get(port, "/api/v1/jobs/" + jobOfA, a).status()).isEqualTo(200);

        ApiTestSupport.Response missing = get(port, "/api/v1/jobs/" + UUID.randomUUID(), b);
        assertThat(missing.status()).isEqualTo(404);
        assertThat(get(port, "/api/v1/jobs/" + jobOfA, b).fingerprint()).isEqualTo(missing.fingerprint());
        assertThat(get(port, "/api/v1/jobs/" + jobOfA + "/report", b).fingerprint()).isEqualTo(missing.fingerprint());
        assertThat(get(port, "/api/v1/jobs", b).text()).doesNotContain(jobOfA.toString());
    }

    @Test
    void dataAccessBeforeBindingFailsClosed() {
        PROBE_FAILURE.set(null);
        ApiTestSupport.Response r = ApiTestSupport.send(port, "GET", "/api/v1/jobs", TestJwts.token(TENANT_A, "compliance-a"), null,
                Map.of(PROBE_HEADER, "1"));
        assertThat(PROBE_FAILURE.get()).isInstanceOf(TenantNotBoundException.class);
        assertThat(r.status()).isEqualTo(500);
        assertThat(r.text()).isEqualTo("{\"code\":\"INTERNAL_ERROR\",\"details\":{},\"message\":\"Internal error.\"}");
    }
}
