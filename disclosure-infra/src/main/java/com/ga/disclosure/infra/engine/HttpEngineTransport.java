package com.ga.disclosure.infra.engine;

import com.ga.disclosure.workflow.disclosure.EngineUnavailableException;
import com.ga.platform.core.tenant.TenantId;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.Objects;

/**
 * JDK {@link HttpClient} 전송(추가 의존성 없음). 서비스 토큰은 {@link EngineCredentialPort}에서 받아 {@code Authorization: Bearer}로 싣고,
 * 토큰·본문은 로그·예외 메시지에 넣지 않는다.
 *
 * <p>재시도는 연결 실패({@link ConnectException}·연결 타임아웃)에만, 최대 {@link EngineClientSettings#maxAttempts()}회. 요청을 보낸 뒤의
 * 타임아웃({@code ENGINE_TIMEOUT})·그 밖의 입출력 오류·응답 수신(상태 코드 무관)은 재시도하지 않는다 — 산출 POST는 멱등이 아니다.
 */
public final class HttpEngineTransport implements EngineTransport {

    private final EngineClientSettings settings;
    private final EngineCredentialPort credentials;
    private final EngineEndpoints endpoints;
    private final HttpClient client;

    public HttpEngineTransport(EngineClientSettings settings, EngineCredentialPort credentials, EngineEndpoints endpoints) {
        this(settings, credentials, endpoints, HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    /** 테스트가 연결 실패를 주입할 수 있게 클라이언트를 받는다. */
    public HttpEngineTransport(EngineClientSettings settings, EngineCredentialPort credentials, EngineEndpoints endpoints, HttpClient client) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public Response post(TenantId tenant, String path, byte[] body) {
        return send(tenant, builder(tenant, path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
    }

    @Override
    public Response get(TenantId tenant, String path) {
        return send(tenant, builder(tenant, path).GET().build());
    }

    private HttpRequest.Builder builder(TenantId tenant, String path) {
        String token = credentials.tokenFor(tenant)
                .orElseThrow(() -> new EngineUnavailableException("ENGINE_NO_CREDENTIAL", null));
        URI base = endpoints.baseUrl(tenant);
        return HttpRequest.newBuilder(base.resolve(stripTrailingSlash(base.getPath()) + path))
                .timeout(settings.requestTimeout())
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token);
    }

    private static String stripTrailingSlash(String path) {
        return path == null ? "" : path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private Response send(TenantId tenant, HttpRequest request) {
        IOException lastConnectFailure = null;
        for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
            try {
                HttpResponse<byte[]> r = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                return new Response(r.statusCode(), r.body() == null ? new byte[0] : r.body());
            } catch (HttpConnectTimeoutException | ConnectException e) {
                lastConnectFailure = e;                                   // 요청이 엔진에 닿지 않았다 — 재시도 가능
            } catch (HttpTimeoutException e) {
                throw new EngineUnavailableException("ENGINE_TIMEOUT", e); // 보낸 뒤 — 재시도 금지
            } catch (IOException e) {
                throw new EngineUnavailableException("ENGINE_IO", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EngineUnavailableException("ENGINE_INTERRUPTED", e);
            }
        }
        throw new EngineUnavailableException("ENGINE_CONNECT", lastConnectFailure);
    }
}
