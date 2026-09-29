package com.ga.platform.spring.jdbc;

import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.io.Serial;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 테넌트 세션 바인딩 트랜잭션 매니저.
 *
 * <p>트랜잭션을 시작할 때 {@link TenantContext#current()}의 값을 PostgreSQL 트랜잭션 로컬 설정
 * {@code app.tenant_id}에 넣는다. RLS 정책 {@code tenant_id = current_setting('app.tenant_id', true)}가 이 값을 읽는다.
 * <ul>
 *   <li>{@code SET LOCAL app.tenant_id = ?}는 바인드 변수를 받지 못하므로 같은 효과의
 *       {@code SELECT set_config('app.tenant_id', ?, true)}(세 번째 인자 true = 트랜잭션 로컬)를 쓴다.
 *       커밋·롤백과 함께 사라지므로 풀링된 커넥션에 테넌트가 남지 않는다.</li>
 *   <li>컨텍스트가 없으면 커넥션을 얻기 전에 {@code TenantNotBoundException}으로 트랜잭션 자체가 실패한다.
 *       설정을 건너뛰고 진행하는 경로는 없다.</li>
 *   <li>시작한 테넌트를 트랜잭션 리소스로 기록해 두고, {@link TenantScopedRepository}가 호출마다 대조한다
 *       (트랜잭션 밖 호출, 트랜잭션 안에서 다른 테넌트로 재바인딩한 호출을 거부).</li>
 * </ul>
 */
public class TenantSessionBinder extends JdbcTransactionManager {

    @Serial
    private static final long serialVersionUID = 1L;

    static final String SET_TENANT_SQL = "SELECT set_config('app.tenant_id', ?, true)";

    private static final Object BOUND_TENANT_KEY = new Object() {
        @Override
        public String toString() {
            return "TenantSessionBinder.BOUND_TENANT";
        }
    };

    public TenantSessionBinder(DataSource dataSource) {
        super(dataSource);
    }

    /** 현재 트랜잭션을 연 테넌트(이 매니저가 연 트랜잭션 안에서만 존재). */
    public static Optional<TenantId> boundTenant() {
        return Optional.ofNullable((TenantId) TransactionSynchronizationManager.getResource(BOUND_TENANT_KEY));
    }

    static void requireBoundTransaction(TenantId expected) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new OutsideTenantTransactionException("no active transaction");
        }
        TenantId bound = boundTenant().orElseThrow(
                () -> new OutsideTenantTransactionException("active transaction was not started by TenantSessionBinder"));
        if (!bound.equals(expected)) {
            throw new TenantMismatchException(bound, expected);
        }
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        TenantId tenant = TenantContext.current(); // 커넥션 획득 전에 실패해야 한다
        super.doBegin(transaction, definition);
        TransactionSynchronizationManager.bindResource(BOUND_TENANT_KEY, tenant);
    }

    @Override
    protected void prepareTransactionalConnection(Connection con, TransactionDefinition definition) throws SQLException {
        super.prepareTransactionalConnection(con, definition);
        try (PreparedStatement ps = con.prepareStatement(SET_TENANT_SQL)) {
            ps.setString(1, TenantContext.current().value());
            ps.execute();
        }
    }

    @Override
    protected Object doSuspend(Object transaction) {
        Object connection = super.doSuspend(transaction);
        TenantId tenant = (TenantId) TransactionSynchronizationManager.unbindResourceIfPossible(BOUND_TENANT_KEY);
        return new Suspended(connection, tenant);
    }

    @Override
    protected void doResume(Object transaction, Object suspendedResources) {
        Suspended suspended = (Suspended) suspendedResources;
        super.doResume(transaction, suspended.connection());
        if (suspended.tenant() != null) {
            TransactionSynchronizationManager.bindResource(BOUND_TENANT_KEY, suspended.tenant());
        }
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        try {
            super.doCleanupAfterCompletion(transaction);
        } finally {
            TransactionSynchronizationManager.unbindResourceIfPossible(BOUND_TENANT_KEY);
        }
    }

    private record Suspended(Object connection, TenantId tenant) {
    }
}
