package com.ga.platform.spring.jdbc;

import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * {@link TenantScopedRepository}가 쓰는 JDBC 클라이언트의 운반체. 원시 {@code DataSource}/{@code JdbcClient}를 저장소 하위
 * 클래스에 노출하지 않기 위한 봉투다 — 하위 클래스는 이 객체를 생성자로 받아 {@code super(gateway)}에 넘기기만 한다.
 *
 * <p>하위 저장소(다른 패키지)의 생성자 시그니처에 쓰이므로 public 최상위 클래스이며, 아키텍처 규칙 {@code dbAccessOnlyVia}의
 * 허용 목록에 FQN으로 등재된다. 담고 있는 클라이언트는 이 패키지 밖으로 나가지 않는다.
 */
public final class TenantJdbcGateway {

    private final JdbcClient client;

    public TenantJdbcGateway(DataSource dataSource) {
        this.client = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
    }

    JdbcClient client() {
        return client;
    }
}
