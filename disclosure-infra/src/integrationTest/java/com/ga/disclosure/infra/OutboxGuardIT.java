package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static com.ga.disclosure.infra.TriggerAssertions.assertRejected;
import static com.ga.disclosure.infra.TriggerAssertions.sqlStateOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V8 GD106(승인 Q13): 아웃박스는 테넌트 내 갭 없는 seq(계약 envelope). 이벤트는 머리 + 1로만 들어가고 미발행으로 쓰이며, 머리는 1에서
 * 시작해 +1씩 실재 이벤트를 가리킨다. 이후 이벤트는 발행 시각 1회 기록만 바뀌고 지워지지 않는다. 테스트마다 새 테넌트(머리가 테넌트당 하나).
 */
class OutboxGuardIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final UUID AGGREGATE = UUID.fromString("00000000-0000-4000-8000-000000000001");

    private static String tenant() {
        String t = SeedData.uniqueTenant("OBX");
        DB.seed(t, c -> SeedData.tenant(c, t));
        return t;
    }

    private static String event(String type, String published) {
        return """
                INSERT INTO outbox_event (tenant_id, seq, event_id, type, version, occurred_at, aggregate_kind, aggregate_id, payload, published_at)
                VALUES (?, ?, gen_random_uuid(), '%s', 1, TIMESTAMPTZ '2026-09-23 10:00:00+09', 'DISCLOSURE', ?, '{}'::jsonb, %s)
                """.formatted(type, published);
    }

    private static final String EVENT = event("DisclosureSealed", "NULL");

    @Test
    void appendsHeadPlusOneThenAdvancesTheHead() {
        String t = tenant();
        DB.asAppCommitting(t, c -> {
            SeedData.exec(c, EVENT, t, 1L, AGGREGATE.toString());
            SeedData.exec(c, "INSERT INTO outbox_head (tenant_id, seq) VALUES (?, 1)", t);
            SeedData.exec(c, EVENT, t, 2L, AGGREGATE.toString());
            return SeedData.exec(c, "UPDATE outbox_head SET seq = 2 WHERE tenant_id = ?", t);
        });
        long count = DB.asApp(t, c -> SeedData.longValue(c, "SELECT count(*) FROM outbox_event WHERE tenant_id = ?", t));
        assertThat(count).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 2, 3})
    void firstEventIsSeqOne(long seq) {
        String t = tenant();
        assertRejected(DB, t, "GD106", EVENT, t, seq, AGGREGATE.toString());
    }

    @Test
    void gapOrRepeatAfterTheHeadRejected() {
        String t = tenant();
        DB.seed(t, c -> SeedData.outboxEvent(c, t, AGGREGATE));
        assertRejected(DB, t, "GD106", EVENT, t, 3L, AGGREGATE.toString());
        assertRejected(DB, t, "GD106", EVENT, t, 1L, AGGREGATE.toString());
    }

    @Test
    void eventWithoutAdvancingTheHeadBlocksTheNextOne() {
        // 머리를 옮기지 않으면 그 뒤 이벤트는 여전히 머리 + 1만 허용된다 — 갭이 생기지 않는다
        String t = tenant();
        DB.seed(t, c -> SeedData.outboxEvent(c, t, AGGREGATE));
        assertThat(sqlStateOf(() -> DB.asApp(t, c -> {
            SeedData.exec(c, EVENT, t, 2L, AGGREGATE.toString());
            return SeedData.exec(c, EVENT, t, 3L, AGGREGATE.toString());
        }))).isEqualTo("GD106");
    }

    @Test
    void writtenUnpublished() {
        String t = tenant();
        assertRejected(DB, t, "GD106", event("DisclosureSealed", "TIMESTAMPTZ '2026-09-23 10:00:01+09'"), t, 1L, AGGREGATE.toString());
    }

    @Test
    void typeVocabularyIsTheContractsEight() {
        String t = tenant();
        assertRejected(DB, t, "23514", event("DisclosureDeleted", "NULL"), t, 1L, AGGREGATE.toString());
    }

    // ------------------------------------------------------------------ 머리

    @Test
    void headStartsAtOneAndPointsAtAnEvent() {
        String t = tenant();
        assertRejected(DB, t, "GD106", "INSERT INTO outbox_head (tenant_id, seq) VALUES (?, 2)", t);
        assertRejected(DB, t, "GD106", "INSERT INTO outbox_head (tenant_id, seq) VALUES (?, 1)", t);   // 이벤트 1이 없다
    }

    @Test
    void headAdvancesByExactlyOneOntoAnEvent() {
        String t = tenant();
        DB.seed(t, c -> {
            SeedData.outboxEvent(c, t, AGGREGATE);
            SeedData.outboxEvent(c, t, AGGREGATE);
        });
        assertRejected(DB, t, "GD106", "UPDATE outbox_head SET seq = 4 WHERE tenant_id = ?", t);
        assertRejected(DB, t, "GD106", "UPDATE outbox_head SET seq = 1 WHERE tenant_id = ?", t);
        assertRejected(DB, t, "GD106", "UPDATE outbox_head SET seq = 3 WHERE tenant_id = ?", t);       // 이벤트 3이 없다
    }

    // ------------------------------------------------------------------ 발행 기록·삭제 없음

    @Test
    void eventsChangeOnlyByRecordingPublicationOnce() {
        String t = tenant();
        DB.seed(t, c -> SeedData.outboxEvent(c, t, AGGREGATE));
        String where = " WHERE tenant_id = ? AND seq = 1";
        assertRejected(DB, t, "42501", "UPDATE outbox_event SET payload = '{\"x\": 1}'" + where, t);
        assertThat(sqlStateOf(() -> DB.seed(t, c -> SeedData.exec(c, "UPDATE outbox_event SET payload = '{\"x\": 1}'" + where, t))))
                .isEqualTo("GD106");
        DB.asAppCommitting(t, c -> SeedData.exec(c, "UPDATE outbox_event SET published_at = TIMESTAMPTZ '2026-09-23 10:00:02+09'" + where, t));
        assertRejected(DB, t, "GD106", "UPDATE outbox_event SET published_at = TIMESTAMPTZ '2026-09-23 10:00:03+09'" + where, t);
        assertRejected(DB, t, "GD106", "UPDATE outbox_event SET published_at = NULL" + where, t);
    }

    @Test
    void neverRemoved() {
        String t = tenant();
        DB.seed(t, c -> SeedData.outboxEvent(c, t, AGGREGATE));
        assertRejected(DB, t, "42501", "DELETE FROM outbox_event WHERE tenant_id = ?", t);
        assertRejected(DB, t, "42501", "DELETE FROM outbox_head WHERE tenant_id = ?", t);
        assertThat(sqlStateOf(() -> DB.seed(t, c -> SeedData.exec(c, "DELETE FROM outbox_event WHERE tenant_id = ?", t)))).isEqualTo("GD106");
        assertThat(sqlStateOf(() -> DB.seed(t, c -> SeedData.exec(c, "DELETE FROM outbox_head WHERE tenant_id = ?", t)))).isEqualTo("GD106");
        assertThat(sqlStateOf(() -> DB.seed(t, c -> SeedData.exec(c, "TRUNCATE outbox_event")))).isEqualTo("GD106");
        assertThat(sqlStateOf(() -> DB.seed(t, c -> SeedData.exec(c, "TRUNCATE outbox_head")))).isEqualTo("GD106");
    }
}
