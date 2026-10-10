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
    /** 앱과 CLI가 같이 쓰는 시험 비밀 디렉터리(테넌트 KEK — 시드 테넌트마다 {@code {T}-KEK-1}, 고객 수입은 CLI·봉인은 웹이라 같은 키여야 한다). */
    public static final Path SECRETS = com.ga.disclosure.infra.testing.TestKeks.shared().dir();
    public static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));
    public static final Path DEMO = ROOT.resolve("disclosure-demo/src/main/resources");
    static {
        // Phase 8: 앱은 비밀을 만들지 않는다 — 웹 앱의 커서·요청 해시·영수증 키를 시험 비밀 디렉터리에 먼저 둔다(secrets init과 같은 이름·형식)
        for (String name : java.util.List.of("api/cursor", "api/request-hash", "api/customer-receipt")) {
            com.ga.disclosure.infra.testing.TestKeks.shared().ensureKey(com.ga.disclosure.workflow.secret.SecretName.of(name));
        }
    }
    /** 웹 앱이 읽는 키 파일(시험이 HMAC을 다시 계산할 때 — 비밀 디렉터리 안). */
    public static final Path CURSOR_KEY = SECRETS.resolve("api/cursor");
    public static final Path REQUEST_HASH_KEY = SECRETS.resolve("api/request-hash");
    public static final Path RECEIPT_KEY = SECRETS.resolve("api/customer-receipt");

    private ApiTestSupport() {
    }

    private static Path tempPath(String prefix, String name) {
        try {
            return Files.createTempDirectory(prefix).resolve(name);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void properties(DynamicPropertyRegistry registry) {
        propertyMap().forEach(registry::add);
    }

    /** 웹 앱 시험 설정(이름 → 값 공급자). {@link #properties}와 직접 기동하는 시험(기동 실패 단언)이 같이 쓴다. */
    public static java.util.Map<String, java.util.function.Supplier<Object>> propertyMap() {
        SeaweedHarness s3 = SeaweedHarness.get();
        java.util.Map<String, java.util.function.Supplier<Object>> p = new java.util.LinkedHashMap<>();
        p.put("spring.datasource.url", DB::jdbcUrl);
        p.put("ga.health.url", DB::jdbcUrl);
        // Phase 8: 액추에이터는 관리 포트에만 — 시험 컨텍스트가 여럿 캐시되므로 임의 포트
        p.put("management.server.port", () -> "0");
        p.put("ga.internal.port", () -> "0");
        // 웹 IT 클래스마다 컨텍스트가 캐시에 남고 각자 풀을 쥔다 — 기본 풀(최소 유휴 = 최대 10)이면 컨텍스트 14개에서 서버 연결 100개를 넘는다(53300)
        p.put("spring.datasource.hikari.maximum-pool-size", () -> "5");
        p.put("spring.datasource.hikari.minimum-idle", () -> "1");
        p.put("spring.datasource.hikari.idle-timeout", () -> "10000");
        p.put("ga.tenant-directory.url", DB::jdbcUrl);
        p.put("ga.job-lock.url", DB::jdbcUrl);
        p.put("ga.api.jwt.issuer", () -> TestJwts.ISSUER);
        p.put("ga.api.jwt.audience", () -> TestJwts.AUDIENCE);
        p.put("ga.api.jwt.public-key-location", TestJwts::publicKeyPem);
        p.put("ga.secrets.dir", SECRETS::toString);
        p.put("ga.public-sign.min-response-millis", () -> "30");
        p.put("ga.engine.mode", () -> "stub");
        p.put("ga.engine.stub-table", () -> DEMO.resolve("demo/engine-table.json").toString());
        p.put("ga.storage.s3.endpoint", s3::endpoint);
        p.put("ga.storage.s3.bucket", s3::freshBucket);
        p.put("ga.storage.s3.access-key-id", () -> SeaweedHarness.ACCESS_KEY);
        p.put("ga.storage.s3.secret-access-key", () -> SeaweedHarness.SECRET_KEY);
        return p;
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
                "--ga.health.url=" + DB.jdbcUrl(),
                "--ga.tenant-directory.url=" + DB.jdbcUrl(),
                "--ga.job-lock.url=" + DB.jdbcUrl(),
                "--ga.secrets.dir=" + SECRETS));
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
            com.ga.disclosure.app.CliOutputScan.assertClean(buffer.toString(java.nio.charset.StandardCharsets.UTF_8));
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

    /** 누출 스캔(6A 계획 §9.3)이 그동안의 모든 응답을 {경로, 응답}으로 받아 본다. 평소에는 {@code null}. */
    static volatile java.util.function.BiConsumer<String, Response> observer;

    public static Response get(int port, String path, String tokenOrNull) {
        return send(port, "GET", path, tokenOrNull, null, Map.of());
    }

    public static Response post(int port, String path, String tokenOrNull, String jsonOrNull, Map<String, String> headers) {
        return send(port, "POST", path, tokenOrNull, jsonOrNull, headers);
    }

    /**
     * 앱 포트 → 그 앱의 내부 포트(Phase 8 Q5 — {@code /internal/**}은 내부 포트에만 있다). 시험 수신기 {@code InternalPortRecorder}가 기동 때 채운다.
     * {@link #send}는 {@code /internal} 경로를 같은 앱의 내부 포트로 보낸다(클러스터 안 진입점 C가 하는 일) — 포트를 그대로 쓰려면 {@link #sendExact}.
     */
    public static final java.util.Map<Integer, Integer> INTERNAL_PORTS = new java.util.concurrent.ConcurrentHashMap<>();

    public static Response send(int port, String method, String path, String tokenOrNull, String jsonOrNull, Map<String, String> headers) {
        int target = path.startsWith("/internal/") || path.equals("/internal") ? INTERNAL_PORTS.getOrDefault(port, port) : port;
        return sendExact(target, method, path, tokenOrNull, jsonOrNull, headers);
    }

    /** 주어진 포트로 그대로 보낸다(포트 분리 시험). */
    public static Response sendExact(int port, String method, String path, String tokenOrNull, String jsonOrNull, Map<String, String> headers) {
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
            // 요청마다 새 클라이언트(닫는다): 공유 클라이언트의 연결 풀은 host:port로 묶여, 앞 시험 클래스의 컨텍스트가 닫힌 뒤 같은 임의 포트를 받은
            // 새 서버에 죽은 연결을 내줄 수 있었다(전체 실행에서만 "header parser received no bytes") — 서버보다 오래 사는 연결을 두지 않는다
            HttpResponse<byte[]> r;
            try (HttpClient http = HttpClient.newHttpClient()) {
                r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            }
            Response response = new Response(r.statusCode(), headers(r.headers()), r.body());
            // G11: 모든 IT 응답이 계약 스키마를 통과한다(계약에 없는 상태·미디어 타입도 위반)
            java.util.List<String> violations = ApiContracts.get().violations(method, path, response.status(), response.headers().get("content-type"),
                    response.body());
            if (!violations.isEmpty()) {
                throw new AssertionError("response breaks the OpenAPI contract: " + violations);
            }
            java.util.function.BiConsumer<String, Response> o = observer;
            if (o != null) {
                o.accept(path, response);
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
