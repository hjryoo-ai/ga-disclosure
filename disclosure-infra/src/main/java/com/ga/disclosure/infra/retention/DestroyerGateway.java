package com.ga.disclosure.infra.retention;

import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.retention.DestroyerPort;
import com.ga.disclosure.workflow.retention.DestructionRefusedException;
import com.ga.platform.core.tenant.TenantContext;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 파기 함수 호출(5 계획 §5.4, 승인 Q2 — 한 트랜잭션 + {@code SET LOCAL ROLE}). 호출자의 테넌트 트랜잭션이 잡은 앱 연결에서
 * {@code SET LOCAL ROLE disclosure_destroyer} → {@code SELECT ga_…(…)} → {@code RESET ROLE}. 앱 롤은 파기자 롤로 SET ROLE만 할 수 있고 권한을
 * 물려받지 않으며(멤버십 {@code SET TRUE, INHERIT FALSE}), 파기자 롤이 가진 것은 세 함수의 EXECUTE뿐이다. {@code SET LOCAL}은 트랜잭션 끝에 풀리지만
 * 예외가 나도 같은 연결이 파기자 롤로 남지 않도록 {@code finally}에서 {@code RESET ROLE}을 부른다.
 *
 * <p>직접 JDBC 접근 허용 목록의 항목이다(ArchitectureRulesTest): 테넌트 데이터를 읽지 않고(함수 호출 결과는 키 ID 하나), 전용 롤로만 쓴다. 트랜잭션
 * 밖에서 부르면 거부한다({@code SET LOCAL}이 효과가 없다).
 */
@Component
public class DestroyerGateway implements DestroyerPort {

    static final String ROLE = "disclosure_destroyer";

    private final DataSource dataSource;

    public DestroyerGateway(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public String shredDocumentKey(DisclosureId disclosure, LocalDate asOf, Instant at, String by) {
        return call("SELECT ga_document_key_shred(?, ?, ?, ?, ?)", ps -> {
            ps.setString(1, TenantContext.current().value());
            ps.setObject(2, disclosure.value());
            ps.setDate(3, Date.valueOf(asOf));
            ps.setTimestamp(4, Timestamp.from(at));
            ps.setString(5, by);
        });
    }

    @Override
    public void destroyDisclosure(DisclosureId disclosure, LocalDate asOf, Instant at, String by) {
        call("SELECT ga_disclosure_destroy(?, ?, ?, ?, ?)", ps -> {
            ps.setString(1, TenantContext.current().value());
            ps.setObject(2, disclosure.value());
            ps.setDate(3, Date.valueOf(asOf));
            ps.setTimestamp(4, Timestamp.from(at));
            ps.setString(5, by);
        });
    }

    @Override
    public void destroyCustomerRef(CustomerRef customer, Instant at, String by) {
        call("SELECT ga_customer_ref_destroy(?, ?, ?, ?)", ps -> {
            ps.setString(1, TenantContext.current().value());
            ps.setString(2, customer.value());
            ps.setTimestamp(3, Timestamp.from(at));
            ps.setString(4, by);
        });
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private String call(String sql, Binder binder) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("destruction functions run inside the caller's tenant transaction");
        }
        Connection c = DataSourceUtils.getConnection(dataSource);
        try {
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL ROLE " + ROLE);
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        } catch (SQLException e) {
            throw new DestructionRefusedException(e.getSQLState() == null ? "UNKNOWN" : e.getSQLState(), e);
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
