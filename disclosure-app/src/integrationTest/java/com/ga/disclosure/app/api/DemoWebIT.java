package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.app.demo.DemoWebConfiguration;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.ga.disclosure.app.api.ApiTestSupport.ROOT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 "서버 쪽 추가" 1·3항(데모 프로파일): 화면 서빙 체인과 데모 로그인.
 * <ul>
 *   <li>{@code /}·{@code /staff/**}·{@code /s}·{@code /oidc-callback}은 Vite 산출 HTML(no-store), {@code /assets/*}는 해시 이름 자산(1년 immutable) — 모든
 *       화면 응답에 CSP·{@code Referrer-Policy: no-referrer}·nosniff·프레임 금지. 자산 이름 밖·다른 메서드는 404.</li>
 *   <li>데모 로그인(Authorization Code + PKCE): 폼은 닫힌 매개변수만, 제출은 303 콜백, 코드는 한 번·검증자 대조, 토큰은 API가 받아들인다. 라우트 = 데모 계약.</li>
 * </ul>
 * 데모가 아닌 프로파일에서 같은 경로가 404인 것은 {@link WebNotServedOutsideDemoIT}.
 */
class DemoWebIT {

    static final String T = SeedData.uniqueTenant("DWEB");
    static ConfigurableApplicationContext web;
    static int port;
    static Path dir;

    @BeforeAll
    static void boot() throws Exception {
        FlowSupport.prepare(T);
        dir = Files.createTempDirectory("ga-demo-web");
        Path pem = dir.resolve("demo-oidc.pem");
        // 데모 웹 앱은 기동 때 공개키 PEM을 읽는다 — 키를 먼저 만든다(http-demo.sh와 같은 순서). 서명 키는 비밀 출처(Phase 8)
        ApiTestSupport.cli("secrets", "init", "--secrets-dir", ApiTestSupport.SECRETS.toString(), "--demo", "yes");
        ApiTestSupport.cli("--spring.profiles.active=cli,demo", "--ga.demo.oidc-public-pem=" + pem,
                "demo", "token", "--tenant", T, "--subject", "compliance-1");
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--spring.profiles.active=demo",
                "--ga.demo.oidc-public-pem=" + pem, "--ga.demo.login.accounts[0]=" + T + "/compliance-1", "--ga.demo.login.accounts[1]=" + T + "/agent-1"));
        for (Map.Entry<String, Supplier<Object>> e : ApiTestSupport.propertyMap().entrySet()) {
            if (!e.getKey().startsWith("ga.api.jwt.")) {
                args.add("--" + e.getKey() + "=" + e.getValue().get());
            }
        }
        args.add("--ga.api.jwt.public-key-location=" + pem);
        web = new SpringApplicationBuilder(DisclosureApplication.class).web(WebApplicationType.SERVLET).run(args.toArray(String[]::new));
        port = Integer.parseInt(web.getEnvironment().getProperty("local.server.port"));
    }

    @AfterAll
    static void stop() {
        if (web != null) {
            web.close();
        }
    }

    static HttpResponse<byte[]> send(String method, String path, String formOrNull) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (formOrNull == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/x-www-form-urlencoded").method(method, HttpRequest.BodyPublishers.ofString(formOrNull));
        }
        try (HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    static String header(HttpResponse<?> r, String name) {
        return r.headers().firstValue(name).orElse(null);
    }

    /** CSP를 지시어별로 본다(상수와의 같음만 보면 상수를 약하게 고쳐도 통과한다). */
    static void assertStrictCsp(String csp) {
        Map<String, String> d = new java.util.LinkedHashMap<>();
        for (String part : csp.split(";")) {
            String p = part.strip();
            int sp = p.indexOf(' ');
            d.put(sp < 0 ? p : p.substring(0, sp), sp < 0 ? "" : p.substring(sp + 1).strip());
        }
        assertThat(csp).doesNotContain("unsafe-").doesNotContain("http:").doesNotContain("https:").doesNotContain("*");
        assertThat(d).containsEntry("default-src", "'self'").containsEntry("script-src", "'self'").containsEntry("style-src", "'self'")
                .containsEntry("connect-src", "'self'").containsEntry("font-src", "'self'").containsEntry("worker-src", "'self'")
                .containsEntry("object-src", "'none'").containsEntry("base-uri", "'none'").containsEntry("frame-ancestors", "'none'")
                .containsEntry("form-action", "'self'");
    }

    static void assertScreenHeaders(HttpResponse<?> r) {
        assertThat(header(r, "Content-Security-Policy")).isEqualTo(DemoWebConfiguration.CSP);
        assertStrictCsp(header(r, "Content-Security-Policy"));
        assertThat(header(r, "Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(header(r, "X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(header(r, "X-Frame-Options")).isEqualTo("DENY");
        assertThat(r.headers().allValues("Set-Cookie")).isEmpty();
    }

    @Test
    void screensAreServedSameOriginWithStrictHeaders() throws Exception {
        for (String path : new String[] {"/", "/staff", "/staff/disclosures/" + UUID.randomUUID(), "/s", "/oidc-callback"}) {
            HttpResponse<byte[]> r = send("GET", path, null);
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            assertThat(header(r, "Content-Type")).isEqualTo("text/html;charset=UTF-8");
            assertThat(header(r, "Cache-Control")).isEqualTo("no-store");
            assertScreenHeaders(r);
            assertThat(new String(r.body(), StandardCharsets.UTF_8)).contains("<script type=\"module\" crossorigin src=\"/assets/");
        }
        String staff = new String(send("GET", "/", null).body(), StandardCharsets.UTF_8);
        Matcher js = Pattern.compile("src=\"/assets/([^\"]+\\.js)\"").matcher(staff);
        assertThat(js.find()).isTrue();
        HttpResponse<byte[]> asset = send("GET", "/assets/" + js.group(1), null);
        assertThat(asset.statusCode()).isEqualTo(200);
        assertThat(header(asset, "Content-Type")).isEqualTo("text/javascript");
        assertThat(header(asset, "Cache-Control")).isEqualTo("max-age=31536000, public, immutable");
        assertScreenHeaders(asset);

        for (String path : new String[] {"/assets/no-such-file.js", "/assets/x.exe", "/assets/a/b.js", "/assets/.hidden.js"}) {
            assertThat(send("GET", path, null).statusCode()).as(path).isEqualTo(404);
        }
        // 인코딩된 경로 구분자는 핸들러에 닿기 전에 방화벽이 거부한다(StrictHttpFirewall 400)
        assertThat(send("GET", "/assets/..%2fapplication.yaml", null).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/", "x=1").statusCode()).as("other methods fall to the deny-all chain").isEqualTo(404);
        assertThat(send("GET", "/demo/oidc/token", null).statusCode()).isEqualTo(404);
    }

    static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Test
    void theDemoSignInIsAuthorizationCodeWithPkceAndTheTokenOpensTheApi() throws Exception {
        String verifier = b64url(UUID.randomUUID().toString().concat(UUID.randomUUID().toString()).getBytes(StandardCharsets.US_ASCII));
        String challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        String state = b64url(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
        String params = "response_type=code&client_id=ga-disclosure-web&redirect_uri=/oidc-callback&code_challenge=" + challenge
                + "&code_challenge_method=S256&state=" + state;

        HttpResponse<byte[]> form = send("GET", "/demo/oidc/authorize?" + params, null);
        assertThat(form.statusCode()).isEqualTo(200);
        assertScreenHeaders(form);
        String html = new String(form.body(), StandardCharsets.UTF_8);
        assertThat(html).contains("value=\"" + T + "/compliance-1\"", "value=\"" + T + "/agent-1\"").doesNotContain("<script");
        assertThat(send("GET", "/demo/oidc/authorize?" + params.replace("S256", "plain"), null).statusCode()).isEqualTo(400);
        assertThat(send("GET", "/demo/oidc/authorize?" + params.replace("/oidc-callback", "https://evil.example/cb"), null).statusCode()).isEqualTo(400);
        assertThat(send("POST", "/demo/oidc/authorize", params + "&account=" + T + "/someone-else").statusCode()).as("not in the closed list")
                .isEqualTo(400);

        HttpResponse<byte[]> submitted = send("POST", "/demo/oidc/authorize", params + "&account=" + T + "/compliance-1");
        assertThat(submitted.statusCode()).isEqualTo(303);
        String location = header(submitted, "Location");
        assertThat(location).matches("^/oidc-callback\\?code=[A-Za-z0-9_-]{43}&state=" + state + "$");
        String code = location.substring(location.indexOf("code=") + 5, location.indexOf("&state"));

        String token = "grant_type=authorization_code&client_id=ga-disclosure-web&redirect_uri=/oidc-callback&code=" + code + "&code_verifier=";
        HttpResponse<byte[]> wrong = send("POST", "/demo/oidc/token", token + verifier.substring(1) + "x");
        assertThat(wrong.statusCode()).isEqualTo(400);
        assertThat(new String(wrong.body(), StandardCharsets.UTF_8)).isEqualTo("{\"error\":\"invalid_grant\"}");
        assertThat(send("POST", "/demo/oidc/token", token + verifier).statusCode()).as("the code was spent by the failed exchange").isEqualTo(400);

        HttpResponse<byte[]> again = send("POST", "/demo/oidc/authorize", params + "&account=" + T + "/compliance-1");
        String code2 = header(again, "Location").replaceAll("^.*code=([^&]+)&.*$", "$1");
        String exchange = token.replace("code=" + code, "code=" + code2) + verifier;
        HttpResponse<byte[]> issued = send("POST", "/demo/oidc/token", exchange);
        assertThat(issued.statusCode()).isEqualTo(200);
        assertThat(header(issued, "Cache-Control")).isEqualTo("no-store");
        JsonNode body = Canonicalizer.parseStrict(new String(issued.body(), StandardCharsets.UTF_8));
        Set<String> names = new TreeSet<>();
        body.propertyNames().forEach(names::add);
        assertThat(names).containsExactly("access_token", "expires_in", "token_type");
        assertThat(send("POST", "/demo/oidc/token", exchange).statusCode()).as("one use").isEqualTo(400);

        ApiTestSupport.Response jobs = ApiTestSupport.get(port, "/api/v1/jobs", body.get("access_token").asString());
        assertThat(jobs.status()).as(jobs.text()).isEqualTo(200);
    }

    /** 데모 로그인 라우트 = 데모 계약의 경로·메서드(양방향) — 함수형 라우터의 술어를 방문해 모은다. */
    @Test
    void theDemoRoutesMatchTheDemoContract() throws Exception {
        Set<String> routes = new TreeSet<>();
        for (RouterFunction<?> f : web.getBeansOfType(RouterFunction.class).values()) {
            f.accept(new RouterFunctions.Visitor() {
                @Override
                public void startNested(RequestPredicate predicate) {
                }

                @Override
                public void endNested(RequestPredicate predicate) {
                }

                @Override
                public void route(RequestPredicate predicate, HandlerFunction<?> handler) {
                    Matcher m = Pattern.compile("(GET|POST) && (/demo/[^ )]+)").matcher(predicate.toString());
                    if (m.find()) {
                        routes.add(m.group(1) + " " + m.group(2));
                    }
                }

                @Override
                public void resources(java.util.function.Function<ServerRequest, java.util.Optional<org.springframework.core.io.Resource>> lookup) {
                }

                @Override
                public void attributes(Map<String, Object> attributes) {
                }

                @Override
                public void unknown(RouterFunction<?> routerFunction) {
                }
            });
        }
        JsonNode contract = new YAMLMapper().readTree(Files.readString(ROOT.resolve("contracts/api/v1/demo-oidc.openapi.yaml")));
        Set<String> declared = new TreeSet<>();
        contract.get("paths").properties().forEach(p -> p.getValue().propertyNames().forEach(m -> declared.add(m.toUpperCase() + " " + p.getKey())));
        assertThat(routes).isNotEmpty().isEqualTo(declared);
    }
}
