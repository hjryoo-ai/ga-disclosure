package com.ga.disclosure.app;

import com.ga.disclosure.app.cli.OfflineCli;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ga-disclosure 진입점.
 *
 * <p>데이터소스는 {@code disclosure_app}(RLS 대상, DML만)이다. 앱은 기동 때 마이그레이션하지 않는다(Phase 8) — 스키마는 운영자 명령 {@code db migrate}
 * ({@code disclosure_migrator}, {@link OfflineCli})가 먼저 적용하고, 앱은 스키마 버전 가드({@code SchemaVersionGuard})를 지나야 뜬다.
 * 트랜잭션 매니저는 platform-spring의 {@code TenantSessionBinder}(자동설정)다.
 */
@SpringBootApplication(scanBasePackages = "com.ga.disclosure")
public class DisclosureApplication {

    public static void main(String[] args) {
        if (OfflineCli.handles(args)) {
            try {
                OfflineCli.run(args, System.out);
            } catch (RuntimeException e) {
                System.err.println("ERROR " + e.getMessage());
                System.exit(1);
            }
            return;
        }
        SpringApplication.run(DisclosureApplication.class, args);
    }
}
