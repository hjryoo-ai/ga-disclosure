package com.ga.disclosure.infra;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.infra.engine.EngineClientSettings;
import com.ga.disclosure.infra.engine.EngineGradeClient;
import com.ga.disclosure.infra.engine.HttpEngineTransport;
import com.ga.disclosure.infra.testing.FakeEngine;
import com.ga.disclosure.infra.testing.FakeEngine.Fault;
import com.ga.disclosure.workflow.disclosure.CommandResult;
import com.ga.disclosure.workflow.disclosure.EngineRequest;
import com.ga.disclosure.workflow.disclosure.EngineUnavailableException;
import com.ga.disclosure.workflow.disclosure.GradeSnapshotPort;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 3A W4: 엔진 응답의 계약 스키마 위반(UNAVAILABLE에 rankInSet)·순위 비단조·집합 불일치(누락·초과)·미허용 정책·미허용 tieBreak 각각 →
 * 스냅샷 미생성, {@code GRADE_INCONSISTENT} 플래그, 상태 COMPARED(업무 거부로 커밋). 엔진 오류 응답(422)·타임아웃은 명령 오류
 * ({@code COMMAND_FAILED}, 플래그 없음). 재시도는 연결 실패에만 — 응답 지연(요청 뒤 타임아웃)은 재시도하지 않는다.
 * 전부 실제 HTTP 어댑터와 계약 검증 페이크({@link FakeEngine})로 돈다.
 */
class GradeSnapshotIT {

    private final WorkflowSetup s = new WorkflowSetup();

    @AfterEach
    void stop() {
        s.close();
    }

    private String one(DisclosureId id, String sql) {
        return s.db.asApp(s.tenant.value(), c -> {
            try (var ps = c.prepareStatement(sql)) {
                ps.setString(1, s.tenant.value());
                ps.setObject(2, id.value());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
    }

    private void assertNoSnapshotAndFlagged(DisclosureId id) {
        assertThat(one(id, "SELECT status || '/' || coalesce(grade_snapshot_id, '-') FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?"))
                .isEqualTo("COMPARED/-");
        assertThat(one(id, "SELECT count(*) FROM disclosure_item WHERE tenant_id = ? AND disclosure_id = ? AND grade_status IS NOT NULL"))
                .isEqualTo("0");
        assertThat(one(id, """
                SELECT count(*) FROM compliance_flag
                 WHERE tenant_id = ? AND disclosure_id = ? AND type = 'GRADE_INCONSISTENT' AND resolved_at IS NULL""")).isEqualTo("1");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Fault.class, names = {"UNAVAILABLE_WITH_RANK", "NON_MONOTONIC", "MISSING_PRODUCT", "EXTRA_PRODUCT", "DISALLOWED_POLICY"})
    void invalidEngineResponsesNeverBecomeSnapshots(Fault fault) {
        DisclosureId id = s.compared();
        s.engine.fault(fault);
        CommandResult r = s.service.requestGrades(s.tenant, WorkflowSetup.AGENT, id);
        assertThat(r.rejectionOrNull()).isEqualTo(CommandResult.Rejection.GRADE_REJECTED);
        assertThat(r.status()).isEqualTo(DisclosureStatus.COMPARED);
        String expected = switch (fault) {
            case UNAVAILABLE_WITH_RANK -> "schema:";
            case NON_MONOTONIC -> "(iii)";
            case MISSING_PRODUCT -> "(i) snapshot lacks";
            case EXTRA_PRODUCT -> "(i) snapshot has products that were not requested";
            case DISALLOWED_POLICY -> "(iv) grading policy";
            default -> throw new IllegalArgumentException();
        };
        assertThat(r.engineViolations()).anySatisfy(v -> assertThat(v).contains(expected));
        assertNoSnapshotAndFlagged(id);
        assertThat(s.auditLog()).filteredOn(a -> a.entry().targetId() != null && a.entry().targetId().equals(id.toString()))
                .extracting(a -> a.entry().action().name()).containsSubsequence("GRADE_FETCH", "DISCLOSURE_REJECT");
        assertThat(s.engine.requestViolations()).isEmpty();
        assertThat(s.engine.selfCheckFailures()).isEmpty();

        // 같은 확인서의 두 번째 거부는 열린 플래그를 재사용한다(행이 늘지 않는다)
        s.service.requestGrades(s.tenant, WorkflowSetup.AGENT, id);
        assertNoSnapshotAndFlagged(id);
    }

    @Test
    void tieBreakOutsideTheRulesAllowanceIsRejectedByTheClient() {
        EngineGradeClient client = new EngineGradeClient(new HttpEngineTransport(s.settings, t -> Optional.of(WorkflowSetup.TOKEN),
                t -> s.engine.baseUrl()));
        EngineRequest request = new EngineRequest(WorkflowSetup.CONSULT, WorkflowSetup.GROUP,
                List.of(ProductKey.parse("INS-A:PRD-1001"), ProductKey.parse("INS-C:PRD-3120")));
        GradeSnapshotPort.Allowance sharedOnly = new GradeSnapshotPort.Allowance(Set.of("GRADING-2026-07"), Set.of("RANK-2026-07"),
                Set.of(TieBreak.SHARED_RANK));
        assertThat(client.request(s.tenant, request, sharedOnly)).isInstanceOf(GradeSnapshotPort.Fetch.Accepted.class);
        s.engine.fault(Fault.TIE_BREAK_STRICT);
        GradeSnapshotPort.Fetch f = client.request(s.tenant, request, sharedOnly);
        assertThat(f).isInstanceOfSatisfying(GradeSnapshotPort.Fetch.Rejected.class,
                r -> assertThat(r.violations()).anySatisfy(v -> assertThat(v).contains("(iv) tieBreak STRICT")));
    }

    @Test
    void refetchReturnsTheSameValidatedSnapshot() {
        EngineGradeClient client = new EngineGradeClient(new HttpEngineTransport(s.settings, t -> Optional.of(WorkflowSetup.TOKEN),
                t -> s.engine.baseUrl()));
        EngineRequest request = new EngineRequest(WorkflowSetup.CONSULT, WorkflowSetup.GROUP,
                List.of(ProductKey.parse("INS-A:PRD-1001"), ProductKey.parse("INS-C:PRD-3120")));
        GradeSnapshotPort.Allowance all = new GradeSnapshotPort.Allowance(Set.of("GRADING-2026-07"), Set.of("RANK-2026-07"),
                Set.of(TieBreak.SHARED_RANK, TieBreak.STRICT));
        GradeSnapshotPort.Fetch.Accepted first = (GradeSnapshotPort.Fetch.Accepted) client.request(s.tenant, request, all);
        GradeSnapshotPort.Fetch again = client.refetch(s.tenant, first.snapshot().snapshot().snapshotId(), request, all);
        assertThat(again).isEqualTo(first);
    }

    @Test
    void engineErrorResponseIsACommandFailureWithoutFlag() {
        DisclosureId id = s.compared();
        s.engine.fault(Fault.STATUS_422);
        EngineUnavailableException e = catchThrowableOfType(EngineUnavailableException.class,
                () -> s.service.requestGrades(s.tenant, WorkflowSetup.AGENT, id));
        assertThat(e.code()).isEqualTo("ENGINE_422_NO_POLICY");
        assertThat(one(id, "SELECT status FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?")).isEqualTo("COMPARED");
        assertThat(one(id, "SELECT count(*) FROM compliance_flag WHERE tenant_id = ? AND disclosure_id = ?")).isEqualTo("0");
        assertThat(s.auditLog()).filteredOn(a -> a.entry().action().name().equals("COMMAND_FAILED"))
                .singleElement().satisfies(a -> assertThat(a.entry().detail().get("code").asString()).isEqualTo("ENGINE_422_NO_POLICY"));
    }

    @Test
    void aResponseThatArrivesTooLateIsNotRetried() {
        try (WorkflowSetup slow = new WorkflowSetup("2026-09-23T01:00:00Z",
                new EngineClientSettings(Duration.ofSeconds(2), Duration.ofMillis(300), 3))) {
            DisclosureId id = slow.compared();
            slow.engine.fault(Fault.DELAY).delay(Duration.ofSeconds(2));
            EngineUnavailableException e = catchThrowableOfType(EngineUnavailableException.class,
                    () -> slow.service.requestGrades(slow.tenant, WorkflowSetup.AGENT, id));
            assertThat(e.code()).isEqualTo("ENGINE_TIMEOUT");
            assertThat(slow.engine.requests()).as("요청을 보낸 뒤의 타임아웃은 재시도하지 않는다").isEqualTo(1);
        }
    }

    @Test
    void connectionFailuresAreRetriedUpToTheLimit() {
        AtomicInteger attempts = new AtomicInteger();
        HttpClient real = HttpClient.newHttpClient();
        HttpClient failingTwice = new CountingClient(real, attempts, 2);
        HttpEngineTransport transport = new HttpEngineTransport(s.settings, t -> Optional.of(WorkflowSetup.TOKEN), t -> s.engine.baseUrl(),
                failingTwice);
        assertThat(transport.get(s.tenant, "/internal/v1/disclosure/commission-grades/GRD-20260923-1000000").status()).isEqualTo(404);
        assertThat(attempts.get()).isEqualTo(3);

        AtomicInteger always = new AtomicInteger();
        HttpEngineTransport refused = new HttpEngineTransport(s.settings, t -> Optional.of(WorkflowSetup.TOKEN), t -> s.engine.baseUrl(),
                new CountingClient(real, always, Integer.MAX_VALUE));
        EngineUnavailableException e = catchThrowableOfType(EngineUnavailableException.class,
                () -> refused.get(s.tenant, "/internal/v1/disclosure/commission-grades/x"));
        assertThat(e.code()).isEqualTo("ENGINE_CONNECT");
        assertThat(always.get()).isEqualTo(s.settings.maxAttempts());
    }

    @Test
    void closedEngineIsAConnectFailure() {
        FakeEngine gone = s.engine;
        URI base = gone.baseUrl();
        gone.close();
        HttpEngineTransport transport = new HttpEngineTransport(new EngineClientSettings(Duration.ofMillis(300), Duration.ofSeconds(1), 2),
                t -> Optional.of(WorkflowSetup.TOKEN), t -> base);
        assertThat(catchThrowableOfType(EngineUnavailableException.class, () -> transport.get(s.tenant, "/x")).code())
                .isEqualTo("ENGINE_CONNECT");
    }

    @Test
    void itemsChangedDuringTheEngineCallMakeTheResultStale() {
        DisclosureId id = s.compared();
        GradeSnapshotPort real = new EngineGradeClient(new HttpEngineTransport(s.settings, t -> Optional.of(WorkflowSetup.TOKEN),
                t -> s.engine.baseUrl()));
        GradeSnapshotPort racing = new GradeSnapshotPort() {
            @Override
            public Fetch request(TenantId tenant, EngineRequest request, Allowance allowance) {
                Fetch f = real.request(tenant, request, allowance);
                // 엔진 호출 중(트랜잭션 밖) 다른 설계사 명령이 항목을 바꾼다
                s.service.replaceItems(tenant, WorkflowSetup.AGENT, id, List.of(WorkflowSetup.catalogItem("INS-A:PRD-1001", true),
                        WorkflowSetup.catalogItem("INS-B:PRD-2044", false), WorkflowSetup.catalogItem("INS-E:PRD-5001", true)));
                return f;
            }

            @Override
            public Fetch refetch(TenantId tenant, com.ga.disclosure.domain.vo.SnapshotId snapshotId, EngineRequest request,
                                 Allowance allowance) {
                return real.refetch(tenant, snapshotId, request, allowance);
            }
        };
        var service = new com.ga.disclosure.workflow.disclosure.DisclosureService(s.disclosures, s.reviews, s.flags,
                new com.ga.disclosure.infra.persistence.TenantRepository(s.gateway), racing, s.catalog, s.catalog, s.vault,
                new com.ga.disclosure.rules.resolve.RuleResolver(s.rules), new com.ga.disclosure.rules.template.TemplateResolver(s.templates),
                com.ga.disclosure.rules.validation.standard.StandardValidations.registry(), s.audit, s.tx, s.clock, s.agents, s.outbox);
        CommandResult r = service.requestGrades(s.tenant, WorkflowSetup.AGENT, id);
        assertThat(r.rejectionOrNull()).isEqualTo(CommandResult.Rejection.GRADE_STALE);
        assertThat(one(id, "SELECT status || '/' || coalesce(grade_snapshot_id, '-') FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?"))
                .isEqualTo("COMPARED/-");
        assertThat(s.auditLog()).filteredOn(a -> a.entry().action().name().equals("GRADE_FETCH"))
                .singleElement().satisfies(a -> assertThat(a.entry().detail().get("outcome").asString()).isEqualTo("STALE"));
    }

    /** 앞의 {@code failures}번은 연결 거부(ConnectException), 그 뒤는 실제 클라이언트에 위임하며 호출 수를 센다. */
    private static final class CountingClient extends HttpClient {
        private final HttpClient delegate;
        private final AtomicInteger attempts;
        private final int failures;

        CountingClient(HttpClient delegate, AtomicInteger attempts, int failures) {
            this.delegate = delegate;
            this.attempts = attempts;
            this.failures = failures;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
            if (attempts.incrementAndGet() <= failures) {
                throw new ConnectException("refused (injected)");
            }
            return delegate.send(request, handler);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h,
                                                                HttpResponse.PushPromiseHandler<T> p) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return delegate.cookieHandler();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return delegate.connectTimeout();
        }

        @Override
        public Redirect followRedirects() {
            return delegate.followRedirects();
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return delegate.proxy();
        }

        @Override
        public SSLContext sslContext() {
            return delegate.sslContext();
        }

        @Override
        public SSLParameters sslParameters() {
            return delegate.sslParameters();
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return delegate.authenticator();
        }

        @Override
        public Version version() {
            return delegate.version();
        }

        @Override
        public Optional<Executor> executor() {
            return delegate.executor();
        }
    }
}
