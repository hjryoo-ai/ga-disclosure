package com.ga.disclosure.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;

/**
 * 액세스 로그(로거 {@code ga.access}, 6A 계획 §5.5): 메서드·<b>라우트 템플릿</b>·상태·소요만. 원 URI·질의 문자열·헤더는 남기지 않는다 — 토큰이 섞일 수 있다.
 * 매칭이 없으면 공개 경로는 {@code /public/**}, 그 밖은 {@code -}. 톰캣 밸브는 쓰지 않는다.
 */
final class AccessLogFilter extends OncePerRequestFilter {

    static final String LOGGER = "ga.access";
    private static final System.Logger LOG = System.getLogger(LOGGER);

    private final Clock clock;

    AccessLogFilter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = clock.millis();
        try {
            chain.doFilter(request, response);
        } finally {
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String template = pattern instanceof String t ? t : request.getRequestURI().startsWith(request.getContextPath() + "/public/") ? "/public/**" : "-";
            LOG.log(System.Logger.Level.INFO, request.getMethod() + " " + template + " " + response.getStatus() + " " + (clock.millis() - start) + "ms");
        }
    }
}
