package com.ga.disclosure.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * 포트 ↔ 경로 접두 대조(Phase 8 Q5): 내부 포트로 온 요청은 {@code /internal/**}만, 다른 포트로 온 {@code /internal/**}은 없다 — 어긋나면 체인 밖 경로와
 * 같은 404 바이트(헤더 기록 필터 뒤에 두어 헤더도 같다). 인증보다 앞이라 잘못된 포트에서는 401도 나오지 않는다.
 */
final class PortChannelFilter extends OncePerRequestFilter {

    private final InternalPort internal;

    PortChannelFilter(InternalPort internal) {
        this.internal = Objects.requireNonNull(internal, "internal");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        boolean onInternalPort = internal.arrivedOn(request.getLocalPort());
        boolean internalPath = InternalPort.internalPath(request.getRequestURI().substring(request.getContextPath().length()));
        if (onInternalPort != internalPath) {
            UnroutedPathHandler.write(response);
            return;
        }
        chain.doFilter(request, response);
    }
}
