package com.ga.disclosure.api.security;

import com.ga.disclosure.workflow.authz.Caller;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * mTLS 주체 대조(6B 계획 §6 Q11): 종단은 인그레스(Phase 8)이고 앱은 JWT 체인 그대로다. 설정 {@code ga.api.client-cert.subject-header}가 있으면
 * 게이트·계약 연결 경로에서 인그레스가 넣은 그 헤더 값이 정확히 하나이고 JWT {@code sub}(= {@code identity_link} 주체)와 같아야 한다 — 없거나 둘 이상이거나
 * 다르면 없는 라우트와 같은 404. 헤더는 인그레스가 덮어써야 믿을 수 있으므로 {@code prod} 프로파일은 설정 없이 기동하지 않는다(앱의 기동 가드).
 * 인증·테넌트 바인딩 뒤에 돈다(호출자가 있어야 대조한다). 헤더 값은 로그·응답에 싣지 않는다.
 *
 * <p>경로는 원 URI가 아니라 라우팅되는 디코딩 경로({@link RoutedPath})로 고른다 — 원 URI 문자열 비교는 {@code /internal/v1/%67ate}처럼 같은 핸들러에
 * 닿는 다른 표기로 건너뛸 수 있었다(보안 검토 반영).
 */
public final class ClientCertSubjectFilter extends OncePerRequestFilter {

    /** 대조하는 경로(닫힌 목록 — 서비스 주체가 업무 사실을 넣거나 묻는 입구). */
    public static final Set<String> GUARDED_PATHS = Set.of("/internal/v1/gate", "/internal/v1/contract-links");

    private final Optional<String> header;

    public ClientCertSubjectFilter(Optional<String> headerOrEmpty) {
        this.header = headerOrEmpty.filter(h -> !h.isBlank());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return header.isEmpty() || !GUARDED_PATHS.contains(RoutedPath.of(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        List<String> values = Collections.list(request.getHeaders(header.orElseThrow()));
        if (!(request.getAttribute(TenantBindingFilter.CALLER) instanceof Caller caller) || values.size() != 1
                || !values.getFirst().equals(caller.subject())) {
            UnroutedPathHandler.write(response);
            return;
        }
        chain.doFilter(request, response);
    }
}
