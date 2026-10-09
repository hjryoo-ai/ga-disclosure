package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6B 중간 회신 ④: 법적 보류 설정과 폐기·파기의 경합. 두 트랜잭션을 실제로 교차시킨다 — 먼저 시작한 쪽이 잠금을 쥔 채 멈추고, 나중 쪽이 <b>잠금을 기다리는
 * 것을 확인한 뒤</b> 먼저 쪽을 커밋한다. 양쪽 순서 모두에서 "보류가 지워진 값을 지키는 것처럼 보이는" 결과는 0이다.
 * <ul>
 *   <li>지우는 쪽이 먼저: 보류는 기다렸다가 지워진 대상을 만나 거부된다(GD139). 고객 보류는 초안 폐기 뒤에도 걸린다 — 고객은 살아 있고, 폐기된 초안의 값은
 *       보류보다 먼저 지워졌다.</li>
 *   <li>보류가 먼저: 지우는 쪽은 기다렸다가 보류를 보고 거부된다(폐기 GD137, 파기 GD114).</li>
 * </ul>
 * 잠금 순서는 확인서 → customer_ref → 세션(설계서 §9). 지우는 쪽은 확인서 FOR UPDATE 뒤 고객 FOR SHARE, 보류는 대상 행 하나만 FOR UPDATE.
 */
class LegalHoldRaceIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("RACE");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-10T09:00:00+09:00");
    private static final Duration WAIT = Duration.ofSeconds(20);

    @BeforeAll
    static void seed() {
        DB.seed(T, c -> {
            SeedData.tenant(c, T);
            SeedData.dataKey(c, T, SeedData.SEED_KEY_ID);
        });
    }

    /** 시나리오마다 다른 고객(가명 형식 CR-{32 hex}). */
    private static String customer(int n) {
        return "CR-" + "0".repeat(31) + n;
    }

    /** 고객 하나에 확인서 하나 — 시나리오마다 새 고객이라 고객 보류가 서로 섞이지 않는다. */
    private static UUID disclosure(String status, String customer) {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> {
            SeedData.customer(c, T, customer);
            id[0] = SeedData.disclosure(c, T, status, SeedData.hash('a'), "DISC-2026-07", customer);
            if (!status.equals("DRAFT")) {
                SeedData.documentKey(c, T, id[0]);
            }
        });
        return id[0];
    }

    /** 앱 롤 연결, 테넌트 바인딩, 자동 커밋 끔. */
    private static Connection app() throws SQLException {
        Connection c = DB.appDataSource().getConnection();
        c.setAutoCommit(false);
        SeedData.call(c, "SELECT set_config('app.tenant_id', ?, true)", T);
        return c;
    }

    private static int pid(Connection c) throws SQLException {
        return Integer.parseInt(SeedData.call(c, "SELECT pg_backend_pid()::text"));
    }

    interface Step {
        void run(Connection c) throws SQLException;
    }

    private static Step abandon(UUID draft) {
        return c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_abandoner");
            SeedData.call(c, "SELECT ga_draft_abandon(?, ?, ?, ?)", T, draft, AT, "agent-1");
        };
    }

    private static Step shred(UUID disclosure) {
        return c -> {
            SeedData.exec(c, "SET LOCAL ROLE disclosure_destroyer");
            SeedData.call(c, "SELECT ga_document_key_shred(?, ?, DATE '2200-01-01', ?, ?)", T, disclosure, AT, "scheduler");
        };
    }

    private static Step holdDisclosure(UUID disclosure) {
        return c -> SeedData.exec(c, "INSERT INTO legal_hold (tenant_id, hold_id, disclosure_id, reason_code, placed_by, placed_at) "
                + "VALUES (?, gen_random_uuid(), ?, 'LITIGATION', 'ops', now())", T, disclosure);
    }

    private static Step holdCustomer(String customer) {
        return c -> SeedData.exec(c, "INSERT INTO legal_hold (tenant_id, hold_id, customer_ref, reason_code, placed_by, placed_at) "
                + "VALUES (?, gen_random_uuid(), ?, 'LITIGATION', 'ops', now())", T, customer);
    }

    /**
     * {@code first}를 실행하고 커밋하지 않은 채, {@code second}를 다른 연결에서 시작한다. {@code second}가 잠금을 기다리는 것을 본 뒤 {@code first}를
     * 커밋하고, {@code second}의 결과(성공이면 빈 값, 실패면 SQLSTATE)를 돌려준다. 기다리지 않으면(직렬화 안 됨) 실패한다.
     */
    private static Optional<String> race(Step first, Step second) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection a = app(); Connection b = app()) {
            first.run(a);
            int waiter = pid(b);
            CompletableFuture<Optional<String>> other = CompletableFuture.supplyAsync(() -> {
                try {
                    second.run(b);
                    b.commit();
                    return Optional.empty();
                } catch (SQLException e) {
                    try {
                        b.rollback();
                    } catch (SQLException ignored) {
                        // 연결 종료가 정리한다
                    }
                    return Optional.of(e.getSQLState());
                }
            }, pool);
            long deadline = System.nanoTime() + WAIT.toNanos();
            boolean waiting = false;
            while (System.nanoTime() < deadline && !other.isDone()) {
                waiting = DB.<String>asApp(null, c -> SeedData.call(c,
                        "SELECT coalesce(max(wait_event_type), '-') FROM pg_stat_activity WHERE pid = ?", waiter)).equals("Lock");
                if (waiting) {
                    break;
                }
                Thread.sleep(20);
            }
            assertThat(waiting).as("the second transaction waits for the first one's row lock").isTrue();
            a.commit();
            return other.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static long activeHolds(UUID disclosure) {
        return Long.parseLong(DB.asApp(T, c -> SeedData.call(c,
                "SELECT count(*)::text FROM legal_hold WHERE tenant_id = ? AND disclosure_id = ? AND released_at IS NULL", T, disclosure)));
    }

    private static String status(UUID disclosure) {
        return DB.asApp(T, c -> SeedData.call(c, "SELECT status FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", T, disclosure));
    }

    @Test
    void abandonmentFirstThenADisclosureHoldMeetsTheTombstone() throws Exception {
        UUID draft = disclosure("DRAFT", customer(1));
        assertThat(race(abandon(draft), holdDisclosure(draft))).contains("GD139");
        assertThat(status(draft)).isEqualTo("ABANDONED");
        assertThat(activeHolds(draft)).isZero();
    }

    @Test
    void aDisclosureHoldFirstThenAbandonmentSeesIt() throws Exception {
        UUID draft = disclosure("DRAFT", customer(2));
        assertThat(race(holdDisclosure(draft), abandon(draft))).contains("GD137");
        assertThat(status(draft)).isEqualTo("DRAFT");
        assertThat(activeHolds(draft)).isOne();
    }

    @Test
    void aCustomerHoldFirstThenAbandonmentSeesIt() throws Exception {
        UUID draft = disclosure("DRAFT", customer(3));
        assertThat(race(holdCustomer(customer(3)), abandon(draft))).contains("GD137");
        assertThat(status(draft)).isEqualTo("DRAFT");
    }

    @Test
    void abandonmentFirstThenACustomerHoldWaitsAndIsPlacedAfterTheErasure() throws Exception {
        UUID draft = disclosure("DRAFT", customer(4));
        // 고객은 살아 있으므로 보류는 걸린다 — 그러나 폐기가 커밋된 뒤에만(그 초안의 값은 보류보다 먼저 지워졌다)
        assertThat(race(abandon(draft), holdCustomer(customer(4)))).isEmpty();
        assertThat(status(draft)).isEqualTo("ABANDONED");
    }

    @Test
    void keyShreddingFirstThenADisclosureHoldMeetsTheShreddedKey() throws Exception {
        UUID done = disclosure("COMPLETED", customer(5));
        assertThat(race(shred(done), holdDisclosure(done))).contains("GD139");
        assertThat(activeHolds(done)).isZero();
    }

    @Test
    void aDisclosureHoldFirstThenKeyShreddingSeesIt() throws Exception {
        UUID done = disclosure("COMPLETED", customer(6));
        assertThat(race(holdDisclosure(done), shred(done))).contains("GD114");
        assertThat(DB.<String>asApp(T, c -> SeedData.call(c,
                "SELECT (wrapped_dek IS NOT NULL)::text FROM document_key WHERE tenant_id = ? AND disclosure_id = ?", T, done))).isEqualTo("true");
    }

    @Test
    void aCustomerHoldFirstThenKeyShreddingSeesIt() throws Exception {
        UUID done = disclosure("COMPLETED", customer(7));
        assertThat(race(holdCustomer(customer(7)), shred(done))).contains("GD114");
    }
}
