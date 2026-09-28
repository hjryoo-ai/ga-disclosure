package com.ga.platform.spring.jdbc;

import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.core.tenant.TenantNotBoundException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** C1: TenantContext 미바인딩 상태의 저장소 호출은 DB 도달 전 예외. 부수로 테넌트 조건 없는 SQL도 DB 도달 전 거부. */
class TenantContextGuardTest {

    private static final TenantId TA = TenantId.of("TA");

    private final FakeJdbc jdbc = new FakeJdbc();
    private final ProbeRepository repository = new ProbeRepository(new TenantScopedRepository.Gateway(jdbc.dataSource));
    private final TransactionTemplate tx = new TransactionTemplate(new TenantSessionBinder(jdbc.dataSource));

    @Test
    void unboundRepositoryCallFailsBeforeReachingDatabase() {
        assertThatThrownBy(repository::scoped).isInstanceOf(TenantNotBoundException.class);
        assertThatThrownBy(() -> repository.insert("n")).isInstanceOf(TenantNotBoundException.class);
        assertThat(jdbc.connectionsObtained()).isZero();
        assertThat(jdbc.events).isEmpty();
    }

    @Test
    void unboundTransactionCannotEvenStart() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> repository.scoped()))
                .isInstanceOf(TenantNotBoundException.class);
        assertThat(jdbc.connectionsObtained()).isZero();
    }

    @Test
    void sqlWithoutTenantPlaceholderIsRejectedBeforeDatabase() {
        TenantContext.runWith(TA, () -> tx.executeWithoutResult(s -> {
            int before = jdbc.statements().size();
            assertThatThrownBy(repository::unscoped).isInstanceOf(MissingTenantPredicateException.class);
            assertThatThrownBy(repository::lookalikePlaceholder).isInstanceOf(MissingTenantPredicateException.class);
            assertThat(jdbc.statements()).hasSize(before);
        }));
    }

    @Test
    void callerCannotSupplyTenantParameter() {
        TenantContext.runWith(TA, () -> tx.executeWithoutResult(s ->
                assertThatThrownBy(repository::callerSuppliedTenant).isInstanceOf(IllegalArgumentException.class)));
        assertThat(jdbc.statements()).noneMatch(sql -> sql.startsWith("DELETE"));
    }
}
