package com.ga.disclosure.api.security;

import com.ga.disclosure.sign.token.SignToken;
import com.ga.disclosure.workflow.authz.TenantRegistry;
import com.ga.disclosure.workflow.sign.PublicSignLimits;
import com.ga.platform.canonical.Canonicalizer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 고객 공개 서명 경로의 문(6A 계획 §5.1~§5.4, G5·G7). 공개 체인에 하나, {@code HeaderWriterFilter} 뒤:
 * <ol>
 *   <li>시작 시각(주입 시계).</li>
 *   <li>거부 전처리 — 질의 문자열이 있으면, 알려진 다섯 경로가 아니면(토큰을 경로로 보낸 경우 포함), POST가 아니면, 토큰(헤더 {@code X-Sign-Token} 우선,
 *       없으면 JSON 본문 {@code token})이 없거나 형식이 틀리면, 토큰 접두 테넌트가 없으면 거부.</li>
 *   <li>그 테넌트의 분당 한도(룰) 초과면 거부.</li>
 *   <li>핸들러(Phase 4 유스케이스). 토큰 거부와 그 밖의 모든 예외는 거부로 — 업무 거부(유효 토큰 보유자)만 422.</li>
 *   <li>응답 버퍼링 → 패딩({@link ResponsePadding}, 모든 응답이 한 번) → 내보내기.</li>
 * </ol>
 * 거부는 언제나 같은 바이트다: 404 + 상수 본문 {@code SIGN_LINK_UNAVAILABLE}(헤더는 체인이 같으므로 같다). 로그에는 예외 클래스 이름만(토큰 없음).
 */
public final class PublicSignGate extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Sign-Token";
    /** 컨트롤러 인자 {@code PublicToken}이 읽는 요청 속성. */
    public static final String TOKEN_ATTRIBUTE = PublicSignGate.class.getName() + ".token";
    /** 공개 advice가 "거부로 바꿔라"를 알리는 요청 속성. */
    public static final String REJECT_ATTRIBUTE = PublicSignGate.class.getName() + ".reject";
    static final Set<String> PATHS = Set.of("/public/v1/sign/open", "/public/v1/sign/view", "/public/v1/sign/verify-identity",
            "/public/v1/sign/capture", "/public/v1/sign/status");
    static final int MAX_BODY_BYTES = 4 * 1024 * 1024;
    static final byte[] REJECTION = Canonicalizer.canonicalize(JsonMapper.builder().build().createObjectNode().put("code", "SIGN_LINK_UNAVAILABLE")
            .put("message", "This signing link cannot be used.").set("details", JsonMapper.builder().build().createObjectNode()));
    private static final System.Logger LOG = System.getLogger(PublicSignGate.class.getName());
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Clock clock;
    private final TenantRegistry tenants;
    private final PublicSignLimits limits;
    private final RateWindow rates;
    private final ResponsePadding padding;

    public PublicSignGate(Clock clock, TenantRegistry tenants, PublicSignLimits limits, RateWindow rates, ResponsePadding padding) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.rates = Objects.requireNonNull(rates, "rates");
        this.padding = Objects.requireNonNull(padding, "padding");
    }

    private record Admitted(HttpServletRequest request, String token) {
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException {
        Instant start = clock.instant();
        ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
        try {
            Optional<Admitted> admitted = admit(request);
            if (admitted.isEmpty()) {
                reject(buffered);
            } else {
                try {
                    admitted.get().request().setAttribute(TOKEN_ATTRIBUTE, admitted.get().token());
                    chain.doFilter(admitted.get().request(), buffered);
                    if (admitted.get().request().getAttribute(REJECT_ATTRIBUTE) != null) {
                        reject(buffered);
                    }
                } catch (Exception e) {
                    LOG.log(System.Logger.Level.INFO, "PUBLIC_SIGN_REJECTED " + e.getClass().getSimpleName());
                    reject(buffered);
                }
            }
        } finally {
            padding.pad(start);
            buffered.copyBodyToResponse();
        }
    }

    private Optional<Admitted> admit(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null || !"POST".equals(request.getMethod())
                || !PATHS.contains(request.getRequestURI().substring(request.getContextPath().length()))) {
            return Optional.empty();
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            return Optional.empty();
        }
        String raw = request.getHeader(TOKEN_HEADER);
        if (raw == null && body.length > 0) {
            try {
                JsonNode node = Canonicalizer.parseStrict(new String(body, StandardCharsets.UTF_8));
                raw = node.path("token").isString() ? node.get("token").asString() : null;
            } catch (RuntimeException notJson) {
                return Optional.empty();
            }
        }
        SignToken token;
        try {
            token = SignToken.parse(raw);
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
        // 없는 테넌트는 카운터를 거치지 않는다(맵을 키울 수 없다). 거부 지점이 달라도 패딩이 전체 응답을 덮는다(B2)
        if (!tenants.exists(token.tenant()) || !rates.admit(token.tenant(), limits.perMinute(token.tenant()), clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(new Admitted(new BufferedBodyRequest(request, body), raw));
    }

    private static void reject(ContentCachingResponseWrapper response) {
        response.reset();   // 핸들러가 남긴 상태·헤더·본문을 지운다(체인 헤더는 커밋 때 쓰이므로 모든 거부에 같다)
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(REJECTION.length);
        try {
            response.getOutputStream().write(REJECTION);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
