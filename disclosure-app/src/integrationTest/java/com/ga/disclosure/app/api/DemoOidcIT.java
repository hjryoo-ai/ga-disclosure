package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 데모 OIDC(6A 계획 §10): {@code demo token}은 데모 프로파일에서만, JWT 클레임은 {@code sub}·{@code tenant_id}·{@code iss}·{@code aud}·{@code exp}뿐
 * (역할 클레임 없음), 서명 키는 저장소 밖 소유자 전용 파일(첫 사용에 생성), 공개키 PEM을 내보낸다. 그 PEM으로 뜬 웹 앱(발급자 {@code ga-demo}·대상
 * {@code ga-disclosure})이 데모 토큰을 받아들이고 — 역할은 {@code identity_link}에서 — 서명이 틀리거나 다른 발급자의 토큰은 401이다. 데모 키를 데모가
 * 아닌 프로파일에 두면 기동이 멈춘다.
 */
class DemoOidcIT {

    static final String T = SeedData.uniqueTenant("OIDC");

    @TempDir
    Path dir;

    static JsonNode claims(String jwt) {
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        return Canonicalizer.parseStrict(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
    }

    @Test
    void demoTokensWorkAgainstAWebAppThatTrustsTheExportedKey() throws Exception {
        FlowSupport.prepare(T);
        // Phase 8: 서명 키는 비밀 출처의 demo/oidc-signing — 앱은 만들지 않는다(secrets init --demo yes)
        String init = ApiTestSupport.cli("secrets", "init", "--secrets-dir", ApiTestSupport.SECRETS.toString(), "--demo", "yes");
        Path key = ApiTestSupport.SECRETS.resolve("demo/oidc-signing");
        // (Phase 8 9c) 스텁 TSA 키 저장소도 비밀로 — kind 데모의 모든 파드가 같은 키(소유자 전용, 스텁이 그대로 읽는다, 값은 출력하지 않는다)
        Path tsa = ApiTestSupport.SECRETS.resolve("demo/tsa-stub.p12");
        assertThat(init).containsPattern("SECRET demo/tsa-stub.p12 (CREATED|EXISTS)");
        assertThat(Files.getPosixFilePermissions(tsa)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        byte[] before = Files.readAllBytes(tsa);
        var stub = com.ga.disclosure.audit.tsa.stub.LocalStubTsa.loadOrCreate(tsa, dir.resolve("tsa-trust.pem"), java.time.Clock.systemUTC());
        assertThat(Files.readAllBytes(tsa)).as("loaded, not recreated").isEqualTo(before);
        assertThat(Files.readString(dir.resolve("tsa-trust.pem"))).isEqualTo(stub.trustAnchors().toPem());
        Path pem = dir.resolve("demo-oidc.pem");

        assertThatThrownBy(() -> ApiTestSupport.cli("demo", "token", "--tenant", T, "--subject", "compliance-1"))
                .hasStackTraceContaining("demo token needs the demo profile");
        String out = ApiTestSupport.cli("--spring.profiles.active=cli,demo", "--ga.demo.oidc-public-pem=" + pem,
                "demo", "token", "--tenant", T, "--subject", "compliance-1", "--ttl", "PT10M");
        String jwt = out.lines().filter(l -> l.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")).reduce((a, b) -> b).orElseThrow();

        JsonNode claims = claims(jwt);
        Set<String> names = new TreeSet<>();
        claims.propertyNames().forEach(names::add);
        assertThat(names).containsExactly("aud", "exp", "iss", "sub", "tenant_id");
        assertThat(claims.get("iss").asString()).isEqualTo("ga-demo");
        assertThat(claims.get("aud").asString()).isEqualTo("ga-disclosure");
        assertThat(claims.get("tenant_id").asString()).isEqualTo(T);
        assertThat(claims.get("exp").asLong() - Instant.now().getEpochSecond()).isBetween(9 * 60L, 10 * 60L + 5);
        assertThat(Files.readString(key)).startsWith("-----BEGIN PRIVATE KEY-----");
        assertThat(Files.getPosixFilePermissions(key)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(Files.readString(pem)).startsWith("-----BEGIN PUBLIC KEY-----");
        // 두 번째 발급은 같은 키(만들지 않는다)
        String keyBefore = Files.readString(key);
        ApiTestSupport.cli("--spring.profiles.active=cli,demo", "--ga.demo.oidc-public-pem=" + pem,
                "demo", "token", "--tenant", T, "--subject", "agent-1");
        assertThat(Files.readString(key)).isEqualTo(keyBefore);

        List<String> args = new ArrayList<>(List.of("--server.port=0"));
        for (Map.Entry<String, Supplier<Object>> e : ApiTestSupport.propertyMap().entrySet()) {
            if (!e.getKey().startsWith("ga.api.jwt.")) {
                args.add("--" + e.getKey() + "=" + e.getValue().get());
            }
        }
        args.addAll(List.of("--ga.api.jwt.issuer=ga-demo", "--ga.api.jwt.audience=ga-disclosure", "--ga.api.jwt.public-key-location=" + pem));
        try (ConfigurableApplicationContext web = new SpringApplicationBuilder(DisclosureApplication.class).web(WebApplicationType.SERVLET)
                .run(args.toArray(String[]::new))) {
            int port = Integer.parseInt(web.getEnvironment().getProperty("local.server.port"));
            ApiTestSupport.Response ok = ApiTestSupport.get(port, "/api/v1/jobs", jwt);
            assertThat(ok.status()).as(ok.text()).isEqualTo(200);
            String tampered = jwt.substring(0, jwt.length() - 2) + (jwt.endsWith("AA") ? "BB" : "AA");
            assertThat(ApiTestSupport.get(port, "/api/v1/jobs", tampered).status()).isEqualTo(401);
            assertThat(ApiTestSupport.get(port, "/api/v1/jobs", TestJwts.token(T, "compliance-1")).status()).as("another issuer's key").isEqualTo(401);
        }
    }

    @Test
    void demoKeysOutsideTheDemoProfileStopTheBoot() {
        assertThatThrownBy(() -> ApiTestSupport.cli("--ga.demo.oidc-public-pem=" + dir.resolve("k"), "demo", "token", "--tenant", T, "--subject", "x"))
                .hasStackTraceContaining("demo-only keys are set outside the demo profile").hasStackTraceContaining("ga.demo.oidc-public-pem");
    }
}
