package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static com.ga.disclosure.infra.TriggerAssertions.sqlStateOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V19(6B 중간 회신 ③): 계약 연결의 이전. 행은 {@code superseded_by}(같은 확인서) 또는 {@code carried_to}(다른 확인서) 중 하나로 한 번 닫힌다(GD130).
 * 이전 대상은 다른 확인서의 같은 증권번호 행이다(GD136, 커밋 때). 확인서마다 연결 사슬의 끝은 1행이고 현재값은 그 행과 같다 — 이전된 끝이면 옛 확인서의
 * 현재값은 넘긴 시점의 값으로 남는다. 증권 부분 유일은 활성 행만이라 이전 뒤 새 확인서가 같은 증권을 쥘 수 있다.
 */
class V19GuardIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("V19");

    @BeforeAll
    static void seed() {
        DB.seed(T, c -> {
            SeedData.tenant(c, T);
            SeedData.dataKey(c, T, SeedData.SEED_KEY_ID);
            SeedData.customer(c, T, SeedData.SEED_CUSTOMER_REF);
        });
    }

    private static UUID completed() {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> id[0] = SeedData.disclosure(c, T, "COMPLETED", SeedData.hash('a')));
        return id[0];
    }

    private static UUID linked(UUID disclosure, String policy) {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> id[0] = SeedData.contractLink(c, T, disclosure, policy, "2026-10-01"));
        return id[0];
    }

    /** 옛 행을 닫고(carried_to) 새 확인서에 새 행을 넣고 현재값을 맞춘다 — 인수·이월 유스케이스의 모양. */
    private static void carry(Connection c, UUID from, UUID to, UUID toDisclosure, String policy) throws SQLException {
        SeedData.exec(c, "UPDATE contract_link SET carried_to = ?, carried_at = now() WHERE tenant_id = ? AND link_id = ?", to, T, from);
        SeedData.exec(c, """
                INSERT INTO contract_link (tenant_id, link_id, disclosure_id, policy_no, contract_date, insurer_code, source, source_ref, received_at, linked_by)
                VALUES (?, ?, ?, ?, DATE '2026-10-01', 'INS-A', 'SEED', ?, now(), 'seed')
                """, T, to, toDisclosure, policy, to.toString());
        SeedData.exec(c, "UPDATE disclosure SET policy_no = ?, contract_date = DATE '2026-10-01' WHERE tenant_id = ? AND disclosure_id = ?", policy, T, toDisclosure);
    }

    @Test
    void aLinkIsCarriedToAnotherDisclosureOnceAndTheOldDisclosureKeepsItsLastValues() {
        UUID a = completed();
        UUID b = completed();
        UUID from = linked(a, "POL-C1");
        UUID to = UUID.randomUUID();
        DB.asAppCommitting(T, c -> {
            carry(c, from, to, b, "POL-C1");
            return null;
        });
        assertThat(DB.<String>asApp(T, c -> SeedData.call(c, "SELECT policy_no FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", T, a)))
                .isEqualTo("POL-C1");
        assertThat(DB.<String>asApp(T, c -> SeedData.call(c, "SELECT policy_no FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", T, b)))
                .isEqualTo("POL-C1");
        // 닫힌 행은 다시 닫히지 않는다(이전·정정 어느 쪽으로도)
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> SeedData.exec(c,
                "UPDATE contract_link SET carried_to = NULL, carried_at = NULL WHERE tenant_id = ? AND link_id = ?", T, from)))).isEqualTo("GD130");
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> SeedData.exec(c,
                "UPDATE contract_link SET superseded_by = ?, superseded_at = now() WHERE tenant_id = ? AND link_id = ?", from, T, from)))).isEqualTo("GD130");
    }

    @Test
    void aLinkClosesOneWayAndIsInsertedOpen() {
        UUID a = completed();
        UUID from = linked(a, "POL-C2");
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> SeedData.exec(c,
                "UPDATE contract_link SET superseded_by = ?, superseded_at = now(), carried_to = ?, carried_at = now() WHERE tenant_id = ? AND link_id = ?",
                UUID.randomUUID(), UUID.randomUUID(), T, from)))).isEqualTo("GD130");
        UUID b = completed();
        assertThat(sqlStateOf(() -> DB.asApp(T, c -> SeedData.exec(c, """
                INSERT INTO contract_link (tenant_id, link_id, disclosure_id, policy_no, contract_date, insurer_code, source, source_ref, received_at,
                                           linked_by, carried_to, carried_at)
                VALUES (?, gen_random_uuid(), ?, 'POL-C2X', DATE '2026-10-01', 'INS-A', 'SEED', 'closed-insert', now(), 'seed', ?, now())
                """, T, b, from)))).isEqualTo("GD130");
    }

    @Test
    void aLinkIsCarriedOnlyToAnotherDisclosureWithTheSamePolicy() {
        UUID a = completed();
        UUID from = linked(a, "POL-C3");
        UUID b = completed();
        // 다른 증권번호로는 넘기지 못한다
        assertThat(sqlStateOf(() -> DB.asAppCommitting(T, c -> {
            carry(c, from, UUID.randomUUID(), b, "POL-C3-OTHER");
            return null;
        }))).isEqualTo("GD136");
        // 같은 확인서로 "이전"하면 사슬의 끝이 둘(끝 1행 부분 유일) — 정정은 superseded_by로만
        assertThat(sqlStateOf(() -> DB.asAppCommitting(T, c -> {
            carry(c, from, UUID.randomUUID(), a, "POL-C3");
            return null;
        }))).isEqualTo("23505");
        // 옛 행만 닫고 새 행을 넣지 않으면 외래키가 커밋 때 거부한다
        assertThat(sqlStateOf(() -> DB.asAppCommitting(T, c -> SeedData.exec(c,
                "UPDATE contract_link SET carried_to = ?, carried_at = now() WHERE tenant_id = ? AND link_id = ?", UUID.randomUUID(), T, from))))
                .isEqualTo("23503");
    }
}
