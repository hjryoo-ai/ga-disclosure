package com.ga.disclosure.app;

import com.ga.disclosure.app.api.ApiTestSupport;
import com.ga.disclosure.app.health.DatabaseHealthIndicator;
import com.ga.disclosure.app.health.IdpJwksHealthIndicator;
import com.ga.disclosure.app.health.StorageHealthIndicator;
import com.ga.disclosure.infra.migration.SchemaMigrator;
import com.ga.disclosure.infra.testing.PostgresHarness;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G7(8 계획 ③ 헬스): 관리 포트에서만 헬스가 응답하고, 준비성 = DB(헬스 롤)·저장소·IdP JWKS, 활성 = 프로세스만. 저장소가 쓸 수 없어도 활성은 UP이고
 * 준비성만 DOWN이다(재기동 대신 트래픽 제외). DB 지표는 테넌트 표를 읽지 않는다 — 헬스 롤이 그 권한부터 없다(V22RolesIT).
 */
class HealthEndpointsIT {

    private static final PostgresHarness DB = PostgresHarness.get();

    @Test
    void readinessAndLivenessAreUpOnTheManagementPortOnly() {
        try (ConfigurableApplicationContext web = start(Map.of())) {
            int port = port(web, "local.server.port");
            int management = port(web, "local.management.port");
            assertThat(management).isNotEqualTo(port);
            assertThat(ApiTestSupport.get(management, "/actuator/health/readiness", null).status()).isEqualTo(200);
            assertThat(ApiTestSupport.get(management, "/actuator/health/liveness", null).status()).isEqualTo(200);
            assertThat(ApiTestSupport.get(management, "/actuator/health", null).text()).contains("\"status\":\"UP\"");
            assertThat(ApiTestSupport.get(port, "/actuator/health/readiness", null).status()).isEqualTo(404);
            // 관리 포트에도 헬스 밖 액추에이터는 없다
            assertThat(ApiTestSupport.get(management, "/actuator/env", null).status()).isEqualTo(404);
            assertThat(ApiTestSupport.get(management, "/actuator/beans", null).status()).isEqualTo(404);

            assertThat(web.getBean(DatabaseHealthIndicator.class).health().getStatus()).isEqualTo(Status.UP);
            assertThat(web.getBean(StorageHealthIndicator.class).health().getStatus()).isEqualTo(Status.UP);
            Health idp = web.getBean(IdpJwksHealthIndicator.class).health();
            assertThat(idp.getStatus()).isEqualTo(Status.UP);
            assertThat(idp.getDetails()).containsEntry("mode", "static-key");
        }
    }

    /** 저장소 버킷이 없으면(만들지 않는 설정) 준비성만 503 — 활성은 200. */
    @Test
    void anUnusableBucketTakesTheAppOutOfRotationWithoutKillingIt() {
        try (ConfigurableApplicationContext web = start(Map.of("ga.storage.s3.bucket", "ga-health-missing-bucket", "ga.storage.s3.create-bucket", "false"))) {
            int management = port(web, "local.management.port");
            ApiTestSupport.Response readiness = ApiTestSupport.get(management, "/actuator/health/readiness", null);
            assertThat(readiness.status()).isEqualTo(503);
            assertThat(readiness.text()).contains("\"status\":\"DOWN\"").doesNotContain("ga-health-missing-bucket");
            assertThat(ApiTestSupport.get(management, "/actuator/health/liveness", null).status()).isEqualTo(200);
        }
    }

    /** DB 지표: 실패 상세는 SQLSTATE뿐(URL·롤·메시지 0), 버전 대조가 다르면 DOWN. */
    @Test
    void theDatabaseIndicatorReportsOnlyStateCodesAndVersions() {
        DatabaseHealthIndicator wrongPassword = new DatabaseHealthIndicator(DB.jdbcUrl(), PostgresHarness.HEALTH, "not-the-password",
                SchemaMigrator.bundledVersion());
        Health down = wrongPassword.health();
        assertThat(down.getStatus()).isEqualTo(Status.DOWN);
        assertThat(down.getDetails()).containsOnlyKeys("sqlState").containsEntry("sqlState", "28P01");

        DatabaseHealthIndicator otherRelease = new DatabaseHealthIndicator(DB.jdbcUrl(), PostgresHarness.HEALTH, PostgresHarness.HEALTH_PASSWORD,
                com.ga.disclosure.infra.migration.SchemaVersion.parse("999"));
        Health mismatch = otherRelease.health();
        assertThat(mismatch.getStatus()).isEqualTo(Status.DOWN);
        assertThat(mismatch.getDetails()).containsEntry("schema", SchemaMigrator.bundledVersion().toString()).containsEntry("application", "999");

        Health unreachable = new IdpJwksHealthIndicator(Optional.of(URI.create("http://127.0.0.1:9/jwks"))).health();
        assertThat(unreachable.getStatus()).isEqualTo(Status.DOWN);
        assertThat(unreachable.getDetails().toString()).doesNotContain("127.0.0.1");
    }

    private static ConfigurableApplicationContext start(Map<String, String> overrides) {
        Map<String, String> props = new LinkedHashMap<>();
        ApiTestSupport.propertyMap().forEach((k, v) -> props.put(k, String.valueOf(v.get())));
        props.putAll(overrides);
        List<String> args = new ArrayList<>();
        args.add("--server.port=0");
        props.forEach((k, v) -> args.add("--" + k + "=" + v));
        return new SpringApplicationBuilder(DisclosureApplication.class).web(org.springframework.boot.WebApplicationType.SERVLET).run(args.toArray(String[]::new));
    }

    private static int port(ConfigurableApplicationContext web, String name) {
        return Integer.parseInt(web.getEnvironment().getRequiredProperty(name));
    }
}
