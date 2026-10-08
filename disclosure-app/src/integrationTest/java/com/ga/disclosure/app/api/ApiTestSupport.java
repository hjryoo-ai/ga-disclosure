package com.ga.disclosure.app.api;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeaweedHarness;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * HTTP 통합 시험 공통(6A): 앱을 웹으로 띄우는 속성(DB·작업 잠금·저장소·KEK·시험 JWT 공개키)과 요청 도우미. 응답 비교는 상태·헤더(Date 제외)·본문 바이트다.
 */
public final class ApiTestSupport {

    public static final PostgresHarness DB = PostgresHarness.get();
    /** 앱과 CLI가 같이 쓰는 시험 KEK 파일(고객 수입은 CLI, 봉인은 웹 — 같은 키여야 한다). */
    public static final Path KEK = kek();
    public static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));
    public static final Path DEMO = ROOT.resolve("disclosure-demo/src/main/resources");
    /** 커서 키 파일 경로(없는 파일 — 앱이 첫 기동에 소유자 전용으로 만든다). */
    public static final Path CURSOR_KEY = tempPath("ga-api-cursor", "cursor.key");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private ApiTestSupport() {
    }

    private static Path kek() {
        try {
            Path file = Files.createTempDirectory("ga-api-kek").resolve("kek.json");
            com.ga.disclosure.infra.crypto.LocalFileKeyProvider.initialize(file, "KEK-API-TEST");
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path tempPath(String prefix, String name) {
        try {
            return Files.createTempDirectory(prefix).resolve(name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void properties(DynamicPropertyRegistry registry) {
        SeaweedHarness s3 = SeaweedHarness.get();
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.flyway.url", DB::jdbcUrl);
        registry.add("ga.tenant-directory.url", DB::jdbcUrl);
        registry.add("ga.job-lock.url", DB::jdbcUrl);
        registry.add("ga.api.jwt.issuer", () -> TestJwts.ISSUER);
        registry.add("ga.api.jwt.audience", () -> TestJwts.AUDIENCE);
        registry.add("ga.api.jwt.public-key-location", TestJwts::publicKeyPem);
        registry.add("ga.crypto.local-kek-file", KEK::toString);
        registry.add("ga.api.cursor-key-file", CURSOR_KEY::toString);
        registry.add("ga.engine.mode", () -> "stub");
        registry.add("ga.engine.stub-table", () -> DEMO.resolve("demo/engine-table.json").toString());
        registry.add("ga.storage.s3.endpoint", s3::endpoint);
        registry.add("ga.storage.s3.bucket", s3::freshBucket);
        registry.add("ga.storage.s3.access-key-id", () -> SeaweedHarness.ACCESS_KEY);
        registry.add("ga.storage.s3.secret-access-key", () -> SeaweedHarness.SECRET_KEY);
    }

    /**
     * GLOBAL 룰(표준 번들 {@code DISC-2026-07}, 또는 본문을 {@code edit}로 고친 변형)을 배포하고 오늘 기준으로 활성화한다. 변형은 새 룰 버전 ID
     * ({@code variantIdOrNull})다 — 같은 ID로 내용이 다른 번들은 없다(번들 계약). 같은 ID를 쓰면 같은 컨테이너의 {@code --tenants all} 배포(OperatorCliIT)가
     * 그 테넌트에서 충돌로 거부된다.
     */
    public static void activateRules(com.ga.disclosure.compliance.rules.RuleDistributionService distribution,
                                     com.ga.disclosure.compliance.rules.RuleActivationJob activation, String tenant, String variantIdOrNull,
                                     java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> edit) {
        Path root = Path.of(System.getProperty("ga.repoRoot"));
        tools.jackson.databind.node.ObjectNode bundle;
        try {
            bundle = (tools.jackson.databind.node.ObjectNode) com.ga.platform.canonical.Canonicalizer.parseStrict(
                    Files.readString(root.resolve("contracts/rules/bundles/rules/DISC-2026-07.bundle.json")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        tools.jackson.databind.node.ObjectNode body = (tools.jackson.databind.node.ObjectNode) bundle.get("body");
        edit.accept(body);
        if (variantIdOrNull != null) {
            bundle.put("ruleVersionId", variantIdOrNull);
        }
        bundle.put("bundleId", bundle.get("ruleVersionId").asString() + "@"
                + com.ga.platform.canonical.Sha256.of(com.ga.platform.canonical.Canonicalizer.canonicalize(body)).substring(0, 12));
        com.ga.disclosure.compliance.rules.Operator operator = new com.ga.disclosure.compliance.rules.Operator("api-it");
        com.ga.platform.core.tenant.TenantId id = com.ga.platform.core.tenant.TenantId.of(tenant);
        distribution.distribute(com.ga.disclosure.rules.bundle.BundleLoader.parse("variant", bundle.toString()), id, operator);
        activation.run(id, operator);
    }

    /**
     * 운영자 CLI를 같은 프로세스에서 한 번 실행하고 표준 출력을 돌려준다(시험 테넌트 준비 — 번들·카탈로그·가상 고객). 고객 정보는 파일로만 넘긴다(절대 규칙 6).
     */
    public static String cli(String... args) {
        java.util.List<String> all = new java.util.ArrayList<>(java.util.List.of(
                "--spring.profiles.active=cli",
                "--spring.datasource.url=" + DB.jdbcUrl(),
                "--spring.flyway.url=" + DB.jdbcUrl(),
                "--ga.tenant-directory.url=" + DB.jdbcUrl(),
                "--ga.job-lock.url=" + DB.jdbcUrl(),
                "--ga.crypto.local-kek-file=" + KEK));
        all.addAll(java.util.List.of(args));
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            new org.springframework.boot.builder.SpringApplicationBuilder(com.ga.disclosure.app.DisclosureApplication.class)
                    .run(all.toArray(String[]::new)).close();
            return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            System.setOut(original);
        }
    }

    /** 응답 한 건(헤더는 소문자 이름 정렬, Date 제외 비교용). */
    public record Response(int status, Map<String, String> headers, byte[] body) {

        public String text() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }

        /** 비교용: 상태·헤더(Date 제외)·본문. */
        public String fingerprint() {
            Map<String, String> h = new TreeMap<>(headers);
            h.remove("date");
            return status + "\n" + h + "\n" + text();
        }
    }

    public static Response get(int port, String path, String tokenOrNull) {
        return send(port, "GET", path, tokenOrNull, null, Map.of());
    }

    public static Response post(int port, String path, String tokenOrNull, String jsonOrNull, Map<String, String> headers) {
        return send(port, "POST", path, tokenOrNull, jsonOrNull, headers);
    }

    public static Response send(int port, String method, String path, String tokenOrNull, String jsonOrNull, Map<String, String> headers) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (tokenOrNull != null) {
            b.header("Authorization", "Bearer " + tokenOrNull);
        }
        headers.forEach(b::header);
        if (jsonOrNull != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonOrNull));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            HttpResponse<byte[]> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            Response response = new Response(r.statusCode(), headers(r.headers()), r.body());
            // G11: 모든 IT 응답이 계약 스키마를 통과한다(계약에 없는 상태·미디어 타입도 위반)
            java.util.List<String> violations = ApiContracts.get().violations(method, path, response.status(), response.headers().get("content-type"),
                    response.body());
            if (!violations.isEmpty()) {
                throw new AssertionError("response breaks the OpenAPI contract: " + violations);
            }
            return response;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> headers(HttpHeaders h) {
        return h.map().entrySet().stream().collect(Collectors.toMap(e -> e.getKey().toLowerCase(java.util.Locale.ROOT),
                e -> String.join(",", e.getValue()), (a, b) -> a, TreeMap::new));
    }
}
