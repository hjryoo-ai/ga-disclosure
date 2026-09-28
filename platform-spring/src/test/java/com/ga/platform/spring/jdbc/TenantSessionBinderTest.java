package com.ga.platform.spring.jdbc;

import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** C4: 트랜잭션 밖 DB 접근 거부 + 트랜잭션 시작 시 테넌트 세션 바인딩. */
class TenantSessionBinderTest {

    private static final TenantId TA = TenantId.of("TA");
    private static final TenantId TB = TenantId.of("TB");

    private final FakeJdbc jdbc = new FakeJdbc();
    private final ProbeRepository repository = new ProbeRepository(new TenantScopedRepository.Gateway(jdbc.dataSource));
    private final TenantSessionBinder binder = new TenantSessionBinder(jdbc.dataSource);
    private final TransactionTemplate tx = new TransactionTemplate(binder);

    @Test
    void repositoryCallOutsideTransactionIsRejectedBeforeDatabase() {
        TenantContext.runWith(TA, () -> {
            assertThatThrownBy(repository::scoped).isInstanceOf(OutsideTenantTransactionException.class);
        });
        assertThat(jdbc.connectionsObtained()).isZero();
    }

    @Test
    void transactionNotStartedByBinderIsRejected() {
        TransactionTemplate foreign = new TransactionTemplate(new DataSourceTransactionManager(jdbc.dataSource));
        TenantContext.runWith(TA, () -> foreign.executeWithoutResult(s ->
                assertThatThrownBy(repository::scoped).isInstanceOf(OutsideTenantTransactionException.class)));
        assertThat(jdbc.statements()).isEmpty();
    }

    @Test
    void beginSetsTransactionLocalTenantBeforeAnyOtherStatement() {
        TenantContext.runWith(TA, () -> tx.executeWithoutResult(s -> {
            assertThat(TenantSessionBinder.boundTenant()).contains(TA);
            repository.scoped();
        }));

        List<String> statements = jdbc.statements();
        assertThat(statements.getFirst()).isEqualTo(TenantSessionBinder.SET_TENANT_SQL);
        assertThat(statements.getFirst()).contains("set_config('app.tenant_id', ?, true)");
        assertThat(jdbc.events).containsSubsequence(
                "getConnection", "prepare:" + TenantSessionBinder.SET_TENANT_SQL, "param:1=TA",
                "prepare:SELECT name FROM probe WHERE tenant_id = ?", "param:1=TA", "commit", "close");
        assertThat(TenantSessionBinder.boundTenant()).isEmpty();
    }

    @Test
    void insertGetsTenantInjectedIntoColumn() {
        TenantContext.runWith(TA, () -> tx.executeWithoutResult(s -> repository.insert("n1")));
        assertThat(jdbc.events).containsSubsequence(
                "prepare:INSERT INTO probe (tenant_id, name) VALUES (?, ?)", "param:1=TA", "param:2=n1");
    }

    @Test
    void rebindingToAnotherTenantInsideTransactionIsRejected() {
        TenantContext.runWith(TA, () -> tx.executeWithoutResult(s ->
                TenantContext.runWith(TB, () -> {
                    assertThatThrownBy(repository::scoped).isInstanceOf(TenantMismatchException.class);
                })));
        assertThat(jdbc.statements()).containsExactly(TenantSessionBinder.SET_TENANT_SQL);
    }

    @Test
    void requiresNewForAnotherTenantSuspendsAndRestoresBinding() {
        TransactionTemplate requiresNew = new TransactionTemplate(binder);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        TenantContext.runWith(TA, () -> tx.executeWithoutResult(outer -> {
            TenantContext.runWith(TB, () -> requiresNew.executeWithoutResult(inner -> {
                assertThat(TenantSessionBinder.boundTenant()).contains(TB);
                repository.scoped();
            }));
            assertThat(TenantSessionBinder.boundTenant()).contains(TA);
            repository.scoped();
        }));
        assertThat(jdbc.connectionsObtained()).isEqualTo(2);
        assertThat(jdbc.events).containsSubsequence("param:1=TA", "param:1=TB", "param:1=TB", "param:1=TA");
    }

    @Test
    void atMostOneOnEmptyResultIsEmpty() {
        TenantContext.runWith(TA, () -> tx.executeWithoutResult(s -> assertThat(repository.scopedAtMostOne()).isEmpty()));
    }

    @Test
    void autoConfigurationRegistersBinderAsTheTransactionManager() throws IOException {
        new ApplicationContextRunner()
                .withBean(DataSource.class, () -> jdbc.dataSource)
                .withConfiguration(AutoConfigurations.of(TenantSessionBinder.PlatformJdbcAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(PlatformTransactionManager.class);
                    assertThat(context.getBean(PlatformTransactionManager.class)).isInstanceOf(TenantSessionBinder.class);
                    assertThat(context).hasSingleBean(TenantScopedRepository.Gateway.class);
                });

        try (InputStream in = getClass().getResourceAsStream(
                "/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(in).isNotNull();
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .contains(TenantSessionBinder.PlatformJdbcAutoConfiguration.class.getName());
        }
    }
}
