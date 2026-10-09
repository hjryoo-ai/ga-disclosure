package com.ga.disclosure.api.security;

import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Channel;
import com.ga.platform.core.tenant.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Objects;
import java.util.Optional;

/**
 * 토큰이 정한 테넌트·주체({@link TenantBindingFilter}가 요청 속성에 둔다). 채널은 여기 없다 — 채널은 <b>라우팅 결과</b>(매칭된 라우트 템플릿)에서 읽는다
 * ({@link #caller}): {@code /api/…} → API, {@code /internal/…} → INTERNAL(승인 Q15). 경로 문자열을 따로 해석하면 MVC와 다른 파서가 생겨 같은 핸들러에 닿는
 * 다른 표기가 판단을 건너뛴다(6B 8단계 보안 검토 — 6A는 원 URI 접두로 채널을 정해 {@code /%69nternal/…}이 API 채널로 내부 핸들러에 닿았다).
 */
public record BoundPrincipal(TenantId tenant, String subject) {

    /** 요청 속성 이름. */
    public static final String ATTRIBUTE = BoundPrincipal.class.getName();

    public BoundPrincipal {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(subject, "subject");
    }

    public static Optional<BoundPrincipal> of(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof BoundPrincipal p ? Optional.of(p) : Optional.empty();
    }

    /**
     * 라우팅 뒤의 호출자: 바인딩된 주체와 매칭된 라우트 템플릿의 접두로 정한 채널. 핸들러가 정해지기 전(필터)이거나 {@code /api}·{@code /internal} 밖의 라우트면
     * 빈 값이다 — 채널을 추측하지 않는다.
     */
    public static Optional<Caller> caller(HttpServletRequest request) {
        if (!(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String route)) {
            return Optional.empty();
        }
        Channel channel;
        if (route.startsWith("/internal/")) {
            channel = Channel.INTERNAL;
        } else if (route.startsWith("/api/")) {
            channel = Channel.API;
        } else {
            return Optional.empty();
        }
        return of(request).map(p -> new Caller(p.tenant(), p.subject(), channel));
    }
}
