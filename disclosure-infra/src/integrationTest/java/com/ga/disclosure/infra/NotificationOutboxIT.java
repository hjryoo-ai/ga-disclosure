package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.pii.CustomerName;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.customer.CustomerRefService;
import com.ga.disclosure.workflow.customer.NewCustomer;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.workflow.disclosure.NotificationDispatcher;
import com.ga.disclosure.workflow.disclosure.SignSessionService;
import com.ga.disclosure.workflow.sign.NotificationStore;
import com.ga.disclosure.workflow.sign.SignLink;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 원격 링크 통지 아웃박스(6A 계획 §7, G10): 발급은 세션·아웃박스·감사를 한 트랜잭션에(롤백이면 0행), 토큰은 보낼 때 만든다. 발송 실패는 롤백(토큰 해시 없음) 뒤
 * 룰 {@code notify.retry} 산식의 재시도, 소진은 DEAD + 플래그 {@code NOTIFY_FAILED} 1건. 번호 없음은 즉시 DEAD, 닫힌·만료 세션은 CANCELLED. 토큰 원문은
 * 아웃박스·세션·감사 어디에도 없다. GD123(토큰은 발송과 함께 1회).
 */
class NotificationOutboxIT {

    static SignSetup withRetry(int maxAttempts, int initial, int multiplier, int max) {
        return new SignSetup(new SealSetup(WorkflowSetup.withRule(body -> ((ObjectNode) body.get("notify").get("retry")).put("maxAttempts", maxAttempts)
                .put("initialDelaySeconds", initial).put("multiplier", multiplier).put("maxDelaySeconds", max))));
    }

    static UUID queue(SignSetup x, DisclosureId id) {
        SignSessionService.IssueOutcome o = x.sessionService.issue(Callers.of(x.w.tenant, SignSetup.AGENT), id, SignatureChannel.REMOTE_LINK);
        assertThat(o.issued()).as("%s", o.rejections()).isTrue();
        return o.notificationId().orElseThrow();
    }

    static String row(SignSetup x, UUID notificationId) {
        return x.s.text("SELECT status || ':' || attempts || ':' || coalesce(last_error_code, '-') FROM notification_outbox"
                + " WHERE tenant_id = ? AND notification_id = ?", x.w.tenant.value(), notificationId);
    }

    static Instant nextAttempt(SignSetup x, UUID notificationId) {
        return Instant.parse(x.s.text("SELECT to_char(next_attempt_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM notification_outbox"
                + " WHERE tenant_id = ? AND notification_id = ?", x.w.tenant.value(), notificationId));
    }

    static String sessionOf(SignSetup x, UUID notificationId) {
        return x.s.text("SELECT session_id::text FROM notification_outbox WHERE tenant_id = ? AND notification_id = ?", x.w.tenant.value(),
                notificationId);
    }

    static List<AuditRecord> audit(SignSetup x, AuditAction action) {
        return x.s.audit().stream().filter(r -> r.entry().action() == action).toList();
    }

    /** 발급 트랜잭션이 롤백되면 세션도 아웃박스 행도 없다 — 둘은 한 트랜잭션이다. */
    @Test
    void anIssueThatRollsBackLeavesNeitherSessionNorNotification() {
        try (SignSetup x = new SignSetup()) {
            DisclosureId id = x.sealed();
            NotificationStore failing = new NotificationStore() {
                @Override
                public void insertPending(UUID notificationId, CustomerRef recipient, UUID sessionId, Instant at) {
                    x.outbox.insertPending(notificationId, recipient, sessionId, at);
                    throw new IllegalStateException("fail after the outbox insert");
                }

                @Override
                public List<UUID> due(Instant asOf, int limit) {
                    throw new AssertionError();
                }

                @Override
                public Optional<Pending> lockDue(UUID notificationId, Instant asOf) {
                    throw new AssertionError();
                }

                @Override
                public Optional<Pending> lockPending(UUID notificationId) {
                    throw new AssertionError();
                }

                @Override
                public void markSent(UUID notificationId, Instant at) {
                    throw new AssertionError();
                }

                @Override
                public void recordFailure(UUID notificationId, Instant nextAttemptAt, String errorCode) {
                    throw new AssertionError();
                }

                @Override
                public void close(UUID notificationId, Closed status, String errorCode, boolean countAttempt, Instant at) {
                    throw new AssertionError();
                }
            };
            SignSessionService broken = new SignSessionService(x.w.deps(x.clock), x.sessions, x.s.recordPort, x.s.cipher, x.s.store, x.tokens, failing, new com.ga.disclosure.workflow.sign.SignLinkBase(SignSetup.LINK_BASE));
            assertThatThrownBy(() -> broken.issue(Callers.of(x.w.tenant, SignSetup.AGENT), id, SignatureChannel.REMOTE_LINK))
                    .isInstanceOf(IllegalStateException.class);
            String t = x.w.tenant.value();
            assertThat(x.s.count("SELECT count(*) FROM sign_session WHERE tenant_id = ? AND disclosure_id = ?", t, id.value())).isZero();
            assertThat(x.s.count("SELECT count(*) FROM notification_outbox WHERE tenant_id = ?", t)).isZero();
            assertThat(audit(x, AuditAction.SIGN_SESSION_ISSUE)).isEmpty();
        }
    }

    /** 실패마다 룰의 산식(60·120·240·480초, 지터 없음), 5번째 실패에서 DEAD + 플래그 1건. 실패한 시도는 토큰 해시를 남기지 않는다. */
    @Test
    void failuresBackOffByTheRuleAndExhaustionRaisesOneFlag() {
        try (SignSetup x = new SignSetup()) {
            DisclosureId id = x.sealed();
            UUID n = queue(x, id);
            String session = sessionOf(x, n);
            x.notify.failing = "PROVIDER_DOWN";
            long[] expected = {60, 120, 240, 480};
            for (int attempt = 1; attempt <= 4; attempt++) {
                Instant before = x.clock.instant();
                NotificationDispatcher.Report r = x.dispatch();
                assertThat(r.retried()).as("attempt %d", attempt).containsExactly(n);
                assertThat(row(x, n)).isEqualTo("PENDING:" + attempt + ":PROVIDER_DOWN");
                assertThat(nextAttempt(x, n)).isEqualTo(before.plusSeconds(expected[attempt - 1]));
                assertThat(x.s.text("SELECT coalesce(token_hash, '-') || ':' || coalesce(sent_at::text, '-') FROM sign_session"
                        + " WHERE tenant_id = ? AND session_id = ?::uuid", x.w.tenant.value(), session)).as("rolled back").isEqualTo("-:-");
                assertThat(x.dispatch().retried()).as("not due yet").isEmpty();
                x.clock.advance(Duration.ofSeconds(expected[attempt - 1]));
            }
            assertThat(x.dispatch().dead()).containsExactly(n);
            assertThat(row(x, n)).isEqualTo("DEAD:5:PROVIDER_DOWN");
            assertThat(x.openFlags(id)).filteredOn(f -> f.type() == DisclosureFlagPort.Type.NOTIFY_FAILED).hasSize(1);
            assertThat(audit(x, AuditAction.NOTIFY_RETRY)).hasSize(4).allSatisfy(r -> assertThat(r.entry().detail().path("ruleVersionId").asString())
                    .startsWith("DISC-2026-07"));
            assertThat(audit(x, AuditAction.NOTIFY_DEAD)).singleElement().satisfies(r -> {
                assertThat(r.entry().targetId()).isEqualTo(n.toString());
                assertThat(r.entry().detail().path("attempts").asInt()).isEqualTo(5);
            });
            x.clock.advance(Duration.ofHours(2));
            assertThat(x.dispatch().dead()).as("a dead notification is never retried").isEmpty();
            assertThat(x.notify.links).isEmpty();
            assertThat(audit(x, AuditAction.CUSTOMER_PHONE_READ)).as("every failed send still records the read").hasSize(5);
        }
    }

    /** 산식의 값은 룰 데이터다 — 룰을 바꾸면(코드 변경 없이) 간격과 소진 횟수가 바뀐다. 곱이 상한을 넘으면 상한. */
    @Test
    void theBackoffIsRuleData() {
        try (SignSetup x = withRetry(3, 10, 3, 20)) {
            UUID n = queue(x, x.sealed());
            x.notify.failing = "PROVIDER_DOWN";
            Instant first = x.clock.instant();
            x.dispatch();
            assertThat(nextAttempt(x, n)).isEqualTo(first.plusSeconds(10));
            x.clock.advance(Duration.ofSeconds(10));
            Instant second = x.clock.instant();
            x.dispatch();
            assertThat(nextAttempt(x, n)).as("min(20, 10 × 3)").isEqualTo(second.plusSeconds(20));
            x.clock.advance(Duration.ofSeconds(20));
            assertThat(x.dispatch().dead()).containsExactly(n);
            assertThat(row(x, n)).isEqualTo("DEAD:3:PROVIDER_DOWN");
        }
    }

    /** 실패 뒤 성공: 새 토큰으로 보내고 SENT. 세션에는 마지막(성공한) 토큰의 해시만 있고, 토큰 원문은 어디에도 없다. */
    @Test
    void aLaterAttemptSendsAFreshTokenAndNoRowHoldsIt() {
        try (SignSetup x = new SignSetup()) {
            DisclosureId id = x.sealed();
            UUID n = queue(x, id);
            x.notify.failing = "PROVIDER_DOWN";
            x.dispatch();
            x.notify.failing = null;
            x.clock.advance(Duration.ofSeconds(60));
            assertThat(x.dispatch().sent()).containsExactly(n);
            assertThat(row(x, n)).isEqualTo("SENT:1:PROVIDER_DOWN");
            String token = x.notify.lastToken();
            String secret = token.substring(token.indexOf('~') + 1);
            String t = x.w.tenant.value();
            for (String sql : List.of("SELECT coalesce(string_agg(n::text, ''), '') FROM notification_outbox n WHERE tenant_id = ?",
                    "SELECT coalesce(string_agg(s::text, ''), '') FROM sign_session s WHERE tenant_id = ?",
                    "SELECT coalesce(string_agg(a::text, ''), '') FROM audit_log a WHERE tenant_id = ?",
                    "SELECT coalesce(string_agg(o::text, ''), '') FROM outbox_event o WHERE tenant_id = ?")) {
                assertThat(x.s.text(sql, t)).as(sql).doesNotContain(secret);
            }
            assertThat(audit(x, AuditAction.SIGN_SESSION_SEND)).singleElement().satisfies(r -> {
                assertThat(r.entry().detail().path("notificationId").asString()).isEqualTo(n.toString());
                assertThat(r.entry().detail().path("attempt").asInt()).isEqualTo(2);
                assertThat(r.entry().actorSubject()).isEqualTo(SignSetup.DISPATCHER);
                assertThat(r.entry().actorRole()).isEqualTo("OPERATOR");
            });
            // 실패한 시도의 번호 읽기도 감사에 남는다(그 트랜잭션은 롤백됐다 — 실패 기록이 다시 남긴다)
            assertThat(audit(x, AuditAction.CUSTOMER_PHONE_READ)).as("the number is read on every attempt").hasSize(2)
                    .anySatisfy(r -> assertThat(r.entry().detail().path("sendFailed").asString()).isEqualTo("PROVIDER_DOWN"));
            assertThat(x.sessionService.verify(token, com.ga.disclosure.workflow.sign.IdentityInputs.birthDate(SignSetup.BIRTH_COMPACT)).missing())
                    .isEmpty();
            assertThat(x.dispatch().sent()).as("sent once").isEmpty();
        }
    }

    /** 번호가 없는 고객은 재시도해도 번호가 생기지 않는다 — 즉시 DEAD(NO_PHONE) + 플래그, 번호 읽기 감사 없음. */
    @Test
    void aCustomerWithoutAPhoneIsDeadAtOnce() {
        try (SignSetup x = new SignSetup()) {
            CustomerRef noPhone = new CustomerRefService(x.w.vault, x.w.audit, x.w.tx, x.w.clock, Callers.authz(x.w.clock))
                    .register(Callers.of(x.w.tenant, CatalogCustomerSetup.OPERATOR), new NewCustomer(CustomerName.of("번호없는고객"), null, null));
            DisclosureId id = x.sealedFor(noPhone);
            UUID n = queue(x, id);
            assertThat(x.dispatch().dead()).containsExactly(n);
            assertThat(row(x, n)).isEqualTo("DEAD:0:NO_PHONE");
            assertThat(x.openFlags(id)).extracting(DisclosureFlagPort.OpenFlag::type).contains(DisclosureFlagPort.Type.NOTIFY_FAILED);
            assertThat(audit(x, AuditAction.CUSTOMER_PHONE_READ)).isEmpty();
        }
    }

    /** 재발급으로 닫힌 세션의 통지는 CANCELLED(SESSION_CLOSED), 만료가 지난 세션은 CANCELLED(SESSION_EXPIRED). 링크는 나가지 않는다. */
    @Test
    void closedAndExpiredSessionsAreCancelled() {
        try (SignSetup x = new SignSetup()) {
            DisclosureId id = x.sealed();
            UUID first = queue(x, id);
            UUID second = queue(x, id);                                       // 첫 세션을 REVOKED(REISSUED)로 닫는다
            x.clock.advance(Duration.ofHours(73));                            // remoteLinkTtlHours = 72
            NotificationDispatcher.Report r = x.dispatch();
            assertThat(r.cancelled()).containsExactlyInAnyOrder(first, second);
            assertThat(row(x, first)).isEqualTo("CANCELLED:0:SESSION_CLOSED");
            assertThat(row(x, second)).isEqualTo("CANCELLED:0:SESSION_EXPIRED");
            assertThat(audit(x, AuditAction.NOTIFY_CANCELLED)).hasSize(2);
            assertThat(x.notify.links).isEmpty();
        }
    }

    /** GD123: 토큰 해시는 발송 시각과 함께, 열린 원격 링크 세션에 1회만. 앱 롤로도 우회할 수 없다. */
    @Test
    void theTokenIsWrittenOnlyTogetherWithTheSendTime() {
        try (SignSetup x = new SignSetup()) {
            UUID n = queue(x, x.sealed());
            String session = sessionOf(x, n);
            String t = x.w.tenant.value();
            String hash = "a".repeat(64);
            assertThat(sqlState(x, t, "UPDATE sign_session SET token_hash = '" + hash + "' WHERE tenant_id = ? AND session_id = '" + session + "'"))
                    .as("without sent_at").isEqualTo("GD123");
            assertThat(sqlState(x, t, "UPDATE sign_session SET token_hash = '" + hash + "', sent_at = now() WHERE tenant_id = ? AND session_id = '"
                    + session + "'")).as("together").isNull();
        }
    }

    static String sqlState(SignSetup x, String tenant, String sql) {
        return x.w.db.asApp(tenant, (Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, tenant);
                ps.executeUpdate();
                return null;
            } catch (SQLException e) {
                return e.getSQLState();
            }
        });
    }

    @Test
    void aSignLinkKeepsTheTokenInTheFragmentAndOutOfToString() {
        com.ga.disclosure.sign.token.SignToken token = com.ga.disclosure.sign.token.SignToken.issue(com.ga.platform.core.tenant.TenantId.of("T1"),
                new SignSetup.SeededTokens("link"));
        SignLink link = SignLink.of(SignSetup.LINK_BASE, token);
        assertThat(link.reveal()).isEqualTo(SignSetup.LINK_BASE + token.reveal());
        assertThat(link.toString()).doesNotContain(token.reveal().substring(token.reveal().indexOf('~') + 1));
        assertThatThrownBy(() -> SignLink.of("https://sign.example.invalid/sign/", token)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new com.ga.disclosure.workflow.sign.NotifyFailure("provider said: 010-1234-5678"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
