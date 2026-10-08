package com.ga.disclosure.api.idempotency;

import com.ga.disclosure.api.error.Problem;
import com.ga.disclosure.api.security.IdempotencyCaptureFilter;
import com.ga.disclosure.api.security.TenantBindingFilter;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.idempotency.IdempotencyService;
import com.ga.disclosure.workflow.idempotency.RequestHashPort;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 쓰기 POST({@code /api}·{@code /internal})의 Idempotency-Key(6A 계획 §4.2, 승인 Q4). 라우트가 정해진 뒤(핸들러 메서드)에만 돈다 — 없는 라우트는 키와
 * 무관하게 404다.
 * <ol>
 *   <li>키 없음 428 {@code IDEMPOTENCY_KEY_REQUIRED}, 형식 오류 400. 본문이 JSON이 아니면 400(청구 전).</li>
 *   <li>요청 해시 = HMAC-SHA256(서버 키, JCS{@code {method, routeTemplate, pathVariables, body}}) — 원문은 저장하지 않는다. 키 없는 SHA-256은 정의역이 작은
 *       본문(고객 등록)을 사전 대입으로 되돌린다(6A 결함, 6B 계획 §A-2).</li>
 *   <li>청구(별도 트랜잭션): 진행 → 컨트롤러, 재생 → 저장 튜플로 바이트를 다시 만들어 응답 해시와 대조한 뒤 보낸다(다르면 500, 다른 본문을 내지 않는다),
 *       다른 요청 422 {@code IDEMPOTENCY_KEY_REUSED}, 진행 중 409 {@code IDEMPOTENCY_IN_PROGRESS}.</li>
 *   <li>완료(별도 트랜잭션): 2xx·409·422만 — 영수증 튜플 {@code {body, location?}}와 응답 바이트 해시. 그 밖(400·401·404·5xx)은 저장하지 않고 청구를
 *       해제한다 — 유스케이스에 닿은 요청만 키를 묶는다(6A 수용심사 §2 ②). 같은 키로 고친 요청은 새로 청구한다.</li>
 * </ol>
 * 재생 응답에는 {@code Idempotency-Replayed: true}를 붙인다(본문 바이트는 처음과 같다). 2xx 응답이 {@code Cache-Control: no-store}(일회용 자격 — 현장
 * 기기 토큰)이면 저장하지 않고 409 {@code IDEMPOTENCY_NOT_REPLAYABLE}을 완료로 남긴다 — 같은 키의 재요청은 그 409이고 효과는 한 번이다.
 */
public final class IdempotencyInterceptor implements HandlerInterceptor {

    public static final String REPLAYED_HEADER = "Idempotency-Replayed";
    private static final String PENDING = IdempotencyInterceptor.class.getName() + ".pending";
    private static final System.Logger LOG = System.getLogger(IdempotencyInterceptor.class.getName());
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final IdempotencyService service;
    private final RequestHashPort requestHashes;

    public IdempotencyInterceptor(IdempotencyService service, RequestHashPort requestHashes) {
        this.service = Objects.requireNonNull(service, "service");
        this.requestHashes = Objects.requireNonNull(requestHashes, "requestHashes");
    }

    private record Pending(Caller caller, String key, int claimSeq) {
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod) || !"POST".equals(request.getMethod())
                || !(request.getAttribute(TenantBindingFilter.CALLER) instanceof Caller caller)) {
            return true;
        }
        if (!(request.getAttribute(IdempotencyCaptureFilter.KEY_ATTRIBUTE) instanceof String key)) {
            return problem(response, HttpStatus.PRECONDITION_REQUIRED.value(), Problem.body("IDEMPOTENCY_KEY_REQUIRED", JSON.createObjectNode()));
        }
        if (!IdempotencyService.KEY.matcher(key).matches()) {
            return problem(response, HttpServletResponse.SC_BAD_REQUEST,
                    Problem.body("MALFORMED_REQUEST", JSON.createObjectNode().put("field", IdempotencyCaptureFilter.HEADER)));
        }
        byte[] body = (byte[]) request.getAttribute(IdempotencyCaptureFilter.BODY_ATTRIBUTE);
        JsonNode bodyNode;
        try {
            bodyNode = body == null || body.length == 0 ? NullNode.getInstance() : Canonicalizer.parseStrict(new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException notJson) {
            return problem(response, HttpServletResponse.SC_BAD_REQUEST, Problem.body("MALFORMED_REQUEST", JSON.createObjectNode().put("field", "body")));
        }
        ObjectNode input = JSON.createObjectNode().put("method", request.getMethod())
                .put("routeTemplate", (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE));
        ObjectNode variables = input.putObject("pathVariables");
        new TreeMap<>(pathVariables(request)).forEach(variables::put);
        input.set("body", bodyNode);
        String requestHash = requestHashes.hash(Canonicalizer.canonicalize(input));
        return switch (service.claim(caller, key, requestHash)) {
            case IdempotencyService.Claim.Proceed p -> {
                request.setAttribute(PENDING, new Pending(caller, key, p.claimSeq()));
                yield true;
            }
            case IdempotencyService.Claim.Replay r -> replay(response, r);
            case IdempotencyService.Claim.Reused r ->
                    problem(response, HttpStatus.UNPROCESSABLE_CONTENT.value(), Problem.body("IDEMPOTENCY_KEY_REUSED", JSON.createObjectNode()));
            case IdempotencyService.Claim.InProgress p ->
                    problem(response, HttpServletResponse.SC_CONFLICT, Problem.body("IDEMPOTENCY_IN_PROGRESS", JSON.createObjectNode()));
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        Object v = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return v instanceof Map<?, ?> m ? (Map<String, String>) m : Map.of();
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!(request.getAttribute(PENDING) instanceof Pending pending)) {
            return;
        }
        if (!IdempotencyService.storable(response.getStatus())) {
            try {
                service.release(pending.caller(), pending.key(), pending.claimSeq());
            } catch (RuntimeException e) {
                // 해제 실패는 응답을 바꾸지 않는다 — 행은 임차가 지나면 같은 요청이 인수하고, 만료 뒤 정리된다
                LOG.log(System.Logger.Level.ERROR, "IDEMPOTENCY_RELEASE_FAILED " + e.getClass().getSimpleName());
            }
            return;
        }
        String cacheControl = response.getHeader(HttpHeaders.CACHE_CONTROL);
        if (response.getStatus() < 300 && cacheControl != null && cacheControl.contains("no-store")) {
            // 일회용 자격(현장 기기 토큰)을 담은 응답 — 저장하지 않고 "재생하지 않음"을 완료로 남긴다(같은 키의 재요청은 이 409, 효과 1회)
            byte[] notReplayable = Problem.body("IDEMPOTENCY_NOT_REPLAYABLE", JSON.createObjectNode());
            ObjectNode ref = JSON.createObjectNode();
            ref.set("body", Canonicalizer.parseStrict(new String(notReplayable, StandardCharsets.UTF_8)));
            try {
                service.complete(pending.caller(), pending.key(), pending.claimSeq(), HttpServletResponse.SC_CONFLICT,
                        new String(Canonicalizer.canonicalize(ref), StandardCharsets.UTF_8), Sha256.of(notReplayable));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR, "IDEMPOTENCY_COMPLETE_FAILED " + e.getClass().getSimpleName());
            }
            return;
        }
        ContentCachingResponseWrapper buffered = WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
        if (buffered == null) {
            LOG.log(System.Logger.Level.ERROR, "IDEMPOTENCY_NOT_BUFFERED");
            return;
        }
        byte[] bytes = buffered.getContentAsByteArray();
        try {
            JsonNode body = Canonicalizer.parseStrict(new String(bytes, StandardCharsets.UTF_8));
            if (!Arrays.equals(Canonicalizer.canonicalize(body), bytes)) {
                // 재생이 같은 바이트를 만들 수 없다 — 저장하지 않는다(임차가 지나면 같은 요청이 인수한다)
                LOG.log(System.Logger.Level.ERROR, "IDEMPOTENCY_NON_CANONICAL_RESPONSE");
                return;
            }
            ObjectNode ref = JSON.createObjectNode();
            ref.set("body", body);
            String location = response.getHeader(HttpHeaders.LOCATION);
            if (location != null) {
                ref.put("location", location);
            }
            String refText = new String(Canonicalizer.canonicalize(ref), StandardCharsets.UTF_8);
            if (!service.complete(pending.caller(), pending.key(), pending.claimSeq(), response.getStatus(), refText, Sha256.of(bytes))) {
                LOG.log(System.Logger.Level.WARNING, "IDEMPOTENCY_COMPLETE_SUPERSEDED");
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "IDEMPOTENCY_COMPLETE_FAILED " + e.getClass().getSimpleName());
        }
    }

    private static boolean replay(HttpServletResponse response, IdempotencyService.Claim.Replay r) throws IOException {
        JsonNode ref = Canonicalizer.parseStrict(r.responseRef());
        byte[] bytes = Canonicalizer.canonicalize(ref.get("body"));
        if (!Sha256.of(bytes).equals(r.responseHash())) {
            LOG.log(System.Logger.Level.ERROR, "IDEMPOTENCY_REPLAY_MISMATCH");
            return problem(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, Problem.body("INTERNAL_ERROR", JSON.createObjectNode()));
        }
        if (ref.hasNonNull("location")) {
            response.setHeader(HttpHeaders.LOCATION, ref.get("location").asString());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        response.setStatus(r.status());
        return write(response, bytes);
    }

    private static boolean problem(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        return write(response, body);
    }

    private static boolean write(HttpServletResponse response, byte[] body) throws IOException {
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        return false;
    }
}
