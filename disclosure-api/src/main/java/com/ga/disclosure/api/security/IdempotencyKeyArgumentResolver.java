package com.ga.disclosure.api.security;

import com.ga.disclosure.api.dto.IdempotencyKey;
import com.ga.disclosure.api.error.MalformedRequestException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** 컨트롤러 인자 {@link IdempotencyKey}: 캡처 필터가 둔 요청 속성(인터셉터가 형식을 확인한 키). 원 헤더 읽기는 {@code api.security}에서만(ApiLayerRulesTest (e)). */
final class IdempotencyKeyArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == IdempotencyKey.class;
    }

    @Override
    public IdempotencyKey resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer, NativeWebRequest webRequest,
                                          WebDataBinderFactory binderFactory) {
        HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
        Object key = request == null ? null : request.getAttribute(IdempotencyCaptureFilter.KEY_ATTRIBUTE);
        if (!(key instanceof String s)) {
            throw new MalformedRequestException(IdempotencyCaptureFilter.HEADER);
        }
        return new IdempotencyKey(s);
    }
}
