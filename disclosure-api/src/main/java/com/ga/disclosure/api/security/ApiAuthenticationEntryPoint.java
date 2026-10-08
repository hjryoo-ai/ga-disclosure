package com.ga.disclosure.api.security;

import com.ga.disclosure.api.error.Problem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * 401(6A 계획 §4.2): 토큰 없음·검증 실패·테넌트 클레임 없음·모르는 테넌트 전부 같은 응답 — 본문 {@code UNAUTHENTICATED} 하나, {@code WWW-Authenticate}는
 * {@code Bearer}만(사유 파라미터 없음).
 */
public final class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final byte[] BODY = Problem.body("UNAUTHENTICATED", tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode());

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
        write(response);
    }

    static void write(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(BODY.length);
        response.getOutputStream().write(BODY);
    }
}
