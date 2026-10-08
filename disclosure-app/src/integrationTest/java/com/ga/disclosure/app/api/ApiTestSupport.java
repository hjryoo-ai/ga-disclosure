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
    private static final Path KEK = kek();
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
        registry.add("ga.storage.s3.endpoint", s3::endpoint);
        registry.add("ga.storage.s3.bucket", s3::freshBucket);
        registry.add("ga.storage.s3.access-key-id", () -> SeaweedHarness.ACCESS_KEY);
        registry.add("ga.storage.s3.secret-access-key", () -> SeaweedHarness.SECRET_KEY);
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
            return new Response(r.statusCode(), headers(r.headers()), r.body());
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
