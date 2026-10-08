package com.ga.disclosure.api.security;

import com.ga.disclosure.api.dto.PublicToken;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 컨트롤러 인자 {@link PublicToken}: 게이트가 통과시킨 토큰(요청 속성). 게이트를 거치지 않은 요청이면 실패(공개 advice가 거부로 바꾼다). */
final class PublicTokenArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == PublicToken.class;
    }

    @Override
    public PublicToken resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer, NativeWebRequest webRequest,
                                       WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        Object token = request == null ? null : request.getAttribute(PublicSignGate.TOKEN_ATTRIBUTE);
        if (!(token instanceof String raw)) {
            throw new IllegalStateException("public sign request did not pass the gate");
        }
        return new PublicToken(raw);
    }
}
