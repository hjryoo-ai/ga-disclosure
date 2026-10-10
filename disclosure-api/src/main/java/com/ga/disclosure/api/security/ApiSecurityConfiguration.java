package com.ga.disclosure.api.security;

import com.ga.disclosure.workflow.authz.TenantRegistry;
import com.ga.disclosure.workflow.sign.PublicSignLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;

/**
 * {@code /api/**}·{@code /internal/**}의 JWT 체인 하나(6A 승인 Q15, 계획 §8). 세션·쿠키·CSRF 없음(무상태 Bearer). 검증은 서명·만료·발급자
 * ({@code ga.api.jwt.issuer})·대상({@code ga.api.jwt.audience})이고 공개키는 {@code ga.api.jwt.jwk-set-uri}(운영 IdP) 또는
 * {@code ga.api.jwt.public-key-location}(PEM — 데모·시험) 중 하나다. 셋 다 기본값이 없다: 웹 애플리케이션은 설정 없이 뜨지 않는다(CLI 프로파일은 웹이 아니라
 * 이 구성이 없다). 인증 실패는 전부 같은 401({@link ApiAuthenticationEntryPoint}). 역할은 토큰이 아니라 {@code identity_link}에서 온다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiSecurityConfiguration {

    /** 서블릿 필터 순서: 예외 장벽은 보안 체인({@code -100})보다 앞. */
    public static final int BARRIER_ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Bean
    public JwtDecoder apiJwtDecoder(@Value("${ga.api.jwt.issuer}") String issuer, @Value("${ga.api.jwt.audience}") String audience,
                                    @Value("${ga.api.jwt.jwk-set-uri:}") String jwkSetUri,
                                    @Value("${ga.api.jwt.public-key-location:}") String publicKeyLocation) {
        if (jwkSetUri.isBlank() == publicKeyLocation.isBlank()) {
            throw new IllegalStateException("configure exactly one of ga.api.jwt.jwk-set-uri or ga.api.jwt.public-key-location");
        }
        NimbusJwtDecoder decoder = jwkSetUri.isBlank()
                ? NimbusJwtDecoder.withPublicKey(readPublicKey(Path.of(publicKeyLocation))).build()
                : NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        // 대상 검사는 클레임 검증기로 — Jwt 멤버를 직접 부르는 클래스는 TenantBindingFilter뿐이다(ApiLayerRulesTest (d)).
        JwtClaimValidator<Collection<String>> audienceCheck = new JwtClaimValidator<>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceCheck));
        return decoder;
    }

    /** 보안 체인보다 앞 — 필터 단계 예외를 500 {@code INTERNAL_ERROR}로({@link ExceptionBarrierFilter}). */
    @Bean
    public FilterRegistrationBean<ExceptionBarrierFilter> exceptionBarrierFilter() {
        FilterRegistrationBean<ExceptionBarrierFilter> registration = new FilterRegistrationBean<>(new ExceptionBarrierFilter());
        registration.setOrder(BARRIER_ORDER);
        return registration;
    }

    static RSAPublicKey readPublicKey(Path pem) {
        try {
            String text = Files.readString(pem);
            String base64 = text.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (IOException e) {
            throw new UncheckedIOException("ga.api.jwt.public-key-location is not readable: " + pem, e);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("ga.api.jwt.public-key-location is not an RSA public key PEM", e);
        }
    }

    @Bean
    public Sleeper publicSignSleeper() {
        return Sleeper.threadSleeper();
    }

    /** 공개 응답 하한(승인 Q6): 배포 설정, 기본값 없음 — 없거나 1 미만이면 기동 실패. */
    @Bean
    public ResponsePadding publicSignPadding(@Value("${ga.public-sign.min-response-millis}") long floorMillis, Clock clock, Sleeper sleeper) {
        return new ResponsePadding(Duration.ofMillis(floorMillis), clock, sleeper);
    }

    @Bean
    public RateWindow publicSignRateWindow() {
        return new RateWindow();
    }

    /** 액세스 로그(로거 {@code ga.access}): 장벽 바로 뒤 — 모든 경로, 템플릿만. */
    @Bean
    public FilterRegistrationBean<AccessLogFilter> accessLogFilter(Clock clock) {
        FilterRegistrationBean<AccessLogFilter> registration = new FilterRegistrationBean<>(new AccessLogFilter(clock));
        registration.setOrder(BARRIER_ORDER + 5);
        return registration;
    }

    /**
     * 고객 공개 서명 체인(6A 계획 §5.1): 인증 없음, 세션·요청 캐시·CSRF 없음, 리퍼러 없음. 문({@link PublicSignGate})은 헤더 필터 뒤 하나 — 모든 공개 응답이 같은
     * 체인·같은 헤더를 지난다.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain publicSignSecurityFilterChain(HttpSecurity http, Clock clock, TenantRegistry tenants, PublicSignLimits limits,
                                                            RateWindow rates, ResponsePadding padding) throws Exception {
        http.securityMatcher("/public/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(r -> r.disable())
                .securityContext(c -> c.disable())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .headers(h -> h.referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .addFilterAfter(new PublicSignGate(clock, tenants, limits, rates, padding), HeaderWriterFilter.class);
        return http.build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtDecoder apiJwtDecoder, TenantRegistry tenants,
            @org.springframework.beans.factory.annotation.Value("${ga.api.client-cert.subject-header:}") String clientCertHeader) throws Exception {
        ApiAuthenticationEntryPoint entryPoint = new ApiAuthenticationEntryPoint();
        http.securityMatcher("/api/**", "/internal/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(r -> r.disable())
                .anonymous(a -> a.disable())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .authorizeHttpRequests(a -> a.anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(apiJwtDecoder).jwtAuthenticationConverter(TenantBindingFilter.noAuthorities()))
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .addFilterAfter(new TenantBindingFilter(tenants), BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new ClientCertSubjectFilter(java.util.Optional.of(clientCertHeader)), TenantBindingFilter.class)
                .addFilterAfter(new IdempotencyCaptureFilter(), ClientCertSubjectFilter.class);
        return http.build();
    }

    /**
     * 나머지 경로(계획 §5.1): 헬스({@code /actuator/health}와 준비성·활성 그룹)만 열고 그 밖은 {@code denyAll} — 응답은 내부 경로의 404와 같은 본문.
     * 세션·쿠키 없음. Phase 8부터 액추에이터는 관리 포트에만 있다 — 앱 포트의 같은 경로는 매핑이 없어 404다.
     */
    @Bean
    @Order(10)
    public SecurityFilterChain otherSecurityFilterChain(HttpSecurity http) throws Exception {
        UnroutedPathHandler unrouted = new UnroutedPathHandler();
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(r -> r.disable())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .authorizeHttpRequests(a -> a.requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/readiness", "/actuator/health/liveness")
                        .permitAll().anyRequest().denyAll())
                .exceptionHandling(e -> e.authenticationEntryPoint(unrouted).accessDeniedHandler(unrouted));
        return http.build();
    }
}
