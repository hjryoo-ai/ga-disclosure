package com.ga.disclosure.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * 포트 ↔ 경로 접두 대조(Phase 8 Q5): 내부 포트로 온 요청은 {@code /internal/**}만, 다른 포트로 온 {@code /internal/**}은 없다 — 어긋나면 체인 밖 경로와
 * 같은 404 바이트(헤더 기록 필터 뒤에 두어 헤더도 같다). 인증보다 앞이라 잘못된 포트에서는 401도 나오지 않는다.
 * <p>경로 판정은 체인 선택·MVC 라우팅과 <b>같은 파서</b>({@code PathPatternRequestMatcher} — 디코딩·세미콜론 제거 뒤의 경로)로 한다. 원 URI 접두로 판정하던
 * 첫 판은 {@code /%69nternal/v1/jobs}가 앱 포트에서 대조를 건너뛰고 내부 핸들러에 닿았다(커밋 뒤 보안 검토 — 6B 8단계와 같은 부류, 회귀 시험
 * {@code PortSeparationIT.noSpellingOfTheInternalPrefixReachesItsHandlersOnTheAppPort}).
 */
final class PortChannelFilter extends OncePerRequestFilter {

    private static final RequestMatcher INTERNAL_PATHS = PathPatternRequestMatcher.withDefaults().matcher(InternalPort.PREFIX + "/**");

    private final InternalPort internal;

    PortChannelFilter(InternalPort internal) {
        this.internal = Objects.requireNonNull(internal, "internal");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        boolean onInternalPort = internal.arrivedOn(request.getLocalPort());
        boolean internalPath = INTERNAL_PATHS.matches(request);
        if (onInternalPort != internalPath) {
            UnroutedPathHandler.write(response);
            return;
        }
        chain.doFilter(request, response);
    }
}
