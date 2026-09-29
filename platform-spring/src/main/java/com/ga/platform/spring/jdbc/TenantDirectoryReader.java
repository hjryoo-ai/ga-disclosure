package com.ga.platform.spring.jdbc;

import com.ga.platform.core.tenant.TenantId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Objects;

/**
 * 모든 테넌트 ID를 읽는다 — 운영자 CLI의 {@code --tenants all} 전용. 테넌트 조건이 없는 유일한 조회이므로 애플리케이션 데이터소스가
 * 아니라 <b>테넌트 디렉터리 전용 롤</b>(ga-disclosure: {@code disclosure_operator}, {@code tenant.tenant_id} 컬럼 SELECT와 전용 RLS
 * 정책만 가진 롤)로 따로 접속한다. 이 롤로는 다른 테이블·컬럼을 읽을 수 없다(DB 권한). 아키텍처 규칙 {@code dbAccessOnlyVia}의
 * 허용 목록에 FQN으로 등재된다. 데이터소스를 빈으로 등록하지 않는다(애플리케이션 데이터소스와 섞이지 않게).
 */
public final class TenantDirectoryReader {

    private final JdbcClient client;

    public TenantDirectoryReader(String jdbcUrl, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                Objects.requireNonNull(jdbcUrl, "jdbcUrl"), Objects.requireNonNull(username, "username"), password);
        this.client = JdbcClient.create(dataSource);
    }

    public List<TenantId> allTenants() {
        return client.sql("SELECT tenant_id FROM tenant ORDER BY tenant_id").query((rs, n) -> TenantId.of(rs.getString(1))).list();
    }
}
