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
import com.ga.disclosure.domain.enums.ValidationStage;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.LifecycleReasonRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.rules.validation.ValidationRegistry;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.disclosure.DisclosureLoader.Loaded;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 무효·정정·재기준 유스케이스(설계서 §6.6, 3B 지시문 §6, 3A 수용심사 §3-7·§3-8).
 * <ul>
 *   <li><b>VOID</b>: 가변 상태는 설계사, 봉인 이후는 고정 룰의 {@code exceptionApproval.role}을 가진 행위자만(인가 자체는 Phase 6 — 지금은 행위자
 *       역할 인자와 감사). 봉인 후 무효는 번호·봉인 컬럼·산출물·잠금을 그대로 둔다.</li>
 *   <li><b>SUPERSEDE</b>: 봉인 이후 상태에서 새 버전 DRAFT(버전 + 1, 원본 ID, 항목 입력 복제 — 등급·추천사유는 복제하지 않는다)를 만들고 원본을
 *       SUPERSEDED로. 새 버전의 룰·서식은 <b>원본 상담일로 다시 해석</b>해 고정한다(그 사이 소급 배포가 있었다면 새 버전은 새 룰을 쓴다).</li>
 *   <li><b>REBASE</b>: {@code RULE_SUPERSEDED_DRAFT} 열린 플래그가 있을 때만. 상담일 재해석 결과를 새로 고정, 스냅샷·사유 폐기, 새 룰의 COMPARE
 *       검증 통과면 COMPARED 아니면 DRAFT(승인 Q3). 옛 승인은 지우지 않고 룰 버전 귀속으로 무효가 된다.</li>
 * </ul>
 * 무효·정정은 열린 {@code VALIDATION_OVERRIDE}·{@code RULE_SUPERSEDED_DRAFT} 플래그를 {@code SUPERSEDED_BY_DOCUMENT_STATE}로 닫는다.
 * {@code GRADE_INCONSISTENT}는 엔진 이상 신호라 문서 상태로 닫지 않는다. 사유는 고정 룰의 닫힌 코드({@code voidReasons}·{@code supersedeReasons})
 * + 선택 텍스트이고(V8, 3B 수용심사 §2-2) 원본 행 메타 컬럼에 한 번 기록된다. 텍스트는 감사에 싣지 않는다(자유 텍스트 — 개인정보가 섞일 수
 * 있다, 절대 규칙 6): 감사에는 코드와 텍스트 길이만.
 */
public final class LifecycleService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<DisclosureFlagPort.Type> CLOSED_BY_DOCUMENT_STATE =
            Set.of(DisclosureFlagPort.Type.VALIDATION_OVERRIDE, DisclosureFlagPort.Type.RULE_SUPERSEDED_DRAFT);

    /** 업무 거부 코드(커밋·감사, 상태 불변). */
    public enum Rejection {
        /** 봉인 이후 무효·정정에 필요한 역할({@code exceptionApproval.role})이 아니다. */
        ROLE_REQUIRED,
        /** 재기준 대상이 아니다({@code RULE_SUPERSEDED_DRAFT} 열린 플래그 없음). */
        REBASE_NOT_ALLOWED,
        /** 사유 코드가 고정 룰의 목록({@code voidReasons}·{@code supersedeReasons})에 없다. */
        REASON_CODE_UNKNOWN,
        /** 그 사유 코드는 텍스트가 필요하다({@code requiresText}). */
        REASON_TEXT_REQUIRED,
        /** 사유 텍스트가 룰 상한({@code lifecycleReasonTextMaxLength})을 넘는다. */
        REASON_TEXT_TOO_LONG
    }

    /** 결과. 거부면 {@code rejection}이 있다. 정정이면 새 버전 ID, 재기준이면 새 룰의 COMPARE 검증 결과. */
    public record Outcome(DisclosureId id, DisclosureStatus status, Optional<Rejection> rejection, Optional<DisclosureId> newVersion,
                          List<ValidationResult> results) {
        public Outcome {
            results = List.copyOf(results);
        }

        public boolean applied() {
            return rejection.isEmpty();
        }
    }

    private final DisclosureStore store;
    private final DisclosureFlagPort flags;
    private final RuleResolver rules;
    private final TemplateResolver templates;
    private final ValidationRegistry registry;
    private final AuditPort audit;
    private final Clock clock;
    private final CommandRunner runner;
    private final DisclosureLoader loader;
    private final OutboxPort outbox;

    public LifecycleService(DisclosureServiceDeps deps) {
        this.outbox = deps.outbox();
        this.store = deps.store();
        this.flags = deps.flags();
        this.rules = deps.rules();
        this.templates = deps.templates();
        this.registry = deps.registry();
        this.audit = deps.audit();
        this.clock = deps.clock();
        this.runner = new CommandRunner(deps.transactions(), audit, clock);
        this.loader = deps.loader();
    }

    // ------------------------------------------------------------------ VOID

    public Outcome voidDisclosure(TenantId tenant, Actor actor, DisclosureId id, LifecycleReason reason) {
        Objects.requireNonNull(reason, "reason");                // 코드 형식은 LifecycleReason이 검사(입력 전제)
        return runner.inTransaction(tenant, actor, DisclosureCommand.VOID.name(), id.toString(), () -> {
            Loaded l = loader.load(tenant, id);
            Disclosure d = l.disclosure();
            DisclosureStateTable.require(d.status(), DisclosureCommand.VOID);
            if (d.status().isSealedOrLater() && !roleAllowed(actor, l.rule())) {
                return reject(actor, d, DisclosureCommand.VOID, Rejection.ROLE_REQUIRED, l.rule());
            }
            Optional<Rejection> invalid = checkReason(l.rule().voidReasons(), l.rule().lifecycleReasonTextMaxLength(), reason);
            if (invalid.isPresent()) {
                return reject(actor, d, DisclosureCommand.VOID, invalid.get(), l.rule());
            }
            VoidMark mark = new VoidMark(clock.instant(), reason);
            DisclosureStatus from = d.status();
            d.voidWith(mark);
            store.save(d);
            record(actor, AuditAction.DISCLOSURE_VOID, id, JSON.createObjectNode().put("from", from.name()).put("to", d.status().name())
                    .put("sealed", from.isSealedOrLater()).put("reasonCode", reason.code()).put("reasonTextLength", reason.textLength())
                    .put("actorRole", actor.role()));
            closeFlags(actor, id);
            outbox.append(EventType.DisclosureVoided, id.toString(), mark.at(), OutboxPayloads.disclosureVoided(id.value(),
                    d.sealStamp().map(st -> st.number().value()).orElse(null), from.name(), mark.at()));
            return new Outcome(id, d.status(), Optional.empty(), Optional.empty(), List.of());
        });
    }

    // ------------------------------------------------------------------ SUPERSEDE

    public Outcome supersede(TenantId tenant, Actor actor, DisclosureId id, LifecycleReason reason) {
        Objects.requireNonNull(reason, "reason");
        return runner.inTransaction(tenant, actor, DisclosureCommand.SUPERSEDE.name(), id.toString(), () -> {
            Loaded l = loader.load(tenant, id);
            Disclosure original = l.disclosure();
            DisclosureStateTable.require(original.status(), DisclosureCommand.SUPERSEDE);
            if (!roleAllowed(actor, l.rule())) {
                return reject(actor, original, DisclosureCommand.SUPERSEDE, Rejection.ROLE_REQUIRED, l.rule());
            }
            Optional<Rejection> invalid = checkReason(l.rule().supersedeReasons(), l.rule().lifecycleReasonTextMaxLength(), reason);
            if (invalid.isPresent()) {
                return reject(actor, original, DisclosureCommand.SUPERSEDE, invalid.get(), l.rule());
            }
            // 새 버전은 원본 상담일로 다시 해석한 룰·서식에 고정한다(그 사이 소급 배포가 있었다면 새 룰)
            EffectiveRule rule = rules.resolve(tenant, original.consultDate());
            for (ValidationStage stage : ValidationStage.values()) {
                registry.plan(stage, rule);
            }
            TemplateResolution template = templates.resolve(tenant, l.template().templateType(), original.consultDate());
            DisclosureId next = DisclosureId.of(UUID.randomUUID());
            Disclosure corrected = Disclosure.supersedingDraft(next, original, rule.globalRuleVersionId(), rule.tenantRuleVersion().orElse(null),
                    template.ref(), loader.context(tenant, rule, template, original.groupCode(), original.consultDate()));
            DisclosureStatus from = original.status();
            original.supersede(next, reason);
            store.save(original);
            store.insert(corrected);
            record(actor, AuditAction.DISCLOSURE_SUPERSEDE, id, JSON.createObjectNode().put("from", from.name()).put("to", original.status().name())
                    .put("next", next.toString()).put("nextVersion", corrected.lineage().version())
                    .put("reasonCode", reason.code()).put("reasonTextLength", reason.textLength()).put("actorRole", actor.role()));
            ObjectNode created = JSON.createObjectNode().put("customerRef", corrected.customerRef().value())
                    .put("groupCode", corrected.groupCode().value()).put("consultDate", corrected.consultDate().toString())
                    .put("ruleVersionId", rule.globalRuleVersionId().value()).put("ruleBodyHash", rule.bodyHash())
                    .put("templateId", template.ref().templateId()).put("templateVersion", template.ref().version())
                    .put("version", corrected.lineage().version()).put("supersedesId", id.toString()).put("items", corrected.disclosureItems().size());
            rule.tenantRuleVersion().ifPresentOrElse(v -> created.put("tenantRuleVersionId", v.value()), () -> created.putNull("tenantRuleVersionId"));
            record(actor, AuditAction.DISCLOSURE_CREATE, next, created);
            closeFlags(actor, id);
            Instant now = clock.instant();
            outbox.append(EventType.DisclosureSuperseded, id.toString(), now, OutboxPayloads.disclosureSuperseded(id.value(),
                    original.sealStamp().orElseThrow().number().value(), next.value(), corrected.lineage().version(), now));
            outbox.append(EventType.DisclosureCreated, next.toString(), now, OutboxPayloads.disclosureCreated(next.value(),
                    corrected.lineage().version(), corrected.agentId(), corrected.customerRef().value(), corrected.groupCode().value(),
                    corrected.consultDate(), template.ref().templateId(), template.ref().version(), corrected.issuerMode().name(), id.value()));
            return new Outcome(id, original.status(), Optional.empty(), Optional.of(next), List.of());
        });
    }

    // ------------------------------------------------------------------ REBASE

    public Outcome rebase(TenantId tenant, Actor actor, DisclosureId id) {
        return runner.inTransaction(tenant, actor, DisclosureCommand.REBASE.name(), id.toString(), () -> {
            Loaded l = loader.load(tenant, id);
            Disclosure d = l.disclosure();
            DisclosureStateTable.require(d.status(), DisclosureCommand.REBASE);
            Optional<DisclosureFlagPort.OpenFlag> flag = flags.openFor(id).stream()
                    .filter(f -> f.type() == DisclosureFlagPort.Type.RULE_SUPERSEDED_DRAFT).findFirst();
            if (flag.isEmpty()) {
                return reject(actor, d, DisclosureCommand.REBASE, Rejection.REBASE_NOT_ALLOWED, l.rule());
            }
            EffectiveRule rule = rules.resolve(tenant, d.consultDate());
            for (ValidationStage stage : ValidationStage.values()) {
                registry.plan(stage, rule);
            }
            TemplateResolution template = templates.resolve(tenant, l.template().templateType(), d.consultDate());
            ObjectNode detail = JSON.createObjectNode().put("from", d.status().name())
                    .put("previousRuleVersionId", d.ruleVersionId().value())
                    .put("previousTemplateVersion", d.template().version());
            d.tenantRuleVersionId().ifPresentOrElse(v -> detail.put("previousTenantRuleVersionId", v.value()),
                    () -> detail.putNull("previousTenantRuleVersionId"));
            TransitionOutcome o = d.rebase(rule.globalRuleVersionId(), rule.tenantRuleVersion().orElse(null), template.ref(),
                    loader.context(tenant, rule, template, d.groupCode(), d.consultDate()), loader.check(rule, template));
            store.save(d);
            detail.put("to", d.status().name()).put("ruleVersionId", rule.globalRuleVersionId().value()).put("ruleBodyHash", rule.bodyHash())
                    .put("templateId", template.ref().templateId()).put("templateVersion", template.ref().version());
            rule.tenantRuleVersion().ifPresentOrElse(v -> detail.put("tenantRuleVersionId", v.value()), () -> detail.putNull("tenantRuleVersionId"));
            ArrayNode rows = detail.putArray("compareResults");
            for (ValidationResult r : o.results()) {
                rows.addObject().put("ruleId", r.ruleId()).put("passed", r.passed()).put("overridable", r.overridable());
            }
            record(actor, AuditAction.DISCLOSURE_REBASE, id, detail);
            resolve(actor, id, flag.get(), DisclosureFlagPort.Resolution.REBASED, actor.subject());
            // 새 룰의 오버라이드 가능 실패는 다른 전이와 같이 플래그로 남긴다(열린 같은 대상 플래그는 재사용)
            for (ValidationResult f : o.results()) {
                if (f.overridable()) {
                    DisclosureFlagPort.RaisedFlag raised = flags.raise(DisclosureFlagPort.Type.VALIDATION_OVERRIDE, "MEDIUM", id, "DISCLOSURE_RULE",
                            id + "/" + f.ruleId(), clock.instant());
                    if (raised.created()) {
                        audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.FLAG_RAISE, "DISCLOSURE_RULE",
                                id + "/" + f.ruleId(), JSON.createObjectNode().put("flagId", raised.flagId().toString())
                                        .put("type", DisclosureFlagPort.Type.VALIDATION_OVERRIDE.name()).put("severity", "MEDIUM")));
                    }
                }
            }
            return new Outcome(id, d.status(), Optional.empty(), Optional.empty(), o.results());
        });
    }

    // ------------------------------------------------------------------ 내부

    /** 사유 코드가 고정 룰의 닫힌 목록에 있고, requiresText면 텍스트가 있으며, 텍스트가 상한 이하인가. */
    static Optional<Rejection> checkReason(List<LifecycleReasonRule> allowed, int maxLength, LifecycleReason reason) {
        Optional<LifecycleReasonRule> rule = allowed.stream().filter(r -> r.code().equals(reason.code())).findFirst();
        if (rule.isEmpty()) {
            return Optional.of(Rejection.REASON_CODE_UNKNOWN);
        }
        if (rule.get().requiresText() && reason.textOrNull() == null) {
            return Optional.of(Rejection.REASON_TEXT_REQUIRED);
        }
        if (reason.textLength() > maxLength) {
            return Optional.of(Rejection.REASON_TEXT_TOO_LONG);
        }
        return Optional.empty();
    }

    private static boolean roleAllowed(Actor actor, EffectiveRule rule) {
        return actor.role().equals(rule.exceptionApprovalRole().name());
    }

    private Outcome reject(Actor actor, Disclosure d, DisclosureCommand command, Rejection rejection, EffectiveRule rule) {
        record(actor, AuditAction.DISCLOSURE_REJECT, d.id(), JSON.createObjectNode().put("command", command.name()).put("reason", rejection.name())
                .put("status", d.status().name()).put("actorRole", actor.role()).put("requiredRole", rule.exceptionApprovalRole().name()));
        return new Outcome(d.id(), d.status(), Optional.of(rejection), Optional.empty(), List.of());
    }

    private void closeFlags(Actor actor, DisclosureId id) {
        for (DisclosureFlagPort.OpenFlag f : flags.openFor(id)) {
            if (CLOSED_BY_DOCUMENT_STATE.contains(f.type())) {
                resolve(actor, id, f, DisclosureFlagPort.Resolution.SUPERSEDED_BY_DOCUMENT_STATE, actor.subject());
            }
        }
    }

    private void resolve(Actor actor, DisclosureId id, DisclosureFlagPort.OpenFlag f, DisclosureFlagPort.Resolution resolution, String by) {
        Instant at = clock.instant();
        if (flags.resolve(f.flagId(), resolution, by, at)) {
            record(actor, AuditAction.FLAG_RESOLVE, id, JSON.createObjectNode().put("flagId", f.flagId().toString()).put("type", f.type().name())
                    .put("resolution", resolution.name()).put("resolvedBy", by));
        }
    }

    private void record(Actor actor, AuditAction action, DisclosureId id, ObjectNode detail) {
        audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), action, CommandRunner.TARGET, id.toString(), detail));
    }
}
