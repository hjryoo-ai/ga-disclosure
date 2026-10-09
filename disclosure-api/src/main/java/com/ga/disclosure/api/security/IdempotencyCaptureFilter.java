package com.ga.disclosure.api.security;

import com.ga.disclosure.api.error.Problem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 쓰기(POST) 요청의 멱등 재료를 잡는다(6A 계획 §4.2) — 원 헤더 읽기는 {@code api.security}만 한다(ApiLayerRulesTest (e)):
 * <ul>
 *   <li>{@code Idempotency-Key} 헤더와 본문 바이트를 요청 속성에 둔다(본문은 메모리에 한 번 읽어 컨트롤러에 다시 준다 — 요청 해시가 본문을 덮는다).</li>
 *   <li>응답을 버퍼에 받는다 — 인터셉터가 완료 때 응답 바이트의 해시와 영수증 튜플을 기록한다. 체인이 끝나면 버퍼를 실제 응답으로 보낸다.</li>
 * </ul>
 * 상한은 종이 스캔(원본 20 MiB, base64로 약 27 MiB)을 담는 크기다. 본문이 상한을 넘으면 400 {@code MALFORMED_REQUEST}({@code field: body}).
 * 라우트별로 더 작은 상한이 있다({@link #ROUTE_LIMITS} — 6B §9.2 고객 등록 4 KiB). 디스패치 전에 판정해야 하므로 경로는 mTLS 필터와 같은
 * {@code PathPatternRequestMatcher}(MVC와 같은 파서)로 고른다 — 원 URI 비교가 아니다(8단계 보안 회신 ①).
 */
public final class IdempotencyCaptureFilter extends OncePerRequestFilter {

    public static final String KEY_ATTRIBUTE = IdempotencyCaptureFilter.class.getName() + ".key";
    public static final String BODY_ATTRIBUTE = IdempotencyCaptureFilter.class.getName() + ".body";
    public static final String HEADER = "Idempotency-Key";
    static final int MAX_BODY_BYTES = 32 * 1024 * 1024;
    /** 라우트별 본문 상한(닫힌 표) — 개인정보를 받는 경로는 작게. */
    static final Map<String, Integer> ROUTE_LIMITS = Map.of("/api/v1/customers", 4 * 1024);
    private static final List<Map.Entry<RequestMatcher, Integer>> LIMITS = ROUTE_LIMITS.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(e -> Map.entry((RequestMatcher) PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, e.getKey()), e.getValue())).toList();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int limit = LIMITS.stream().filter(e -> e.getKey().matches(request)).mapToInt(Map.Entry::getValue).min().orElse(MAX_BODY_BYTES);
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            byte[] problem = Problem.body("MALFORMED_REQUEST", tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode()
                    .put("field", "body"));
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setContentLength(problem.length);
            response.getOutputStream().write(problem);
            return;
        }
        String key = request.getHeader(HEADER);
        if (key != null) {
            request.setAttribute(KEY_ATTRIBUTE, key);
        }
        request.setAttribute(BODY_ATTRIBUTE, body);
        ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(new BufferedBodyRequest(request, body), buffered);
        } finally {
            buffered.copyBodyToResponse();
        }
    }
}
