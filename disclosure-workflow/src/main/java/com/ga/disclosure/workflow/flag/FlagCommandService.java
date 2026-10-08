package com.ga.disclosure.workflow.flag;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.FlagTypePolicy;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.workflow.identity.AgentDirectory;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;
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
import java.util.Set;
import java.util.UUID;

/**
 * 준법 큐 명령(6B 계획 §7): 배정·수동 해소·SLA 경과 표시. 해소 코드·근거 필요 여부는 <b>해소 시점</b>(오늘 KST)의 ACTIVE 룰
 * {@code complianceQueue.types[type]}이고, 담당 역할은 플래그가 열릴 때 복사한 값이다(V14). 거부는 감사를 커밋한 뒤 {@link FlagRejectedException}으로
 * 알린다(근거 판정의 세부는 감사에만).
 *
 * <p>{@code CHAIN_BROKEN} 해소: 근거 {@code {"verifyRunJobId": UUID}}(그 밖의 키 없음)가 가리키는 작업이 같은 테넌트의 {@code VERIFY_TENANT}이고
 * {@code SUCCEEDED}이며 플래그가 열린 <b>뒤에 시작</b>했고, 그 보고서 해시를 가진 {@code VERIFY_RUN} 감사 행의 결과가 {@code MATCH}여야 한다.
 */
public final class FlagCommandService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final String CHAIN_BROKEN = "CHAIN_BROKEN";
    public static final int MAX_SWEEP = 1_000;

    private final FlagLookup lookup;
    private final FlagCommandPort flags;
    private final VerifyEvidencePort verify;
    private final AgentDirectory directory;
    private final RuleResolver rules;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final AuthorizationPort authz;
    private final Clock clock;

    public FlagCommandService(FlagLookup lookup, FlagCommandPort flags, VerifyEvidencePort verify, AgentDirectory directory, RuleResolver rules,
                              AuditPort audit, WorkflowTransactions transactions, AuthorizationPort authz, Clock clock) {
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.flags = Objects.requireNonNull(flags, "flags");
        this.verify = Objects.requireNonNull(verify, "verify");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public record Resolved(UUID flagId, String type, String resolutionCode) {
    }

    public record Assigned(UUID flagId, String type, String assignee) {
    }

    public record SweepReport(List<FlagCommandPort.Breached> breached) {
        public SweepReport {
            breached = List.copyOf(breached);
        }
    }

    /** 결과 또는 (감사를 남긴) 거부 — 트랜잭션 밖에서 던진다. */
    private record Decision<T>(T value, FlagRejectedException.Rejection rejection) {
        T orThrow() {
            if (rejection != null) {
                throw new FlagRejectedException(rejection);
            }
            return value;
        }
    }

    @UseCaseEntry(Action.FLAG_ASSIGN)
    public Assigned assign(Caller caller, UUID flagId, String assignee) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(flagId, "flagId");
        if (assignee == null || assignee.isBlank()) {
            throw new IllegalArgumentException("assignee is required");
        }
        Instant now = clock.instant();
        return transactions.inTenant(caller.tenant(), () -> {
            Actor actor = authz.require(caller, Action.FLAG_ASSIGN, Target.flag(flagId));
            FlagLookup.State flag = lookup.state(flagId).orElseThrow();       // 인가가 존재를 이미 봤다
            ObjectNode detail = JSON.createObjectNode().put("type", flag.type()).put("assignee", assignee);
            FlagRejectedException.Rejection rejection = null;
            if (!flag.open()) {
                rejection = FlagRejectedException.Rejection.ALREADY_RESOLVED;
            } else if (actor.role().equals("MANAGER") && !flag.assignedRole().equals("MANAGER")) {
                rejection = FlagRejectedException.Rejection.ROLE_NOT_ASSIGNED;
            } else if (!directory.find(assignee).map(l -> l.roles().contains(flag.assignedRole())).orElse(false)) {
                rejection = FlagRejectedException.Rejection.ASSIGNEE_INVALID;
            } else if (!flags.assign(flagId, assignee)) {
                rejection = FlagRejectedException.Rejection.ALREADY_RESOLVED;
            }
            if (rejection != null) {
                detail.put("rejected", rejection.name());
            }
            audit.append(new AuditEntry(now, actor.subject(), actor.role(), rejection == null ? AuditAction.FLAG_ASSIGN : AuditAction.FLAG_COMMAND_REJECTED,
                    "FLAG", flagId.toString(), detail));
            return new Decision<>(new Assigned(flagId, flag.type(), assignee), rejection);
        }).orThrow();
    }

    @UseCaseEntry(Action.FLAG_RESOLVE)
    public Resolved resolve(Caller caller, UUID flagId, String resolutionCode, Optional<JsonNode> evidence) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(flagId, "flagId");
        Objects.requireNonNull(evidence, "evidence");
        if (resolutionCode == null || resolutionCode.isBlank()) {
            throw new IllegalArgumentException("resolutionCode is required");
        }
        TenantId tenant = caller.tenant();
        Instant now = clock.instant();
        return transactions.inTenant(tenant, () -> {
            Actor actor = authz.require(caller, Action.FLAG_RESOLVE, Target.flag(flagId));
            FlagLookup.State flag = lookup.state(flagId).orElseThrow();
            EffectiveRule rule = rules.resolve(tenant, LocalDate.ofInstant(now, SEOUL));
            FlagTypePolicy policy = rule.flagPolicy(flag.type());
            ObjectNode detail = JSON.createObjectNode().put("type", flag.type()).put("resolutionCode", resolutionCode)
                    .put("ruleVersionId", rule.globalRuleVersionId().value());
            FlagRejectedException.Rejection rejection = check(flag, policy, resolutionCode, evidence, detail);
            String evidenceJson = null;
            if (rejection == null) {
                evidenceJson = evidence.map(e -> new String(Canonicalizer.canonicalize(e), StandardCharsets.UTF_8)).orElse(null);
                if (!flags.resolveManually(flagId, actor.subject(), now, resolutionCode, evidenceJson)) {
                    rejection = FlagRejectedException.Rejection.ALREADY_RESOLVED;
                }
            }
            if (rejection != null) {
                detail.put("rejected", rejection.name());
                audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.FLAG_COMMAND_REJECTED, "FLAG", flagId.toString(), detail));
                return new Decision<Resolved>(null, rejection);
            }
            detail.put("resolution", FlagCommandPort.MANUAL_RESOLUTION);
            evidence.ifPresent(e -> detail.set("evidence", e));
            audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.FLAG_RESOLVE, "FLAG", flagId.toString(), detail));
            return new Decision<>(new Resolved(flagId, flag.type(), resolutionCode), null);
        }).orThrow();
    }

    /** 판정 순서: 닫힘 → 수동 해소 없음 → 모르는 코드 → 근거 모양 → (CHAIN_BROKEN) 근거 작업. */
    private FlagRejectedException.Rejection check(FlagLookup.State flag, FlagTypePolicy policy, String code, Optional<JsonNode> evidence,
                                                  ObjectNode detail) {
        if (!flag.open()) {
            return FlagRejectedException.Rejection.ALREADY_RESOLVED;
        }
        if (!policy.manuallyResolvable()) {
            return FlagRejectedException.Rejection.NOT_MANUALLY_RESOLVABLE;
        }
        if (!policy.allowsResolution(code)) {
            return FlagRejectedException.Rejection.RESOLUTION_CODE_UNKNOWN;
        }
        if (!policy.requiresEvidence()) {
            return evidence.isPresent() ? FlagRejectedException.Rejection.EVIDENCE_NOT_ACCEPTED : null;     // 근거가 없는 유형에 근거를 싣지 않는다
        }
        if (!flag.type().equals(CHAIN_BROKEN)) {
            return FlagRejectedException.Rejection.EVIDENCE_REQUIRED;     // 근거 모양이 정해진 유형은 CHAIN_BROKEN뿐이다
        }
        Optional<UUID> jobId = evidence.flatMap(FlagCommandService::verifyRunJobId);
        if (jobId.isEmpty()) {
            return FlagRejectedException.Rejection.EVIDENCE_REQUIRED;
        }
        String why = chainEvidence(flag, jobId.get());
        if (why != null) {
            detail.put("evidenceProblem", why).put("verifyRunJobId", jobId.get().toString());
            return FlagRejectedException.Rejection.CHAIN_EVIDENCE_REJECTED;
        }
        return null;
    }

    /** 근거 모양: 키 하나 {@code verifyRunJobId}(UUID 문자열). */
    static Optional<UUID> verifyRunJobId(JsonNode evidence) {
        if (evidence == null || !evidence.isObject() || !Set.copyOf(evidence.propertyNames()).equals(Set.of("verifyRunJobId"))
                || !evidence.get("verifyRunJobId").isString()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(evidence.get("verifyRunJobId").asString()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** 근거 작업 판정 — 문제 코드(감사용) 또는 null. */
    private String chainEvidence(FlagLookup.State flag, UUID jobId) {
        Optional<VerifyEvidencePort.Job> job = verify.job(jobId);
        if (job.isEmpty()) {
            return "JOB_NOT_FOUND";
        }
        VerifyEvidencePort.Job j = job.get();
        if (!j.kind().equals("VERIFY_TENANT")) {
            return "NOT_VERIFY_TENANT";
        }
        if (!j.status().equals("SUCCEEDED") || j.reportSha256().isEmpty() || j.startedAt().isEmpty()) {
            return "NOT_SUCCEEDED";
        }
        if (!j.startedAt().get().isAfter(flag.raisedAt())) {
            return "STARTED_BEFORE_FLAG";
        }
        Optional<String> result = verify.verifyResult(j.reportSha256().get());
        if (result.isEmpty()) {
            return "NO_VERIFY_AUDIT";
        }
        return result.get().equals("MATCH") ? null : "NOT_MATCH";
    }

    /** SLA 경과 표시(배치 — 스케줄은 Phase 8): 기한이 지난 열린 플래그마다 감사 1행. 새 플래그를 만들지 않는다. */
    @UseCaseEntry(Action.FLAG_SLA_SWEEP)
    public SweepReport sweepSla(Caller caller, int limit) {
        Objects.requireNonNull(caller, "caller");
        if (limit < 1 || limit > MAX_SWEEP) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_SWEEP);
        }
        Instant now = clock.instant();
        return transactions.inTenant(caller.tenant(), () -> {
            Actor actor = authz.require(caller, Action.FLAG_SLA_SWEEP, Target.none());
            List<FlagCommandPort.Breached> marked = flags.markSlaBreached(now, limit);
            for (FlagCommandPort.Breached b : marked) {
                audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.FLAG_SLA_BREACHED, "FLAG", b.flagId().toString(),
                        JSON.createObjectNode().put("type", b.type()).put("dueAt", b.dueAt().toString())));
            }
            return new SweepReport(marked);
        });
    }
}
