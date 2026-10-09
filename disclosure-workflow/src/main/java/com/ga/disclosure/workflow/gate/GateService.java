package com.ga.disclosure.workflow.gate;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.sign.gate.GateFunction;
import com.ga.disclosure.sign.gate.GateView;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.workflow.contract.ContractLinkStore;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 청약 게이트(6B 지시문 §5, 계획 §6, 설계서 §4.4). 한 트랜잭션에서:
 * <ol>
 *   <li>인가({@code GATE_CHECK} — {@code GATE_CLIENT} 테넌트만, 그 밖은 404). 질의 해석({@code query})은 인가 <b>뒤</b>다 — 권한 없는 주체는 본문이
 *       틀려도 같은 404(형식 오류 400으로 경로의 존재를 알리지 않는다, 계약 연결 입구와 같은 순서).</li>
 *   <li>주체별 분당 한도(오늘 KST 룰의 {@code gate.perMinutePerPrincipal}, 고정 1분 창·인스턴스 메모리) — 넘으면 {@link GateRateLimitedException}(429,
 *       감사 없음). 인가 뒤라 게이트 주체가 아닌 쪽은 한도를 관찰할 수 없다.</li>
 *   <li>후보: 청약번호면 그 번호의 확인서, 증권번호면 그 증권의 활성 연결을 쥔 확인서 — 둘 다 계약 연결과 같은 후보 규칙({@link GateLookup}). 무효·정정된
 *       확인서가 아직 연결을 쥐고 있어도 후보가 아니다(6B 중간 회신 ③ — 무효 확인서의 연결은 ALLOWED 근거가 아니다).</li>
 *   <li>0건 {@code BLOCKED/NO_DISCLOSURE}, 2건 이상 {@code BLOCKED/AMBIGUOUS}, 1건이고 고객 가명이 다르면 {@code BLOCKED/CUSTOMER_MISMATCH}.</li>
 *   <li>같으면 {@link GateFunction#evaluate}를 <b>그대로</b>: 그 확인서의 상태, 고정 룰의 {@code signerSet}·{@code gateRequiresManager}, 서명한 역할.
 *       충족이면 {@code ALLOWED/SATISFIED}, 대기면 {@code BLOCKED/PENDING}, 해당 없음(봉인 전·만료)이면 {@code BLOCKED/NO_DISCLOSURE}.</li>
 *   <li>감사 {@code GATE_DECISION} 1행(BLOCKED도) — 식별자는 종류와 SHA-256(UTF-8)뿐.</li>
 * </ol>
 */
public final class GateService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GateLookup lookup;
    private final RuleResolver rules;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final AuthorizationPort authz;
    private final Clock clock;
    /** 테넌트·주체 → {분 번호, 그 분의 요청 수}. 키는 인가를 통과한 게이트 주체뿐이다(identity_link 행 수로 묶인다). */
    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

    public GateService(GateLookup lookup, RuleResolver rules, AuditPort audit, WorkflowTransactions transactions, AuthorizationPort authz, Clock clock) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @UseCaseEntry(Action.GATE_CHECK)
    public GateDecision check(Caller caller, java.util.function.Supplier<GateQuery> parse) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(parse, "parse");
        TenantId tenant = caller.tenant();
        return transactions.inTenant(tenant, () -> {
            Actor actor = authz.require(caller, Action.GATE_CHECK, Target.none());
            GateQuery query = Objects.requireNonNull(parse.get(), "query");
            Instant now = clock.instant();
            int limit = rules.resolve(tenant, LocalDate.ofInstant(now, SEOUL)).gatePerMinutePerPrincipal();
            if (!admit(tenant.value() + "\u0000" + caller.subject(), limit, now)) {
                throw new GateRateLimitedException();
            }
            List<ContractLinkStore.Candidate> candidates = query.kind() == GateQuery.Kind.APPLICATION_NO
                    ? lookup.byApplicationNo(query.identifier()) : lookup.byActivePolicy(query.identifier());
            ObjectNode detail = JSON.createObjectNode().put("identifierKind", query.kind().name())
                    .put("identifierSha256", Sha256.of(query.identifier().getBytes(StandardCharsets.UTF_8))).put("candidates", candidates.size());
            GateDecision decision;
            Optional<ContractLinkStore.Candidate> examined = Optional.empty();
            if (candidates.isEmpty()) {
                decision = GateDecision.blocked(GateDecision.Reason.NO_DISCLOSURE);
            } else if (candidates.size() > 1) {
                decision = GateDecision.blocked(GateDecision.Reason.AMBIGUOUS);
            } else {
                ContractLinkStore.Candidate c = candidates.getFirst();
                examined = Optional.of(c);
                decision = c.customerRef().equals(query.customerRef().value()) ? evaluate(tenant, c) : GateDecision.blocked(GateDecision.Reason.CUSTOMER_MISMATCH);
            }
            detail.put("decision", decision.decision().name()).put("reason", decision.reason().name());
            examined.ifPresent(c -> {
                detail.put("disclosureId", c.id().toString());
                c.disclosureNo().ifPresent(no -> detail.put("disclosureNo", no));
            });
            decision.ruleVersionId().ifPresent(r -> detail.put("ruleVersionId", r.value()));
            if (!decision.pendingRoles().isEmpty()) {
                decision.pendingRoles().forEach(r -> detail.withArray("pendingRoles").add(r.name()));
            }
            audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.GATE_DECISION, examined.isPresent() ? "DISCLOSURE" : "TENANT",
                    examined.map(c -> c.id().toString()).orElse(tenant.value()), detail));
            return decision;
        });
    }

    /** 고객이 같은 후보 1건: Phase 4 산식 그대로, 고정 룰로. */
    private GateDecision evaluate(TenantId tenant, ContractLinkStore.Candidate c) {
        EffectiveRule pinned = rules.load(tenant, c.consultDate(), c.globalRule(), c.tenantRule().orElse(null));
        GateView view = GateFunction.evaluate(DisclosureStatus.valueOf(c.status()), pinned.signerSet(), lookup.signedRoles(c.id()),
                pinned.gateRequiresManager());
        return switch (view.status()) {
            case COMPLETED, PENDING -> view.gateSatisfied()
                    ? new GateDecision(GateDecision.Verdict.ALLOWED, GateDecision.Reason.SATISFIED, c.disclosureNo(), view.pendingRoles(),
                    Optional.of(c.globalRule()))
                    : new GateDecision(GateDecision.Verdict.BLOCKED, GateDecision.Reason.PENDING, c.disclosureNo(), view.pendingRoles(),
                    Optional.of(c.globalRule()));
            case NONE -> GateDecision.blocked(GateDecision.Reason.NO_DISCLOSURE);
        };
    }

    /** 고정 1분 창: 같은 분의 요청 수가 한도 이하면 받는다(받은 요청도 센다). */
    private boolean admit(String key, int perMinute, Instant now) {
        long minute = now.getEpochSecond() / 60;
        long[] after = windows.compute(key, (k, w) -> w == null || w[0] != minute ? new long[] {minute, 1} : new long[] {minute, w[1] + 1});
        return after[1] <= perMinute;
    }
}
