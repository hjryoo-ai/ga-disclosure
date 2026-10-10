package com.ga.disclosure.api.security;

import com.ga.disclosure.api.error.Problem;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.onboarding.TenantRulesStatus;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

/**
 * 룰 없는 테넌트의 쓰기 → 503 {@code TENANT_RULES_NOT_ACTIVE}(6B 이월 ①, 8 계획 승인 — {@code /api}·{@code /internal}만, 공개 경로는 없는 테넌트와 같은 거부
 * 그대로). 멱등 인터셉터보다 앞(키를 묶지 않는다), 유스케이스보다 앞(감사 없음). 바인딩된 호출자가 있는 쓰기 요청만 본다 — 인증 실패·없는 라우트는 그 전에 끝난다.
 */
final class TenantRulesInterceptor implements HandlerInterceptor {

    static final Set<String> WRITES = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final byte[] BODY = Problem.body("TENANT_RULES_NOT_ACTIVE", tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode());

    private final TenantRulesStatus status;

    TenantRulesInterceptor(TenantRulesStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod) || !WRITES.contains(request.getMethod())
                || !(BoundPrincipal.caller(request).orElse(null) instanceof Caller caller) || status.inForce(caller)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setContentLength(BODY.length);
        response.getOutputStream().write(BODY);
        return false;
    }
}
