package com.ga.disclosure.api.security;

import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Channel;
import com.ga.disclosure.workflow.authz.TenantRegistry;
import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * 토큰 → 테넌트 바인딩(6A 계획 §8). JWT 검증(서명·만료·발급자·대상) 뒤에 돈다:
 * <ol>
 *   <li>클레임 {@code sub}·{@code tenant_id}만 읽는다 — {@code Jwt}를 다루는 클래스는 이것뿐이다(ApiLayerRulesTest). 역할·조직 클레임은 읽지 않는다
 *       (역할은 {@code identity_link}, 절대 규칙 5).</li>
 *   <li>테넌트 형식 검사(실패 401).</li>
 *   <li>{@code ScopedValue}로 테넌트를 바인딩하고, 바인딩된 상태에서 테넌트 행이 있는지 RLS 아래에서 확인한다(없으면 401).</li>
 *   <li>호출자 {@code Caller(테넌트, 주체, 채널)}를 요청 속성에 두고 바인딩 범위 안에서 체인을 잇는다. 채널은 경로 접두다({@code /api} → API,
 *       {@code /internal} → INTERNAL — 승인 Q15).</li>
 * </ol>
 */
public final class TenantBindingFilter extends OncePerRequestFilter {

    /** 요청 속성 — 컨트롤러는 {@link CallerArgumentResolver}로 받는다. */
    public static final String CALLER = TenantBindingFilter.class.getName() + ".caller";
    static final String TENANT_CLAIM = "tenant_id";

    private final TenantRegistry tenants;

    public TenantBindingFilter(TenantRegistry tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
    }

    /**
     * JWT → 인증 변환: 권한을 하나도 만들지 않는다({@code scope}·{@code roles} 클레임이 권한이 될 길이 없다 — 역할은 {@code identity_link}, 6A 계획 §5.1).
     */
    public static org.springframework.core.convert.converter.Converter<Jwt, org.springframework.security.authentication.AbstractAuthenticationToken>
            noAuthorities() {
        return jwt -> new JwtAuthenticationToken(jwt, java.util.List.of());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            ApiAuthenticationEntryPoint.write(response);
            return;
        }
        Jwt jwt = token.getToken();
        String subject = jwt.getSubject();
        String claim = jwt.getClaimAsString(TENANT_CLAIM);
        TenantId tenant;
        try {
            tenant = TenantId.of(claim);
        } catch (RuntimeException malformed) {
            ApiAuthenticationEntryPoint.write(response);
            return;
        }
        if (subject == null || subject.isBlank() || subject.length() > 200) {
            ApiAuthenticationEntryPoint.write(response);
            return;
        }
        Channel channel = request.getRequestURI().startsWith(request.getContextPath() + "/internal/") ? Channel.INTERNAL : Channel.API;
        try {
            TenantContext.runWith(tenant, () -> {
                if (!tenants.exists(TenantContext.current())) {
                    ApiAuthenticationEntryPoint.write(response);
                    return null;
                }
                request.setAttribute(CALLER, new Caller(tenant, subject, channel));
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }
}
