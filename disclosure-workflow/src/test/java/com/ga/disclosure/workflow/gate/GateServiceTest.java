package com.ga.disclosure.workflow.gate;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditChain;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.rules.testing.InMemoryRuleVersionPort;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.sign.gate.GateFunction;
import com.ga.disclosure.sign.gate.GateStatus;
import com.ga.disclosure.sign.gate.GateView;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.ListGrant;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.contract.ContractLinkStore;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G6(6B 지시문 §5, 계획 §6): 게이트 유스케이스 = Phase 4 {@link GateFunction} <b>그대로</b>. 상태 11 × 서명한 역할의 부분집합 8 × {@code gateRequiresManager}
 * 2 = 176가지 입력 전수를 산식과 대조한다(고정 룰의 signerSet·gateRequiresManager, 서명한 역할은 조회 포트에서). 그 밖에 후보 0·2건·고객 불일치,
 * 요청마다 감사 1행(식별자 원문 없음), 분당 한도(429 — 감사 없음)를 본다. HTTP·DB 경로는 {@code GateApiIT}.
 */
class GateServiceTest {

    static final TenantId T = TenantId.of("T1");
    static final CustomerRef CUSTOMER = CustomerRef.of("CR-" + "a".repeat(32));
    static final Caller CLIENT = new Caller(T, "gate-client-1", com.ga.disclosure.workflow.authz.Channel.INTERNAL);
    static final RuleVersionId WITH_MANAGER = RuleVersionId.of("GATE-MGR");
    static final RuleVersionId WITHOUT_MANAGER = RuleVersionId.of("GATE-NOMGR");

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-09T01:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class Tx implements WorkflowTransactions {
        @Override
        public <T> T inTenant(TenantId tenant, Supplier<T> work) {
            AtomicReference<T> out = new AtomicReference<>();
            TenantContext.runWith(tenant, () -> out.set(work.get()));
            return out.get();
        }

        @Override
        public <T> T inTenant(TenantId tenant, Duration timeout, Supplier<T> work) {
            return inTenant(tenant, work);
        }

        @Override
        public <T> T inTenantRepeatableRead(TenantId tenant, Supplier<T> work) {
            return inTenant(tenant, work);
        }

        @Override
        public <T> T inNewTenantTransaction(TenantId tenant, Supplier<T> work) {
            return inTenant(tenant, work);
        }
    }

    static final class Audit implements AuditPort {
        final List<AuditRecord> rows = new ArrayList<>();

        @Override
        public AuditRecord append(AuditEntry entry) {
            AuditRecord next = AuditChain.next(TenantContext.current(), rows.isEmpty() ? null : rows.getLast(), entry);
            rows.add(next);
            return next;
        }

        @Override
        public List<AuditRecord> readAll() {
            return List.copyOf(rows);
        }

        @Override
        public List<AuditRecord> readAfter(long afterSeq, int limit) {
            return readAll();
        }

        @Override
        public List<AuditRecord> readTarget(String targetKind, String targetId) {
            return readAll();
        }
    }

    static final AuthorizationPort GATE_CLIENT_ONLY = new AuthorizationPort() {
        @Override
        public Actor require(Caller caller, Action action, Target target) {
            assertThat(action).isEqualTo(Action.GATE_CHECK);
            return new Actor(caller.subject(), "GATE_CLIENT");
        }

        @Override
        public ListGrant requireList(Caller caller, Action action) {
            throw new UnsupportedOperationException();
        }
    };

    /** 두 GLOBAL 버전(관리자 필요 여부만 다름) — 오늘의 해석은 WITH_MANAGER, 고정은 후보가 가리키는 쪽. */
    static RuleResolver rules(int perMinute) {
        ObjectNode with = (ObjectNode) Bundles.rule(Bundles.DISC_2026_07).body().deepCopy();
        with.put("gateRequiresManager", true);
        ((ObjectNode) with.get("gate")).put("perMinutePerPrincipal", perMinute);
        ObjectNode without = with.deepCopy().put("gateRequiresManager", false);
        InMemoryRuleVersionPort port = new InMemoryRuleVersionPort()
                .add(Bundles.global(WITH_MANAGER.value(), LocalDate.parse("2026-07-01"), null, RuleStatus.ACTIVE, with))
                .add(Bundles.global(WITHOUT_MANAGER.value(), LocalDate.parse("2026-01-01"), LocalDate.parse("2026-07-01"), RuleStatus.RETIRED, without));
        return new RuleResolver(port);
    }

    /** 조회 포트: 번호 → 후보, 확인서 → 서명한 역할. */
    static final class Lookup implements GateLookup {
        final Map<String, List<ContractLinkStore.Candidate>> byNumber;
        final Map<DisclosureId, List<SignerRole>> signed;

        Lookup(Map<String, List<ContractLinkStore.Candidate>> byNumber, Map<DisclosureId, List<SignerRole>> signed) {
            this.byNumber = byNumber;
            this.signed = signed;
        }

        @Override
        public List<ContractLinkStore.Candidate> byApplicationNo(String applicationNo) {
            return byNumber.getOrDefault(applicationNo, List.of());
        }

        @Override
        public List<ContractLinkStore.Candidate> byActivePolicy(String policyNo) {
            return byNumber.getOrDefault(policyNo, List.of());
        }

        @Override
        public List<SignerRole> signedRoles(DisclosureId id) {
            return signed.getOrDefault(id, List.of());
        }
    }

    static ContractLinkStore.Candidate candidate(DisclosureId id, DisclosureStatus status, String customer, RuleVersionId rule) {
        return new ContractLinkStore.Candidate(id, status.name(), Optional.of("T1-2026-000001"), customer, LocalDate.parse("2026-06-15").plusMonths(
                rule.equals(WITH_MANAGER) ? 1 : 0), rule, Optional.empty(), Optional.empty());
    }

    static GateQuery query(GateQuery.Kind kind, String number) {
        return new GateQuery(kind, number, CUSTOMER);
    }

    // ------------------------------------------------------------------ 전수 대조

    static Stream<Arguments> everyInput() {
        List<Arguments> out = new ArrayList<>();
        List<SignerRole> roles = List.of(SignerRole.CUSTOMER, SignerRole.AGENT, SignerRole.MANAGER);
        for (DisclosureStatus status : DisclosureStatus.values()) {
            for (int mask = 0; mask < 8; mask++) {
                List<SignerRole> signed = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    if ((mask & (1 << i)) != 0) {
                        signed.add(roles.get(i));
                    }
                }
                for (boolean requiresManager : List.of(true, false)) {
                    out.add(Arguments.of(status, List.copyOf(signed), requiresManager));
                }
            }
        }
        return out.stream();
    }

    @ParameterizedTest
    @MethodSource("everyInput")
    void theDecisionIsGateFunctionForEveryStatusSignatureSetAndManagerRule(DisclosureStatus status, List<SignerRole> signed, boolean requiresManager) {
        DisclosureId id = DisclosureId.of(java.util.UUID.randomUUID());
        RuleVersionId rule = requiresManager ? WITH_MANAGER : WITHOUT_MANAGER;
        Audit audit = new Audit();
        GateService gate = new GateService(new Lookup(Map.of("APP-1", List.of(candidate(id, status, CUSTOMER.value(), rule))), Map.of(id, signed)),
                rules(600), audit, new Tx(), GATE_CLIENT_ONLY, new MutableClock());

        GateView expected = GateFunction.evaluate(status, List.of(SignerRole.CUSTOMER, SignerRole.AGENT, SignerRole.MANAGER), signed, requiresManager);
        GateDecision actual = gate.check(CLIENT, () -> query(GateQuery.Kind.APPLICATION_NO, "APP-1"));

        if (expected.status() == GateStatus.NONE) {
            assertThat(actual).isEqualTo(GateDecision.blocked(GateDecision.Reason.NO_DISCLOSURE));
        } else {
            assertThat(actual.decision()).isEqualTo(expected.gateSatisfied() ? GateDecision.Verdict.ALLOWED : GateDecision.Verdict.BLOCKED);
            assertThat(actual.reason()).isEqualTo(expected.gateSatisfied() ? GateDecision.Reason.SATISFIED : GateDecision.Reason.PENDING);
            assertThat(actual.pendingRoles()).isEqualTo(expected.pendingRoles());
            assertThat(actual.disclosureNo()).contains("T1-2026-000001");
            assertThat(actual.ruleVersionId()).contains(rule);
        }
        assertThat(audit.rows).singleElement().satisfies(r -> {
            assertThat(r.entry().action()).isEqualTo(AuditAction.GATE_DECISION);
            assertThat(r.entry().targetId()).isEqualTo(id.toString());
            assertThat(r.entry().detail().get("decision").asString()).isEqualTo(actual.decision().name());
            assertThat(r.entry().detail().get("reason").asString()).isEqualTo(actual.reason().name());
        });
    }

    // ------------------------------------------------------------------ 후보 0·2건·고객 불일치

    @Test
    void noCandidateTwoCandidatesAndAnotherCustomerAreBlockedWithoutPointingAtADisclosure() {
        DisclosureId a = DisclosureId.of(java.util.UUID.randomUUID());
        DisclosureId b = DisclosureId.of(java.util.UUID.randomUUID());
        Audit audit = new Audit();
        GateService gate = new GateService(new Lookup(Map.of(
                "APP-TWO", List.of(candidate(a, DisclosureStatus.COMPLETED, CUSTOMER.value(), WITH_MANAGER),
                        candidate(b, DisclosureStatus.COMPLETED, CUSTOMER.value(), WITH_MANAGER)),
                "POL-OTHER", List.of(candidate(a, DisclosureStatus.COMPLETED, "CR-" + "b".repeat(32), WITH_MANAGER))), Map.of()),
                rules(600), audit, new Tx(), GATE_CLIENT_ONLY, new MutableClock());

        assertThat(gate.check(CLIENT, () -> query(GateQuery.Kind.POLICY_NO, "POL-NONE"))).isEqualTo(GateDecision.blocked(GateDecision.Reason.NO_DISCLOSURE));
        assertThat(gate.check(CLIENT, () -> query(GateQuery.Kind.APPLICATION_NO, "APP-TWO"))).isEqualTo(GateDecision.blocked(GateDecision.Reason.AMBIGUOUS));
        assertThat(gate.check(CLIENT, () -> query(GateQuery.Kind.POLICY_NO, "POL-OTHER")))
                .isEqualTo(GateDecision.blocked(GateDecision.Reason.CUSTOMER_MISMATCH));
        assertThat(audit.rows).extracting(r -> r.entry().detail().get("reason").asString())
                .containsExactly("NO_DISCLOSURE", "AMBIGUOUS", "CUSTOMER_MISMATCH");
        assertThat(audit.rows).extracting(r -> r.entry().targetKind()).containsExactly("TENANT", "TENANT", "DISCLOSURE");
        // 식별자는 해시로만
        assertThat(audit.rows.getFirst().entry().detail().get("identifierSha256").asString())
                .isEqualTo(Sha256.of("POL-NONE".getBytes(StandardCharsets.UTF_8)));
        assertThat(audit.rows).allSatisfy(r -> assertThat(r.entry().detail().toString()).doesNotContain("POL-").doesNotContain("APP-")
                .doesNotContain(CUSTOMER.value()));
    }

    // ------------------------------------------------------------------ 한도

    @Test
    void thePerMinuteLimitIsPerPrincipalAndARefusedRequestIsNotAudited() {
        MutableClock clock = new MutableClock();
        Audit audit = new Audit();
        GateService gate = new GateService(new Lookup(Map.of(), Map.of()), rules(2), audit, new Tx(), GATE_CLIENT_ONLY, clock);
        gate.check(CLIENT, () -> query(GateQuery.Kind.APPLICATION_NO, "APP-1"));
        gate.check(CLIENT, () -> query(GateQuery.Kind.APPLICATION_NO, "APP-1"));
        assertThatThrownBy(() -> gate.check(CLIENT, () -> query(GateQuery.Kind.APPLICATION_NO, "APP-1"))).isInstanceOf(GateRateLimitedException.class);
        assertThat(audit.rows).hasSize(2);
        // 다른 주체는 따로 센다
        gate.check(new Caller(T, "gate-client-2", com.ga.disclosure.workflow.authz.Channel.INTERNAL), () -> query(GateQuery.Kind.APPLICATION_NO, "APP-1"));
        // 다음 분에는 다시 받는다
        clock.now = clock.now.plusSeconds(60);
        gate.check(CLIENT, () -> query(GateQuery.Kind.APPLICATION_NO, "APP-1"));
        assertThat(audit.rows).hasSize(4);
    }

    @Test
    void aDeniedCallerNeverReachesTheParser() {
        AuthorizationPort deny = new AuthorizationPort() {
            @Override
            public Actor require(Caller caller, Action action, Target target) {
                throw new com.ga.disclosure.workflow.authz.AuthorizationDenied(action, com.ga.disclosure.workflow.authz.AuthorizationDenied.Reason.ROLE);
            }

            @Override
            public ListGrant requireList(Caller caller, Action action) {
                throw new UnsupportedOperationException();
            }
        };
        Audit audit = new Audit();
        GateService gate = new GateService(new Lookup(Map.of(), Map.of()), rules(600), audit, new Tx(), deny, new MutableClock());
        assertThatThrownBy(() -> gate.check(CLIENT, () -> {
            throw new AssertionError("parsed before authorization");
        })).isInstanceOf(com.ga.disclosure.workflow.authz.AuthorizationDenied.class);
        assertThat(audit.rows).isEmpty();
    }

    @Test
    void theQueryHidesItsNumberAndRejectsMalformedOnes() {
        assertThat(query(GateQuery.Kind.POLICY_NO, "POL-SECRET").toString()).doesNotContain("POL-SECRET");
        assertThatThrownBy(() -> query(GateQuery.Kind.POLICY_NO, "HAS SPACE")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("HAS SPACE");
        assertThat(EnumSet.allOf(GateDecision.Reason.class)).hasSize(5);
        assertThat(Set.of(GateDecision.Verdict.values())).hasSize(2);
    }
}
