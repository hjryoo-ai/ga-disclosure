package com.ga.disclosure.app.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;

/**
 * 데모 프로파일의 화면 서빙 체인(Phase 7 계획 "서버 쪽 추가" 1항 — 운영 분리는 Phase 8): 직원 화면·고객 서명 화면·정적 자산·데모 로그인만, 같은 출처.
 * 경로 판정은 MVC와 같은 {@code PathPattern} 파서({@link PathPatternRequestMatcher})로만 한다(6B D-4 — 파서가 둘이면 우회가 생긴다). 이 체인에 걸리지 않는
 * 메서드·경로는 나머지 체인(denyAll — 404)으로 간다. 데모가 아니면 이 체인도 컨트롤러도 없다.
 * <ul>
 *   <li>CSP {@value #CSP} — 인라인 스크립트·스타일·{@code eval} 없음, 외부 출처 0.</li>
 *   <li>{@code Referrer-Policy: no-referrer}, {@code X-Content-Type-Options: nosniff}(기본), 프레임 금지. 캐시 헤더는 컨트롤러가 정한다(HTML {@code no-store},
 *       해시 이름 자산 {@code immutable}).</li>
 *   <li>세션·쿠키·CSRF 토큰 없음(무상태 — 직원 토큰은 화면 메모리의 Bearer, 고객 토큰은 헤더).</li>
 * </ul>
 * 파일은 {@code disclosure-web} jar의 {@code classpath:/ga-web/}(Vite 산출물): HTML 네 경로는 함수형 라우터({@code no-store}), {@code /assets/*}는 정적
 * 자원 처리기(해시 이름 — 1년 {@code immutable}, 경로 탈출은 처리기가 막는다). 데모 로그인 경로도 같은 라우터({@link DemoLogin}). API 컨트롤러가 아니다 —
 * API 계층 규칙(컨트롤러 = 접두 패키지·유스케이스 한 번 호출)은 {@code /api}·{@code /internal}·{@code /public}의 것이고 여기에 닿지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("demo")
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class DemoWebConfiguration implements WebMvcConfigurer {

    static final String ROOT = "ga-web/";
    private static final MediaType HTML = MediaType.parseMediaType("text/html;charset=UTF-8");

    public static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; font-src 'self'; "
            + "connect-src 'self'; worker-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'";

    /** 화면 경로(GET): 직원 화면(SPA — 하위 경로도 같은 문서), 고객 서명 화면, 로그인 콜백, 해시 이름 자산, 데모 로그인 폼. */
    static final List<String> GET_PATHS = List.of("/", "/staff", "/staff/**", "/s", "/oidc-callback", "/assets/*", "/demo/oidc/authorize");
    /** 데모 로그인(POST): 주체 선택 폼 제출·코드 교환. */
    static final List<String> POST_PATHS = List.of("/demo/oidc/authorize", "/demo/oidc/token");

    static RequestMatcher matcher() {
        PathPatternRequestMatcher.Builder b = PathPatternRequestMatcher.withDefaults();
        List<RequestMatcher> all = new java.util.ArrayList<>();
        GET_PATHS.forEach(p -> all.add(b.matcher(HttpMethod.GET, p)));
        POST_PATHS.forEach(p -> all.add(b.matcher(HttpMethod.POST, p)));
        return new OrRequestMatcher(all);
    }

    @Bean
    @Order(5)
    public SecurityFilterChain demoWebSecurityFilterChain(HttpSecurity http) throws Exception {
        http.securityMatcher(matcher())
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(r -> r.disable())
                .securityContext(c -> c.disable())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives(CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .frameOptions(f -> f.deny())
                        .cacheControl(c -> c.disable()))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll());
        return http.build();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/*").addResourceLocations("classpath:/" + ROOT + "assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable()).resourceChain(false);
    }

    @Bean
    public RouterFunction<ServerResponse> demoScreens(DemoLogin login) {
        return RouterFunctions.route()
                .GET("/", r -> html("staff/index.html"))
                .GET("/staff", r -> html("staff/index.html"))
                .GET("/staff/**", r -> html("staff/index.html"))
                .GET("/s", r -> html("sign/index.html"))
                .GET("/oidc-callback", r -> html("oidc-callback/index.html"))
                .GET("/demo/oidc/authorize", login::form)
                .POST("/demo/oidc/authorize", RequestPredicates.contentType(MediaType.APPLICATION_FORM_URLENCODED), login::authorize)
                .POST("/demo/oidc/token", RequestPredicates.contentType(MediaType.APPLICATION_FORM_URLENCODED), login::token)
                .build();
    }

    private static ServerResponse html(String path) {
        ClassPathResource resource = new ClassPathResource(ROOT + path);
        if (!resource.exists()) {
            return ServerResponse.notFound().build();
        }
        try (InputStream in = resource.getInputStream()) {
            return ServerResponse.ok().contentType(HTML).cacheControl(CacheControl.noStore()).body(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
