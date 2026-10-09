package com.ga.disclosure.infra.retention;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.disclosure.AbandonPort;
import com.ga.disclosure.workflow.disclosure.AbandonRefusedException;
import com.ga.platform.core.tenant.TenantContext;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

/**
 * 초안 폐기 함수 호출(6B 계획 Q10 — {@link DestroyerGateway}와 같은 방식, 다른 롤). 호출자의 테넌트 트랜잭션이 잡은 앱 연결에서
 * {@code SET LOCAL ROLE disclosure_abandoner} → {@code SELECT ga_draft_abandon(…)} → {@code RESET ROLE}. 폐기 롤이 가진 것은 그 함수 하나의
 * EXECUTE뿐이다(V14) — 설계사 요청 경로가 파기 롤을 쓰지 않는다.
 *
 * <p>직접 JDBC 접근 허용 목록의 항목이다(ArchitectureRulesTest): 테넌트 데이터를 읽지 않고(함수는 값을 돌려주지 않는다), 전용 롤로만 쓴다. 트랜잭션
 * 밖에서 부르면 거부한다({@code SET LOCAL}이 효과가 없다).
 */
@Component
public class AbandonGateway implements AbandonPort {

    static final String ROLE = "disclosure_abandoner";

    private final DataSource dataSource;

    public AbandonGateway(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void abandon(DisclosureId disclosure, Instant at, String by) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("draft abandonment runs inside the caller's tenant transaction");
        }
        Connection c = DataSourceUtils.getConnection(dataSource);
        try {
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL ROLE " + ROLE);
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT ga_draft_abandon(?, ?, ?, ?)")) {
                ps.setString(1, TenantContext.current().value());
                ps.setObject(2, disclosure.value());
                ps.setTimestamp(3, Timestamp.from(at));
                ps.setString(4, by);
                ps.execute();
            }
        } catch (SQLException e) {
            throw new AbandonRefusedException(e.getSQLState() == null ? "UNKNOWN" : e.getSQLState(), e);
        } finally {
            try (Statement s = c.createStatement()) {
                s.execute("RESET ROLE");
            } catch (SQLException ignored) {
                // 트랜잭션이 이미 실패 상태면 RESET도 실패한다 — 롤백이 SET LOCAL을 함께 되돌린다
            }
            DataSourceUtils.releaseConnection(c, dataSource);
        }
    }
}
