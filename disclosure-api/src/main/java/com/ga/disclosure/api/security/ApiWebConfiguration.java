package com.ga.disclosure.api.security;

import com.ga.disclosure.api.idempotency.CanonicalJsonMessageConverter;
import com.ga.disclosure.api.idempotency.IdempotencyInterceptor;
import com.ga.disclosure.workflow.idempotency.IdempotencyService;
import com.ga.disclosure.workflow.idempotency.RequestHashPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * MVC 조립: 컨트롤러 인자 {@code Caller}, 쓰기 POST의 Idempotency-Key 인터셉터({@code /api}·{@code /internal}), JSON 응답의 JCS 직렬화(재생 바이트 동일 —
 * 승인 Q4).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiWebConfiguration implements WebMvcConfigurer {

    private final IdempotencyService idempotency;
    private final RequestHashPort requestHashes;
    private final JsonMapper mapper;

    public ApiWebConfiguration(IdempotencyService idempotency, RequestHashPort requestHashes, JsonMapper mapper) {
        this.idempotency = idempotency;
        this.requestHashes = requestHashes;
        this.mapper = mapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new IdempotencyInterceptor(idempotency, requestHashes)).addPathPatterns("/api/**", "/internal/**");
    }

    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.addCustomConverter(new CanonicalJsonMessageConverter(mapper));
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerArgumentResolver());
        resolvers.add(new ClientContextArgumentResolver());
        resolvers.add(new PublicTokenArgumentResolver());
        resolvers.add(new IdempotencyKeyArgumentResolver());
    }
}
