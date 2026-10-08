package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.audit.outbox.EventType;
import com.ga.disclosure.audit.outbox.OutboxPayloads;
import com.ga.disclosure.audit.outbox.OutboxPort;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ga.disclosure.app.api.ApiTestSupport.DB;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * G8(6A 계획 §4.1, 승인 Q5): 이벤트 피드 {@code GET /internal/v1/events}·{@code POST /internal/v1/events/ack}. seq 오름차순·갭 없음, 정수
 * {@code afterSeq}·{@code nextSeq}·{@code headSeq}, ack 전 재요청은 같은 이벤트(at-least-once), ack 뒤 기본 시작점 이동, 모든 응답이 계약(envelope
 * 스키마 포함 — {@link ApiTestSupport#send}가 검증한다), 다른 테넌트 이벤트 0, {@code DisclosureDestroyed} 포함, 머리 너머는 422, 사람 역할은 404.
 *
 * <p>{@code DisclosureDestroyed}는 운영 적재 포트({@link OutboxPort} — 파기 경로가 쓰는 것과 같은 계약 검증·갭 없는 seq)로 넣는다. 그 이벤트를 만드는 파기
 * 경로 자체는 Phase 5 시험(DestructionOrderIT 등)이 다룬다 — 피드는 종류와 무관하게 행을 그대로 낸다.
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventFeedIT {

    static final String T = SeedData.uniqueTenant("FEED");
    static final String OTHER = SeedData.uniqueTenant("FEEDO");
    static String customer;
    static String otherCustomer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @BeforeAll
    static void prepare() {
        customer = FlowSupport.prepare(T);
        otherCustomer = FlowSupport.prepare(OTHER);
        for (String tenant : List.of(T, OTHER)) {
            DB.seed(tenant, c -> SeedData.roleLink(c, tenant, "feed-1", "FEED_CONSUMER"));
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    OutboxPort outbox;

    @Autowired
    WorkflowTransactions tx;

    /** 200 응답의 본문(아니면 상태·본문으로 실패). */
    static JsonNode json(ApiTestSupport.Response r) {
        assertThat(r.status()).as(r.text()).isEqualTo(200);
        return Canonicalizer.parseStrict(r.text());
    }

    ApiTestSupport.Response feed(String tenant, String query) {
        return ApiTestSupport.get(port, "/internal/v1/events" + query, TestJwts.token(tenant, "feed-1"));
    }

    ApiTestSupport.Response ack(String tenant, long upToSeq, String key) {
        return ApiTestSupport.post(port, "/internal/v1/events/ack", TestJwts.token(tenant, "feed-1"), "{\"upToSeq\":" + upToSeq + "}",
                Map.of("Idempotency-Key", key));
    }

    static List<Long> seqs(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("events").forEach(e -> out.add(e.get("seq").asLong()));
        return out;
    }

    static List<String> types(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("events").forEach(e -> out.add(e.get("type").asString()));
        return out;
    }

    long published(String tenant) {
        return DB.asApp(tenant, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM outbox_event WHERE published_at IS NOT NULL");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    @Test
    void theFeedIsAtLeastOnceInSeqOrderUntilAcked() {
        String id = FlowSupport.sealed(port, T, customer);
        String otherId = FlowSupport.sealed(port, OTHER, otherCustomer);

        JsonNode before = json(feed(T, ""));
        String disclosureNo = before.get("events").valueStream().filter(e -> e.get("type").asString().equals("DisclosureSealed")).findFirst()
                .orElseThrow().get("payload").get("disclosureNo").asString();
        tx.inTenant(TenantId.of(T), () -> outbox.append(EventType.DisclosureDestroyed, id, Instant.parse("2026-10-08T00:00:00Z"),
                OutboxPayloads.disclosureDestroyed(UUID.fromString(id), disclosureNo, Instant.parse("2026-10-08T00:00:00Z"))));

        // 기본 시작점(아직 ack 없음 = 0): 1..머리, 오름차순·갭 없음
        ApiTestSupport.Response firstResponse = feed(T, "");
        assertThat(firstResponse.status()).as(firstResponse.text()).isEqualTo(200);
        JsonNode first = json(firstResponse);
        long head = first.get("headSeq").asLong();
        assertThat(head).isGreaterThanOrEqualTo(3);
        List<Long> expected = new ArrayList<>();
        for (long s = 1; s <= head; s++) {
            expected.add(s);
        }
        assertThat(seqs(first)).isEqualTo(expected);
        assertThat(first.get("nextSeq").asLong()).isEqualTo(head);
        assertThat(first.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(types(first)).contains("DisclosureCreated", "DisclosureSealed", "DisclosureDestroyed");
        // 다른 테넌트 이벤트 0: 이 테넌트의 이벤트는 전부 이 확인서의 것이다
        first.get("events").forEach(e -> assertThat(e.get("aggregate").get("id").asString()).isEqualTo(id).isNotEqualTo(otherId));

        // 쪽 나누기: 정수 afterSeq → nextSeq
        JsonNode page1 = json(feed(T, "?afterSeq=0&limit=2"));
        assertThat(seqs(page1)).containsExactly(1L, 2L);
        assertThat(page1.get("nextSeq").asLong()).isEqualTo(2);
        JsonNode page2 = json(feed(T, "?afterSeq=" + page1.get("nextSeq").asLong() + "&limit=2"));
        assertThat(seqs(page2).getFirst()).isEqualTo(3L);
        JsonNode end = json(feed(T, "?afterSeq=" + head));
        assertThat(end.get("events")).isEmpty();
        assertThat(end.get("nextSeq").asLong()).isEqualTo(head);

        // ack 전 재요청은 같은 이벤트(at-least-once)
        assertThat(feed(T, "").text()).isEqualTo(firstResponse.text());
        assertThat(published(T)).isZero();

        // ack 2 → 기본 시작점이 3으로
        ApiTestSupport.Response acked = ack(T, 2, "ack-" + UUID.randomUUID());
        assertThat(acked.status()).as(acked.text()).isEqualTo(200);
        assertThat(json(acked).get("ackedSeq").asLong()).isEqualTo(2);
        assertThat(json(acked).get("headSeq").asLong()).isEqualTo(head);
        assertThat(seqs(json(feed(T, ""))).getFirst()).isEqualTo(3L);
        assertThat(published(T)).isEqualTo(2);
        // 이미 ack한 지점 이하는 아무것도 바꾸지 않는다, 같은 키는 같은 바이트(멱등 재생)
        String key = "ack-" + UUID.randomUUID();
        ApiTestSupport.Response lower = ack(T, 1, key);
        assertThat(json(lower).get("ackedSeq").asLong()).isEqualTo(2);
        ApiTestSupport.Response replayed = ack(T, 1, key);
        assertThat(replayed.text()).isEqualTo(lower.text());
        assertThat(replayed.headers()).containsEntry("idempotency-replayed", "true");
        assertThat(published(T)).isEqualTo(2);
        // 끝까지 ack → 기본 시작점에서 빈 쪽
        assertThat(json(ack(T, head, "ack-" + UUID.randomUUID())).get("ackedSeq").asLong()).isEqualTo(head);
        assertThat(json(feed(T, "")).get("events")).isEmpty();
        assertThat(published(T)).isEqualTo(head);

        // 다른 테넌트는 그대로(ack가 넘어가지 않는다)
        JsonNode other = json(feed(OTHER, ""));
        assertThat(seqs(other).getFirst()).isEqualTo(1L);
        other.get("events").forEach(e -> assertThat(e.get("aggregate").get("id").asString()).isEqualTo(otherId));
        assertThat(published(OTHER)).isZero();
    }

    @Test
    void beyondTheHeadIsRejectedAndHumansGetNotFound() {
        long head = json(feed(T, "?afterSeq=0&limit=1")).get("headSeq").asLong();
        ApiTestSupport.Response after = feed(T, "?afterSeq=" + (head + 5));
        assertThat(after.status()).isEqualTo(422);
        assertThat(after.text()).contains("AFTER_BEYOND_HEAD");
        ApiTestSupport.Response ackBeyond = ack(T, head + 5, "ack-" + UUID.randomUUID());
        assertThat(ackBeyond.status()).isEqualTo(422);
        assertThat(ackBeyond.text()).contains("ACK_BEYOND_HEAD");

        assertThat(feed(T, "?limit=0").status()).isEqualTo(400);
        assertThat(feed(T, "?limit=1001").status()).isEqualTo(400);
        assertThat(feed(T, "?afterSeq=-1").status()).isEqualTo(400);
        assertThat(ApiTestSupport.post(port, "/internal/v1/events/ack", TestJwts.token(T, "feed-1"), "{}", Map.of("Idempotency-Key",
                "ack-" + UUID.randomUUID())).status()).isEqualTo(400);

        // 사람 역할(설계사·준법)은 404 — 권한 없음과 없는 경로는 같은 응답
        ApiTestSupport.Response human = ApiTestSupport.get(port, "/internal/v1/events", TestJwts.token(T, "compliance-1"));
        assertThat(human.status()).isEqualTo(404);
        assertThat(ApiTestSupport.get(port, "/internal/v1/events", TestJwts.token(T, "agent-1")).fingerprint()).isEqualTo(human.fingerprint());
    }
}
