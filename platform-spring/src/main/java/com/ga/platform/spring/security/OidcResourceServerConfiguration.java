package com.ga.platform.spring.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OIDC 리소스 서버 설정 골격. <b>프로파일 {@value #PROFILE}에서만 활성</b>이며 Phase 0에서는 어떤 애플리케이션도 켜지 않는다.
 *
 * <p>Spring Security 의존은 {@code compileOnly}다 — 이 설정을 쓰는 애플리케이션이 보안 스타터를 직접 선언한다.
 * subject → {@code identity_link} 해석({@link com.ga.platform.spring.identity.IdentityResolver})과 요청 필터는 Phase 6.
 */
@Configuration(proxyBeanMethods = false)
@Profile(OidcResourceServerConfiguration.PROFILE)
@ConditionalOnClass(name = "org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken")
public class OidcResourceServerConfiguration {

    public static final String PROFILE = "oidc";

    @Bean
    public SecurityFilterChain oidcSecurityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
