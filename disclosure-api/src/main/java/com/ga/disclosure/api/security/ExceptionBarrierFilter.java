package com.ga.disclosure.api.security;

import com.ga.disclosure.api.error.Problem;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 필터 단계(MVC 바깥)에서 새어 나온 예외 → 500 {@code INTERNAL_ERROR}(6A 계획 §4.2). 컨트롤러 예외는 advice가 받으므로 여기 오는 것은 필터의 실패뿐이다
 * (예: 바인딩 전 조회의 {@code TenantNotBoundException} — TenantBindingOrderIT). 없으면 서블릿 컨테이너가 {@code /error}로 다시 보내고, 그 경로는 체인 밖이라
 * 404로 바뀌어 실패가 가려진다. 로그는 예외 클래스 이름만(메시지·스택에 입력값이 섞일 수 있다).
 */
final class ExceptionBarrierFilter extends OncePerRequestFilter {

    private static final System.Logger LOG = System.getLogger(ExceptionBarrierFilter.class.getName());
    private static final byte[] BODY = Problem.body("INTERNAL_ERROR", tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode());

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            Throwable cause = e instanceof ServletException && e.getCause() != null ? e.getCause() : e;
            LOG.log(System.Logger.Level.ERROR, "API_FILTER_ERROR " + cause.getClass().getSimpleName());
            if (response.isCommitted()) {
                throw e;
            }
            response.reset();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setContentLength(BODY.length);
            response.getOutputStream().write(BODY);
        }
    }
}
