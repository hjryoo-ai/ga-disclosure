package com.ga.disclosure.api.security;

import com.ga.disclosure.api.error.Problem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 체인 밖 경로(6A 계획 §5.1 — {@code /actuator/health} 외 {@code denyAll}): 401·403 대신 내부 경로의 404와 같은 본문을 낸다. 없는 경로와 막힌 경로를
 * 구별할 수 없게 한다.
 */
final class UnroutedPathHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final byte[] BODY = Problem.body("NOT_FOUND", tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode());

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException {
        write(response);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException {
        write(response);
    }

    private static void write(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(BODY.length);
        response.getOutputStream().write(BODY);
    }
}
