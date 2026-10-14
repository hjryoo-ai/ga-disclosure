package com.ga.disclosure.app.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * IdP JWKS 도달(Phase 8 준비성): {@code ga.api.jwt.jwk-set-uri}가 있으면 GET 200 + {@code "keys"} 본문이어야 UP. 없으면(정적 공개키 —
 * {@code public-key-location}, 데모·시험) 기동 때 이미 읽었으므로 UP({@code mode=static-key}). 상세에 URI·응답 본문을 싣지 않는다.
 */
public final class IdpJwksHealthIndicator implements HealthIndicator {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final Optional<URI> jwkSetUri;
    private final HttpClient http;

    public IdpJwksHealthIndicator(Optional<URI> jwkSetUri) {
        this.jwkSetUri = jwkSetUri;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Health health() {
        if (jwkSetUri.isEmpty()) {
            return Health.up().withDetail("mode", "static-key").build();
        }
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(jwkSetUri.get()).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() == 200 && response.body().contains("\"keys\"");
            return (ok ? Health.up() : Health.down()).withDetail("mode", "jwks").withDetail("status", response.statusCode()).build();
        } catch (IOException e) {
            return Health.down().withDetail("mode", "jwks").withDetail("error", e.getClass().getSimpleName()).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Health.down().withDetail("mode", "jwks").withDetail("error", "interrupted").build();
        }
    }
}
