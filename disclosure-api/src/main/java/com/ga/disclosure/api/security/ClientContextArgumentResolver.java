package com.ga.disclosure.api.security;

import com.ga.disclosure.api.dto.ClientContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 컨트롤러 인자 {@link ClientContext}: 원격 주소와 User-Agent(서명 증거). 원 헤더 읽기는 {@code api.security}에서만(ApiLayerRulesTest (e)). */
final class ClientContextArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == ClientContext.class;
    }

    @Override
    public ClientContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer, NativeWebRequest webRequest,
                                         WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        return request == null ? new ClientContext(null, null) : new ClientContext(request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
    }
}
