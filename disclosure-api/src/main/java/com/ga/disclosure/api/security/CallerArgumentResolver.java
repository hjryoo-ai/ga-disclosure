package com.ga.disclosure.api.security;

import com.ga.disclosure.workflow.authz.Caller;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 컨트롤러 인자 {@link Caller} — {@link TenantBindingFilter}가 둔 주체와 매칭된 라우트의 채널({@link BoundPrincipal#caller}). 없으면 바인딩 전 호출이라
 * 500이다(정상 경로에는 없다).
 */
public final class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == Caller.class;
    }

    @Override
    public Caller resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request, WebDataBinderFactory binders) {
        return BoundPrincipal.caller(request.getNativeRequest(jakarta.servlet.http.HttpServletRequest.class))
                .orElseThrow(() -> new IllegalStateException("no caller bound for this request"));
    }
}
