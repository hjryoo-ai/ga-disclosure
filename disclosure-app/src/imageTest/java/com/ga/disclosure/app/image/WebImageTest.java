package com.ga.disclosure.app.image;

import com.ga.disclosure.app.demo.DemoWebConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 웹 이미지(Phase 8 ③): 데모 프로파일의 화면 서빙({@link DemoWebConfiguration})을 운영에서 대신하므로 경로·헤더가 그 구성과 같은 문자열이다 — CSP는 그
 * 상수, 자산 캐시는 같은 {@link CacheControl} 표현의 렌더링. 비루트(UID 101)로 돈다. 헤더 기대값을 이 시험에 다시 적지 않는다(드리프트 방지).
 */
class WebImageTest {

    static GenericContainer<?> web;
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeAll
    static void start() {
        web = new GenericContainer<>(DockerImageName.parse(Docker.WEB)).withExposedPorts(8080)
                .withCreateContainerCmdModifier(c -> c.getHostConfig().withReadonlyRootfs(true))
                .withTmpFs(java.util.Map.of("/tmp", "rw"))
                .waitingFor(Wait.forHttp("/staff").forStatusCode(200));
        web.start();
    }

    @AfterAll
    static void stop() {
        web.stop();
    }

    static HttpResponse<String> send(String method, String path) throws Exception {
        URI uri = URI.create("http://" + web.getHost() + ":" + web.getMappedPort(8080) + path);
        return HTTP.send(HttpRequest.newBuilder(uri).method(method, HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static void securityHeaders(HttpResponse<String> r, String what) {
        assertThat(r.headers().firstValue("content-security-policy")).as(what).hasValue(DemoWebConfiguration.CSP);
        assertThat(r.headers().firstValue("referrer-policy")).as(what).hasValue("no-referrer");
        assertThat(r.headers().firstValue("x-content-type-options")).as(what).hasValue("nosniff");
        assertThat(r.headers().firstValue("x-frame-options")).as(what).hasValue("DENY");
        assertThat(r.headers().firstValue("server")).as(what).hasValue("nginx");             // 버전 숨김(server_tokens off)
    }

    @Test
    void screensAreTheBuiltDocumentsWithTheDemoServingHeaders() throws Exception {
        Path dist = Docker.repoRoot().resolve("disclosure-web/build/web/dist");
        String staff = Files.readString(dist.resolve("staff/index.html"));
        String sign = Files.readString(dist.resolve("sign/index.html"));
        String callback = Files.readString(dist.resolve("oidc-callback/index.html"));
        for (String[] c : new String[][] {{"/", staff}, {"/staff", staff}, {"/staff/disclosures/x", staff}, {"/s", sign}, {"/oidc-callback", callback}}) {
            HttpResponse<String> r = send("GET", c[0]);
            assertThat(r.statusCode()).as(c[0]).isEqualTo(200);
            assertThat(r.body()).as(c[0]).isEqualTo(c[1]);
            assertThat(r.headers().firstValue("cache-control")).as(c[0]).hasValue(CacheControl.noStore().getHeaderValue());
            assertThat(r.headers().firstValue("content-type")).as(c[0]).hasValue("text/html;charset=UTF-8");
            securityHeaders(r, c[0]);
        }
        // 자산 전수: 확장자마다 정해진 형식(nosniff라 틀리면 브라우저가 거부한다 — PDF.js 모듈 워커 .mjs가 octet-stream이었다, 11단계 kind E2E). 표 밖
        // 확장자가 빌드에 생기면 실패한다(형식을 정하고 nginx 표에 더할 것)
        java.util.Map<String, String> types = java.util.Map.of("js", "text/javascript", "mjs", "text/javascript", "css", "text/css", "ttf", "font/ttf");
        List<Path> assets;
        try (Stream<Path> files = Files.list(dist.resolve("assets"))) {
            assets = files.sorted().toList();
        }
        assertThat(assets).isNotEmpty();
        for (Path p : assets) {
            String name = p.getFileName().toString();
            String ext = name.substring(name.lastIndexOf('.') + 1);
            assertThat(types).as("asset type for ." + ext).containsKey(ext);
            HttpResponse<String> a = send("GET", "/assets/" + name);
            assertThat(a.statusCode()).as(name).isEqualTo(200);
            assertThat(a.headers().firstValue("content-type")).as(name).hasValue(types.get(ext));
            assertThat(a.headers().firstValue("cache-control")).as(name)
                    .hasValue(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable().getHeaderValue());
            securityHeaders(a, name);
        }
    }

    @Test
    void everythingElseIsA404AndTheServerRunsAsNonRoot() throws Exception {
        for (String[] c : new String[][] {{"GET", "/api/v1/disclosures"}, {"GET", "/internal/v1/jobs"}, {"GET", "/sign/index.html"}, {"GET", "/assets/"},
                {"GET", "/assets/a/b.js"}, {"GET", "/actuator/health"}, {"POST", "/staff"}, {"DELETE", "/s"}, {"GET", "/demo/oidc/authorize"}}) {
            assertThat(send(c[0], c[1]).statusCode()).as(c[0] + " " + c[1]).isEqualTo(404);
        }
        assertThat(web.execInContainer("id", "-u").getStdout().trim()).isEqualTo("101");
    }
}
