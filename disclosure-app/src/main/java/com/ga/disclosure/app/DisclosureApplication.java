package com.ga.disclosure.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ga-disclosure 진입점. Phase 0은 조립과 {@code /actuator/health}만 제공한다(컨트롤러·비즈니스 로직 없음).
 *
 * <p>데이터소스는 {@code disclosure_app}(RLS 대상, DML만), Flyway는 {@code disclosure_migrator}(소유자)로 접속한다.
 * 트랜잭션 매니저는 platform-spring의 {@code TenantSessionBinder}(자동설정)다.
 */
@SpringBootApplication(scanBasePackages = "com.ga.disclosure")
public class DisclosureApplication {

    public static void main(String[] args) {
        SpringApplication.run(DisclosureApplication.class, args);
    }
}
