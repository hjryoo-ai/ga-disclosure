package com.ga.disclosure.app;

import com.ga.disclosure.app.api.ApiTestSupport;
import com.ga.disclosure.app.config.ProdStartupGuard;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeaweedHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G3 운영 필수 키(8 계획 ③): {@code prod} 프로파일은 필수 키가 하나라도 없으면 어떤 빈보다 먼저 멈추고, 문장은 키 이름만이다 — 주어진 다른 키의 값(센티널)도,
 * {@code application.yaml}의 로컬 기본값도 싣지 않는다. 운영 금지 값(스텁·환경변수 비밀·버킷 생성·정적 공개키)도 멈춘다. 전부 주면 실제로 뜬다.
 */
class ProdStartupGuardIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    /** 문장에 나오면 안 되는 값 — 주어진 값 전부에 이 표식을 섞는다. */
    private static final String SENTINEL = "zz-sentinel-value-7Q";

    /** {@code application-prod.yaml}의 자리표시(환경 이름) → 시험 값. 실제 하네스에 닿는 값이라 전부 주면 기동한다. */
    private static Map<String, String> environment() {
        SeaweedHarness s3 = SeaweedHarness.get();
        Map<String, String> env = new LinkedHashMap<>();
        env.put("DISCLOSURE_DB_URL", DB.jdbcUrl() + "&ApplicationName=" + SENTINEL);
        env.put("DISCLOSURE_APP_USER", PostgresHarness.APP);
        env.put("DISCLOSURE_APP_PASSWORD", "app_local_only");
        env.put("DISCLOSURE_HEALTH_USER", PostgresHarness.HEALTH);
        env.put("DISCLOSURE_HEALTH_PASSWORD", PostgresHarness.HEALTH_PASSWORD);
        env.put("DISCLOSURE_OPERATOR_USER", PostgresHarness.OPERATOR);
        env.put("DISCLOSURE_OPERATOR_PASSWORD", PostgresHarness.OPERATOR_PASSWORD);
        env.put("DISCLOSURE_JOB_LOCK_USER", PostgresHarness.JOB_LOCK);
        env.put("DISCLOSURE_JOB_LOCK_PASSWORD", PostgresHarness.JOB_LOCK_PASSWORD);
        env.put("GA_SECRETS_DIR", ApiTestSupport.SECRETS.toString());
        env.put("GA_S3_ENDPOINT", s3.endpoint().toString());
        env.put("GA_S3_REGION", "us-east-1");
        env.put("GA_S3_BUCKET", s3.freshBucket());
        env.put("GA_S3_ACCESS_KEY_ID", SeaweedHarness.ACCESS_KEY);
        env.put("GA_S3_SECRET_ACCESS_KEY", SeaweedHarness.SECRET_KEY);
        env.put("GA_JWT_ISSUER", "https://idp." + SENTINEL + ".invalid");
        env.put("GA_JWT_AUDIENCE", "ga-disclosure");
        env.put("GA_JWT_JWK_SET_URI", "https://idp." + SENTINEL + ".invalid/jwks");
        env.put("GA_CLIENT_CERT_SUBJECT_HEADER", "X-Client-Cert-Subject");
        env.put("GA_PUBLIC_SIGN_MIN_RESPONSE_MILLIS", "30");
        env.put("GA_SIGN_LINK_BASE_URL", "https://sign." + SENTINEL + ".invalid/s#");
        env.put("GA_TSA_URL", "https://tsa." + SENTINEL + ".invalid");
        return env;
    }

    /** 필수 키 ↔ 그 값을 주는 환경 이름(application-prod.yaml과 같은 짝). */
    static Stream<String> environmentNames() {
        return environment().keySet().stream().filter(n -> !n.equals("DISCLOSURE_DB_URL"));
    }

    @Test
    void nothingGivenStopsWithEveryRequiredKeyNameAndNoValues() {
        assertThatThrownBy(() -> start(Map.of(), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("prod profile cannot start — missing or forbidden settings: " + String.join(", ", ProdStartupGuard.REQUIRED))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("local_only", "localhost", "example.invalid", "ga-local"));
    }

    @ParameterizedTest
    @MethodSource("environmentNames")
    void eachMissingSettingIsNamedAlone(String missing) {
        Map<String, String> env = environment();
        env.remove(missing);
        assertThatThrownBy(() -> start(env, List.of()))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> {
                    String names = e.getMessage().substring(e.getMessage().indexOf(": ") + 2);
                    assertThat(names.split(", ")).hasSize(1)
                            .allMatch(ProdStartupGuard.REQUIRED::contains);
                    assertThat(e.getMessage()).doesNotContain(SENTINEL, "local_only", PostgresHarness.HEALTH_PASSWORD);
                });
    }

    @Test
    void forbiddenProdValuesStopStartup() {
        assertThatThrownBy(() -> start(environment(), List.of("--ga.engine.mode=stub", "--ga.secrets.source=env", "--ga.tsa.mode=stub",
                "--ga.storage.s3.create-bucket=true", "--ga.api.jwt.public-key-location=/tmp/" + SENTINEL + ".pem")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("prod profile cannot start — missing or forbidden settings: ga.api.jwt.public-key-location (not allowed in prod), "
                        + "ga.engine.mode (not allowed in prod), ga.secrets.source (not allowed in prod), "
                        + "ga.storage.s3.create-bucket (not allowed in prod), ga.tsa.mode (not allowed in prod)");
    }

    @Test
    void everythingGivenStarts() {
        try (ConfigurableApplicationContext app = start(environment(), List.of())) {
            assertThat(app.getEnvironment().getActiveProfiles()).containsExactly("prod");
            assertThat(ProdStartupGuard.problems(app.getEnvironment())).isEmpty();
            // 운영 로그는 구조화(JSON, Boot 내장 ECS) — 배포 전 로그 스캔이 줄 단위 JSON을 읽는다
            assertThat(app.getEnvironment().getProperty("logging.structured.format.console")).isEqualTo("ecs");
        }
    }

    private static ConfigurableApplicationContext start(Map<String, String> env, List<String> extra) {
        List<String> args = new ArrayList<>(List.of("--spring.profiles.active=prod", "--server.port=0", "--management.server.port=0", "--ga.internal.port=0"));
        env.forEach((k, v) -> args.add("--" + k + "=" + v));
        args.addAll(extra);
        return new SpringApplicationBuilder(DisclosureApplication.class).web(WebApplicationType.SERVLET).run(args.toArray(String[]::new));
    }
}
