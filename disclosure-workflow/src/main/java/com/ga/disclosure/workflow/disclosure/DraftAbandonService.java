package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.outbox.EventType;
import com.ga.disclosure.audit.outbox.OutboxPayloads;
import com.ga.disclosure.audit.outbox.OutboxPort;
import com.ga.disclosure.domain.disclosure.DisclosureCommand;
import com.ga.disclosure.domain.disclosure.DisclosureStateTable;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.workflow.disclosure.DisclosureLoader.Loaded;
import com.ga.disclosure.workflow.retention.ErasureReader;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 초안 폐기(6B 지시문 §6, 계획 §2.2·§3). 봉인 전 상태(DRAFT~REASONED) → {@code ABANDONED} 묘비. 계기는 둘이다.
 * <ul>
 *   <li><b>명시 폐기</b>({@code DRAFT_ABANDON}): 작성 설계사가 고정 룰의 닫힌 사유 코드({@code draft.abandonReasons} — 텍스트 없음)로.</li>
 *   <li><b>방치 초안 배치</b>({@code ABANDON_DRAFTS}): 실행 시점(오늘 KST) ACTIVE 룰의 {@code draft.abandonAfterDays}보다 오래 바뀌지 않은 초안. 값이
 *       null이면 아무것도 하지 않는다(실값 미정 — TODO(confirm#13)). 초안마다 한 트랜잭션이고, 잠근 뒤 상태·마지막 변경을 다시 본다(그 사이 설계사가
 *       고쳤으면 건너뛴다).</li>
 * </ul>
 * 같은 트랜잭션에서: 지울 값의 해시를 읽어 감사 {@code DRAFT_ABANDONED}(파기와 같은 규약 — 평문 없음) → 폐기 함수({@code ga_draft_abandon}, 전용 롤)가
 * 상태를 옮기고 추천사유·검토 사유·항목 입력값·청약번호를 지운다(행 삭제 없음) → 문서 상태로 닫는 플래그를 닫고 → 아웃박스 {@code DisclosureAbandoned}.
 * 번호가 없으므로 체인·채번과 무관하다.
 */
public final class DraftAbandonService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public static final int MAX_BATCH = 1_000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * "마지막 변경"으로 세는 감사 행위 — 확인서를 바꾸는 명령만(작성·전이·등급 조회·예외 승인·재기준). 열람·검증 미리보기·업무 거부·명령 실패는 변경이
     * 아니다.
     */
    static final Set<AuditAction> CHANGES = Set.of(AuditAction.DISCLOSURE_CREATE, AuditAction.DISCLOSURE_TRANSITION, AuditAction.GRADE_FETCH,
            AuditAction.EXCEPTION_APPROVE, AuditAction.DISCLOSURE_REBASE);
    private static final Set<String> CHANGE_NAMES = CHANGES.stream().map(Enum::name).collect(Collectors.toUnmodifiableSet());

    /** 배치 결과(번호·개인정보 없음). {@code abandonAfterDays}가 비면 룰 값이 null이라 아무것도 하지 않았다. */
    public record BatchReport(OptionalInt abandonAfterDays, Optional<Instant> changedBefore, List<DisclosureId> abandoned, int skipped) {
        public BatchReport {
            abandoned = List.copyOf(abandoned);
        }
    }

    private final DisclosureFlagPort flags;
    private final RuleResolver rules;
    private final AuditPort audit;
    private final OutboxPort outbox;
    private final Clock clock;
    private final CommandRunner runner;
    private final DisclosureLoader loader;
    private final AuthorizationPort authz;
    private final WorkflowTransactions transactions;
    private final AbandonPort abandoner;
    private final ErasureReader erasure;
    private final IdleDraftStore idle;

    public DraftAbandonService(DisclosureServiceDeps deps, AbandonPort abandoner, ErasureReader erasure, IdleDraftStore idle) {
        this.flags = deps.flags();
        this.rules = deps.rules();
        this.audit = deps.audit();
        this.outbox = deps.outbox();
        this.clock = deps.clock();
        this.runner = new CommandRunner(deps.transactions(), audit, clock);
        this.loader = deps.loader();
        this.authz = deps.authz();
        this.transactions = deps.transactions();
        this.abandoner = Objects.requireNonNull(abandoner, "abandoner");
        this.erasure = Objects.requireNonNull(erasure, "erasure");
        this.idle = Objects.requireNonNull(idle, "idle");
    }

    /** 명시 폐기. 사유 코드가 고정 룰 목록에 없으면 업무 거부 {@code REASON_CODE_UNKNOWN}(상태 불변, 코드 값은 감사에 싣지 않는다). */
    @UseCaseEntry(Action.DRAFT_ABANDON)
    public LifecycleService.Outcome abandon(Caller caller, DisclosureId id, String reasonCode) {
        TenantId tenant = caller.tenant();
        Objects.requireNonNull(reasonCode, "reasonCode");
        return runner.inTransaction(caller, DisclosureCommand.ABANDON.name(), id.toString(), attempt -> {
            Actor actor = attempt.granted(authz.require(caller, Action.DRAFT_ABANDON, Target.disclosure(id)));
            Loaded l = loader.load(tenant, id);
            Disclosure d = l.disclosure();
            DisclosureStateTable.require(d.status(), DisclosureCommand.ABANDON);
            if (l.rule().draftAbandonReasons().stream().noneMatch(r -> r.code().equals(reasonCode))) {
                record(actor, AuditAction.DISCLOSURE_REJECT, id, JSON.createObjectNode().put("command", DisclosureCommand.ABANDON.name())
                        .put("reason", LifecycleService.Rejection.REASON_CODE_UNKNOWN.name()).put("status", d.status().name()).put("actorRole", actor.role()));
                return new LifecycleService.Outcome(id, d.status(), Optional.of(LifecycleService.Rejection.REASON_CODE_UNKNOWN), Optional.empty(),
                        List.of());
            }
            abandonLocked(actor, d, JSON.createObjectNode().put("trigger", "EXPLICIT").put("reasonCode", reasonCode)
                    .put("ruleVersionId", l.rule().globalRuleVersionId().value()));
            return new LifecycleService.Outcome(id, d.status(), Optional.empty(), Optional.empty(), List.of());
        });
    }

    /** 방치 초안 배치(작업 {@code ABANDON_DRAFTS}). */
    @UseCaseEntry(Action.ABANDON_DRAFTS)
    public BatchReport abandonIdle(Caller caller, int limit) {
        Objects.requireNonNull(caller, "caller");
        if (limit < 1 || limit > MAX_BATCH) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_BATCH);
        }
        TenantId tenant = caller.tenant();
        Instant now = clock.instant();
        record Plan(Actor actor, EffectiveRule rule, OptionalInt days, Optional<Instant> before, List<IdleDraftStore.Idle> idle) {
        }
        Plan plan = transactions.inTenant(tenant, () -> {
            Actor actor = authz.require(caller, Action.ABANDON_DRAFTS, Target.none());
            EffectiveRule rule = rules.resolve(tenant, LocalDate.ofInstant(now, SEOUL));
            OptionalInt days = rule.draftAbandonAfterDays();
            if (days.isEmpty()) {
                return new Plan(actor, rule, days, Optional.empty(), List.of());
            }
            Instant before = now.minus(Duration.ofDays(days.getAsInt()));
            return new Plan(actor, rule, days, Optional.of(before), idle.idleSince(before, CHANGE_NAMES, limit));
        });
        if (plan.before().isEmpty()) {
            return new BatchReport(plan.days(), Optional.empty(), List.of(), 0);
        }
        Instant before = plan.before().get();
        List<DisclosureId> abandoned = new ArrayList<>();
        int skipped = 0;
        for (IdleDraftStore.Idle candidate : plan.idle()) {
            boolean done = transactions.inTenant(tenant, () -> {
                Disclosure d = loader.load(tenant, candidate.id()).disclosure();            // 잠근다(설계사 명령과 같은 행 잠금)
                if (!d.status().isMutable()) {
                    return false;
                }
                Optional<Instant> changed = idle.lastChange(candidate.id(), CHANGE_NAMES);
                if (changed.isEmpty() || !changed.get().isBefore(before)) {
                    return false;                                                          // 그 사이 고쳐졌다
                }
                Instant last = changed.get();
                abandonLocked(plan.actor(), d, JSON.createObjectNode().put("trigger", "IDLE").put("abandonAfterDays", plan.days().getAsInt())
                        .put("lastChangedAt", last.toString()).put("ruleVersionId", plan.rule().globalRuleVersionId().value()));
                return true;
            });
            if (done) {
                abandoned.add(candidate.id());
            } else {
                skipped++;
            }
        }
        int skippedCount = skipped;
        transactions.inTenant(tenant, () -> {
            audit.append(new AuditEntry(clock.instant(), plan.actor().subject(), plan.actor().role(), AuditAction.DRAFT_ABANDON_BATCH, "TENANT",
                    tenant.value(), JSON.createObjectNode().put("abandonAfterDays", plan.days().getAsInt()).put("changedBefore", before.toString())
                            .put("candidates", plan.idle().size()).put("abandoned", abandoned.size()).put("skipped", skippedCount)
                            .put("ruleVersionId", plan.rule().globalRuleVersionId().value())));
            return null;
        });
        return new BatchReport(plan.days(), Optional.of(before), abandoned, skipped);
    }

    /** 잠근 초안 하나를 폐기한다(호출자의 트랜잭션). 지울 값은 함수 호출 전에 읽어 해시로 감사에 남긴다. */
    private void abandonLocked(Actor actor, Disclosure d, ObjectNode trigger) {
        DisclosureStatus from = d.status();
        Instant at = clock.instant();
        d.abandon(at);                                   // 상태표 검사 — 저장은 폐기 함수가 한다
        ObjectNode detail = JSON.createObjectNode().put("from", from.name()).put("to", d.status().name());
        detail.setAll(trigger);
        ArrayNode erased = detail.putArray("erased");
        erasure.abandonedDraft(d.id()).forEach(e -> erased.addObject().put("table", e.table()).put("column", e.column()).put("row", e.row())
                .put("repr", e.repr()).put("value", e.value()));
        record(actor, AuditAction.DRAFT_ABANDONED, d.id(), detail);
        abandoner.abandon(d.id(), at, actor.subject());
        for (DisclosureFlagPort.OpenFlag f : flags.openFor(d.id())) {
            if (LifecycleService.CLOSED_BY_DOCUMENT_STATE.contains(f.type())
                    && flags.resolve(f.flagId(), DisclosureFlagPort.Resolution.SUPERSEDED_BY_DOCUMENT_STATE, actor.subject(), at)) {
                record(actor, AuditAction.FLAG_RESOLVE, d.id(), JSON.createObjectNode().put("flagId", f.flagId().toString()).put("type", f.type().name())
                        .put("resolution", DisclosureFlagPort.Resolution.SUPERSEDED_BY_DOCUMENT_STATE.name()).put("resolvedBy", actor.subject()));
            }
        }
        outbox.append(EventType.DisclosureAbandoned, d.id().toString(), at, OutboxPayloads.disclosureAbandoned(d.id().value(), at));
    }

    private void record(Actor actor, AuditAction action, DisclosureId id, ObjectNode detail) {
        audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), action, CommandRunner.TARGET, id.toString(), detail));
    }
}
