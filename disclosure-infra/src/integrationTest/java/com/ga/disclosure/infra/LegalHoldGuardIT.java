package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.ga.disclosure.infra.TriggerAssertions.assertAllowed;
import static com.ga.disclosure.infra.TriggerAssertions.assertRejected;
import static com.ga.disclosure.infra.TriggerAssertions.sqlStateOf;
import static org.assertj.core.api.Assertions.assertThat;

/** V9 법적 보류(GD112): 대상은 확인서 또는 고객 하나, 대상당 활성 1건, PLACED → RELEASED 1회, 삭제 없음. 앱은 삽입·해제 컬럼 갱신만. */
class LegalHoldGuardIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("LHD");
    private static final String CUSTOMER = "CR-" + "0".repeat(31) + "9";
    private static UUID disclosure;

    private static final String PLACE = """
            INSERT INTO legal_hold (tenant_id, hold_id, disclosure_id, customer_ref, reason_code, placed_by, placed_at)
            VALUES (?, ?, ?, ?, 'LITIGATION', 'compliance@x', now())
            """;
    private static final String RELEASE = """
            UPDATE legal_hold SET released_at = now(), released_by = 'compliance-2@x', release_reason_code = 'CASE_CLOSED'
             WHERE tenant_id = ? AND hold_id = ?
            """;

    @BeforeAll
    static void seed() {
        DB.seed(T, c -> {
            SeedData.tenant(c, T);
            SeedData.dataKey(c, T, SeedData.SEED_KEY_ID);
            SeedData.customer(c, T, CUSTOMER);
            disclosure = SeedData.disclosure(c, T, "COMPLETED", SeedData.hash('a'));
        });
    }

    @Test
    void targetsAreExactlyOneAndOneActiveHoldEach() {
        assertRejected(DB, T, "23514", PLACE, T, UUID.randomUUID(), disclosure, CUSTOMER);
        assertRejected(DB, T, "23514", PLACE, T, UUID.randomUUID(), null, null);
        assertRejected(DB, T, "GD112", """
                INSERT INTO legal_hold (tenant_id, hold_id, disclosure_id, reason_code, placed_by, placed_at, released_by, released_at,
                                        release_reason_code)
                VALUES (?, ?, ?, 'LITIGATION', 'c@x', now(), 'c2@x', now(), 'X')
                """, T, UUID.randomUUID(), disclosure);
        UUID first = UUID.randomUUID();
        DB.asAppCommitting(T, c -> SeedData.exec(c, PLACE, T, first, disclosure, null));
        assertRejected(DB, T, "23505", PLACE, T, UUID.randomUUID(), disclosure, null);
        assertAllowed(DB, T, PLACE, T, UUID.randomUUID(), null, CUSTOMER);

        // 해제는 1회, 해제 컬럼만 — 그 밖은 앱 권한 없음(42501), 소유 롤도 트리거가 막는다(GD112)
        assertRejected(DB, T, "42501", "UPDATE legal_hold SET reason_code = 'OTHER' WHERE tenant_id = ? AND hold_id = ?", T, first);
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c,
                "UPDATE legal_hold SET reason_code = 'OTHER' WHERE tenant_id = ? AND hold_id = ?", T, first)))).isEqualTo("GD112");
        DB.asAppCommitting(T, c -> SeedData.exec(c, RELEASE, T, first));
        assertRejected(DB, T, "GD112", RELEASE, T, first);
        assertRejected(DB, T, "GD112", "UPDATE legal_hold SET released_at = NULL, released_by = NULL, release_reason_code = NULL"
                + " WHERE tenant_id = ? AND hold_id = ?", T, first);
        // 해제 뒤에는 같은 대상에 새 보류
        assertAllowed(DB, T, PLACE, T, UUID.randomUUID(), disclosure, null);
        // 삭제 없음
        assertRejected(DB, T, "42501", "DELETE FROM legal_hold WHERE tenant_id = ? AND hold_id = ?", T, first);
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, "DELETE FROM legal_hold WHERE tenant_id = ? AND hold_id = ?", T, first))))
                .isEqualTo("GD112");
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, "TRUNCATE legal_hold")))).isEqualTo("GD112");
    }
}
