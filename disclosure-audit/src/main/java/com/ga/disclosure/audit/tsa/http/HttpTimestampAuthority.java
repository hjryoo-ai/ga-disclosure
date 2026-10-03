package com.ga.disclosure.audit.tsa.http;

import com.ga.disclosure.audit.tsa.TimestampAuthorityPort;
import com.ga.disclosure.audit.tsa.TimestampFailure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

/**
 * RFC 3161 over HTTP(§3.4 of RFC 3161): {@code POST application/timestamp-query} → {@code application/timestamp-reply}. 응답 수락·
 * 검증은 {@link com.ga.disclosure.audit.tsa.TimestampClient}가 한다. 연결 실패·시간 초과·비 200은 {@code UNAVAILABLE}.
 */
public final class HttpTimestampAuthority implements TimestampAuthorityPort {

    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;

    public HttpTimestampAuthority(URI endpoint, Duration timeout) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public byte[] exchange(byte[] timeStampRequestDer) {
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/timestamp-query")
                .header("Accept", "application/timestamp-reply")
                .POST(HttpRequest.BodyPublishers.ofByteArray(timeStampRequestDer))
                .build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new TimestampFailure(TimestampFailure.Kind.UNAVAILABLE, "HTTP_" + response.statusCode());
            }
            return response.body();
        } catch (IOException e) {
            throw new TimestampFailure(TimestampFailure.Kind.UNAVAILABLE, "TRANSPORT", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TimestampFailure(TimestampFailure.Kind.UNAVAILABLE, "INTERRUPTED", e);
        }
    }
}
