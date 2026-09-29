package com.ga.disclosure.infra;

import com.ga.disclosure.domain.enums.GateMode;
import com.ga.disclosure.domain.enums.IssuerMode;
import com.ga.disclosure.infra.persistence.TenantRecord;
import com.ga.disclosure.infra.persistence.TenantRepository;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.core.tenant.TenantNotBoundException;
import com.ga.platform.spring.jdbc.AmbiguousResultException;
import com.ga.platform.spring.jdbc.OutsideTenantTransactionException;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import com.ga.platform.spring.jdbc.TenantSessionBinder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 PostgreSQL에서 저장소 패턴을 확인한다: 자기 테넌트 행만, 미바인딩·트랜잭션 밖 거부(C1·C4의 DB판),
 * 트랜잭션 종료 후 커넥션에 테넌트가 남지 않음, 단건 조회의 Ambiguous fail-fast, tenant.params 기본값.
 */
class TenantRepositoryIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final TenantId A = TenantId.of(SeedData.uniqueTenant("TR_A"));
    private static final TenantId B = TenantId.of(SeedData.uniqueTenant("TR_B"));

    private final TenantJdbcGateway gateway = new TenantJdbcGateway(DB.appDataSource());
    private final TenantRepository repository = new TenantRepository(gateway);
    private final TransactionTemplate tx = new TransactionTemplate(new TenantSessionBinder(DB.appDataSource()));

    @BeforeAll
    static void seed() {
        DB.seed(A.value(), c -> SeedData.everyTable(c, A.value()));
        DB.seed(B.value(), c -> SeedData.everyTable(c, B.value()));
    }

    @Test
    void findsOnlyItsOwnTenantWithDefaultParams() throws Exception {
        Optional<TenantRecord> found = TenantContext.runWith(A, () -> tx.execute(s -> repository.findCurrent()));
        assertThat(found).get().satisfies(t -> {
            assertThat(t.tenantId()).isEqualTo(A);
            assertThat(t.issuerMode()).isEqualTo(IssuerMode.SELF);
            assertThat(t.gateMode()).isEqualTo(GateMode.WARN);
            assertThat(t.largeGa()).isTrue();
            assertThat(t.paramsJson().replace(" ", "")).isEqualTo("{\"gateRequiresManager\":true}");
        });
    }

    @Test
    void unboundOrOutsideTransactionNeverReachesDatabase() {
        assertThatThrownBy(repository::findCurrent).isInstanceOf(TenantNotBoundException.class);
        TenantContext.runWith(A, () -> {
            assertThatThrownBy(repository::findCurrent).isInstanceOf(OutsideTenantTransactionException.class);
        });
    }

    @Test
    void sessionSettingDoesNotLeakPastTransaction() throws Exception {
        // 커넥션 풀 재사용 상황: 같은 물리 커넥션으로 트랜잭션 안과 밖을 차례로 관찰한다.
        try (Connection physical = DB.appDataSource().getConnection()) {
            SingleConnectionDataSource single = new SingleConnectionDataSource(physical, true);
            JdbcClient raw = JdbcClient.create(single);
            TransactionTemplate singleTx = new TransactionTemplate(new TenantSessionBinder(single));

            String inside = TenantContext.runWith(A, () -> singleTx.execute(s ->
                    raw.sql("SELECT current_setting('app.tenant_id', true)").query(String.class).single()));
            String after = raw.sql("SELECT coalesce(current_setting('app.tenant_id', true), '<null>')").query(String.class).single();
            long visibleAfter = raw.sql("SELECT count(*) FROM tenant").query(Long.class).single();

            assertThat(inside).isEqualTo(A.value());
            assertThat(after).isIn("", "<null>");
            assertThat(visibleAfter).isZero();
        }
    }

    @Test
    void atMostOneFailsFastOnTwoRows() {
        ProbeRepository probe = new ProbeRepository(gateway);
        TenantContext.runWith(A, () -> tx.executeWithoutResult(s -> {
            assertThat(probe.sealedOrDraftDisclosureCount()).isEqualTo(2);
            assertThatThrownBy(probe::anyDisclosure).isInstanceOf(AmbiguousResultException.class);
        }));
    }

    /** 테스트 전용 저장소: 같은 테넌트에 2건 있는 조건으로 단건 조회를 시도한다. */
    static final class ProbeRepository extends TenantScopedRepository {
        ProbeRepository(TenantJdbcGateway gateway) {
            super(gateway);
        }

        long sealedOrDraftDisclosureCount() {
            return query("SELECT count(*) FROM disclosure WHERE tenant_id = :tenantId", Map.of(), (rs, i) -> rs.getLong(1)).getFirst();
        }

        Optional<String> anyDisclosure() {
            return queryAtMostOne("SELECT status FROM disclosure WHERE tenant_id = :tenantId", Map.of(), (rs, i) -> rs.getString(1));
        }
    }
}
