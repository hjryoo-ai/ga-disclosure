package com.ga.platform.spring.jdbc;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * 조립: 애플리케이션 {@code DataSource}(= {@code disclosure_app} 롤)에 대해 {@link TenantSessionBinder}를
 * 유일한 트랜잭션 매니저로, {@link TenantJdbcGateway}를 저장소 공용 운반체로 등록한다.
 * Boot 기본 {@code DataSourceTransactionManagerAutoConfiguration}보다 먼저 적용되어 그쪽 조건부 빈을 대체한다.
 */
@AutoConfiguration(
        afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        beforeName = "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration")
@ConditionalOnSingleCandidate(DataSource.class)
public class PlatformJdbcAutoConfiguration {

    @Bean
    public TenantSessionBinder transactionManager(DataSource dataSource) {
        return new TenantSessionBinder(dataSource);
    }

    @Bean
    public TenantJdbcGateway tenantJdbcGateway(DataSource dataSource) {
        return new TenantJdbcGateway(dataSource);
    }
}
