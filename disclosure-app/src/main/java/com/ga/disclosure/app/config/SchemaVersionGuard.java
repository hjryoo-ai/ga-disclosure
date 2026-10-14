package com.ga.disclosure.app.config;

import com.ga.disclosure.app.health.DatabaseHealthIndicator;
import com.ga.disclosure.infra.migration.SchemaMigrator;
import com.ga.disclosure.infra.migration.SchemaVersion;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;

import java.util.Optional;

/**
 * 스키마 버전 가드(Phase 8, 8 계획 ③ "마이그레이션 = 선행 Job"): 앱은 기동 때 마이그레이션하지 않는다. 배포물의 최고 {@code V*}와 DB의 최고 성공 버전이
 * 다르면 <b>어떤 빈도 만들기 전에</b> 기동을 멈춘다(빈 팩토리 후처리기 — 웹·CLI·데모 모두). 메시지는 두 버전 번호뿐이다. 조회는 헬스 롤
 * ({@link DatabaseHealthIndicator}, V22) — 앱 롤은 이력 표 권한이 없다. 마이그레이션 명령 {@code db migrate}는 이 컨텍스트를 띄우지 않는다({@code OfflineCli}).
 */
public final class SchemaVersionGuard implements BeanFactoryPostProcessor, EnvironmentAware, Ordered {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        check(indicator(environment));
    }

    static DatabaseHealthIndicator indicator(Environment env) {
        return new DatabaseHealthIndicator(env.getRequiredProperty("ga.health.url"), env.getRequiredProperty("ga.health.username"),
                env.getRequiredProperty("ga.health.password"), SchemaMigrator.bundledVersion());
    }

    /** 메시지: 두 버전(DB 쪽은 번호·{@code none}·{@code unreadable (SQLSTATE)} — V22 전 DB는 헬스 롤이 이력을 못 읽는다)뿐. */
    static void check(DatabaseHealthIndicator database) {
        SchemaVersion bundled = database.bundledVersion();
        String found;
        try {
            Optional<SchemaVersion> applied = database.appliedVersion();
            if (applied.filter(bundled::equals).isPresent()) {
                return;
            }
            found = applied.map(SchemaVersion::toString).orElse("none");
        } catch (IllegalStateException e) {
            found = e.getMessage();
        }
        throw new IllegalStateException("schema version mismatch: database " + found + ", application " + bundled
                + " — apply migrations with the operator command 'db migrate' before starting");
    }
}
