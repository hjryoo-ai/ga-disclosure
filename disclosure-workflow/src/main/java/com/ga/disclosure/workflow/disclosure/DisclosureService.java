package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.outbox.EventType;
import com.ga.disclosure.audit.outbox.OutboxPayloads;
import com.ga.disclosure.audit.outbox.OutboxPort;
import com.ga.disclosure.domain.disclosure.AgentReason;
import com.ga.disclosure.domain.disclosure.DisclosureCommand;
import com.ga.disclosure.domain.disclosure.DisclosureStateTable;
import com.ga.disclosure.domain.disclosure.FieldValue;
import com.ga.disclosure.domain.disclosure.ItemDraft;
import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.domain.enums.ValidationStage;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.Bind;
import com.ga.disclosure.rules.template.TemplateField;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.rules.validation.ValidationRegistry;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.workflow.catalog.CatalogProduct;
import com.ga.disclosure.workflow.catalog.InsurerPanelPort;
import com.ga.disclosure.workflow.catalog.ProductCatalogPort;
import com.ga.disclosure.workflow.customer.CustomerVault;
import com.ga.disclosure.workflow.disclosure.DisclosureLoader.Loaded;
import com.ga.disclosure.workflow.identity.AgentDirectory;
import com.ga.platform.core.tenant.AgentId;
import com.ga.platform.core.tenant.OrgPath;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 확인서 유스케이스(설계서 §6.1·§6.2, 3A 지시문 §3): 초안 생성, 항목 교체, 비교, 등급 산출, 추천사유, 단계 검증(드라이런), 예외 승인.
 *
 * <p>트랜잭션(3A 계획 Q6 — 설계서 §6.2 "하나의 트랜잭션" = 상태 변경·감사가 한 트랜잭션): 각 명령은 행을 잠그고 애그리게이트로 적용한 뒤
 * 저장·감사·플래그를 같은 트랜잭션에서 쓴다. 엔진 호출만 트랜잭션 밖이다 — 짧은 읽기 트랜잭션에서 요청을 만들고, 호출 뒤 쓰기 트랜잭션에서
 * 다시 잠가 요청 지문을 대조한다(다르면 {@code GRADE_STALE} 업무 거부). 감사 실패 기록 규약은 {@link CommandRunner}.
 *
 * <p>룰·서식은 초안 생성 때 상담일로 한 번 해석해 ID를 고정하고, 이후 명령은 고정 ID로 로드한다(재해석하지 않는다). 소급 배포된 룰의
 * 판정은 3B SEAL 조건이다(계획 승인 B1).
 */
public final class DisclosureService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SEVERITY_HIGH = "HIGH";
    private static final String SEVERITY_MEDIUM = "MEDIUM";

    private final DisclosureStore store;
    private final ReviewStore reviews;
    private final DisclosureFlagPort flags;
    private final TenantProfilePort tenants;
    private final GradeSnapshotPort engine;
    private final ProductCatalogPort catalog;
    private final InsurerPanelPort panel;
    private final CustomerVault customers;
    private final RuleResolver rules;
    private final TemplateResolver templates;
    private final ValidationRegistry registry;
    private final AuditPort audit;
    private final Clock clock;
    private final AgentDirectory agents;
    private final OutboxPort outbox;
    private final CommandRunner runner;
    private final DisclosureLoader loader;
    private final AuthorizationPort authz;

    public DisclosureService(DisclosureStore store, ReviewStore reviews, DisclosureFlagPort flags, TenantProfilePort tenants,
                             GradeSnapshotPort engine, ProductCatalogPort catalog, InsurerPanelPort panel, CustomerVault customers,
                             RuleResolver rules, TemplateResolver templates, ValidationRegistry registry, AuditPort audit,
                             WorkflowTransactions transactions, Clock clock, AgentDirectory agents, OutboxPort outbox,
                             AuthorizationPort authz) {
        this.store = Objects.requireNonNull(store, "store");
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.flags = Objects.requireNonNull(flags, "flags");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.engine = Objects.requireNonNull(engine, "engine");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.panel = Objects.requireNonNull(panel, "panel");
        this.customers = Objects.requireNonNull(customers, "customers");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.templates = Objects.requireNonNull(templates, "templates");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.runner = new CommandRunner(transactions, audit, clock);
        this.loader = new DisclosureLoader(store, tenants, catalog, panel, rules, templates, registry);
        this.authz = Objects.requireNonNull(authz, "authz");
    }

    // ------------------------------------------------------------------ 초안

    /**
     * 초안 생성: 상담일로 GLOBAL·TENANT 룰과 서식을 <b>한 번</b> 해석해 버전 ID를 고정한다. 룰의 검증 목록이 전부 등록된 규칙인지도
     * 여기서 확인한다(오타가 봉인 단계까지 숨지 않게). 상담일은 이후 바뀌지 않는다. 확인서의 설계사는 행위자를 {@code identity_link}로 해석한
     * {@code agent_id}다(절대 규칙 5, Phase 4 — 연결이 없거나 AGENT 역할이 아니면 {@code AGENT_NOT_LINKED}). 같은 트랜잭션에서 아웃박스
     * {@code DisclosureCreated}를 적재한다(승인 Q13).
     */
    @UseCaseEntry(Action.DISCLOSURE_CREATE)
    public DisclosureId createDraft(Caller caller, CustomerRef customerRef, GroupCode group, LocalDate consultDate,
                                    TemplateType templateType) {
        TenantId tenant = caller.tenant();
        return runner.inTransaction(caller, "CREATE_DRAFT", null, attempt -> {
            Actor agent = attempt.granted(authz.require(caller, Action.DISCLOSURE_CREATE, Target.none()));
            AgentDirectory.LinkedIdentity link = agents.find(agent.subject()).filter(l -> l.hasRole("AGENT"))
                    .orElseThrow(() -> new CommandRejectedException("AGENT_NOT_LINKED", "the actor is not linked to an agent in this tenant"));
            AgentId agentId = link.agentId().orElseThrow();      // AGENT ⇒ agent_id·조직 경로(V12 CHECK)
            OrgPath orgPath = link.orgPath().orElseThrow();
            if (!customers.exists(customerRef)) {
                throw new CommandRejectedException("UNKNOWN_CUSTOMER", "no customer " + customerRef);
            }
            if (catalog.listGroups(tenant, consultDate).stream().noneMatch(g -> g.code().equals(group))) {
                throw new CommandRejectedException("UNKNOWN_GROUP", "product group " + group + " is not in the catalog on " + consultDate);
            }
            EffectiveRule rule = rules.resolve(tenant, consultDate);
            for (ValidationStage stage : ValidationStage.values()) {
                registry.plan(stage, rule);
            }
            TemplateResolution template = templates.resolve(tenant, templateType, consultDate);
            TenantProfilePort.TenantProfile profile = tenants.profile(tenant);
            DisclosureId id = DisclosureId.of(UUID.randomUUID());
            Disclosure d = Disclosure.draft(id, agentId.value(), customerRef, group, consultDate, rule.globalRuleVersionId(),
                    rule.tenantRuleVersion().orElse(null), template.ref(), profile.issuerMode(),
                    loader.context(tenant, rule, template, group, consultDate));
            store.insert(d, orgPath);
            ObjectNode detail = JSON.createObjectNode()
                    .put("customerRef", customerRef.value())
                    .put("groupCode", group.value())
                    .put("consultDate", consultDate.toString())
                    .put("ruleVersionId", rule.globalRuleVersionId().value())
                    .put("ruleBodyHash", rule.bodyHash())
                    .put("templateId", template.ref().templateId())
                    .put("templateVersion", template.ref().version());
            rule.tenantRuleVersion().ifPresentOrElse(v -> detail.put("tenantRuleVersionId", v.value()),
                    () -> detail.putNull("tenantRuleVersionId"));
            record(agent, AuditAction.DISCLOSURE_CREATE, id, detail);
            outbox.append(EventType.DisclosureCreated, id.toString(), clock.instant(), OutboxPayloads.disclosureCreated(id.value(), 1,
                    agentId.value(), customerRef.value(), group.value(), consultDate, template.ref().templateId(), template.ref().version(),
                    profile.issuerMode().name(), null));
            return id;
        });
    }

    // ------------------------------------------------------------------ 항목·비교·사유

    /** 비교 항목 교체(카탈로그 상품은 상담일 기준 카탈로그에서 상품명·기본값을 채운다). */
    @UseCaseEntry(Action.ITEMS_REPLACE)
    public CommandResult replaceItems(Caller caller, DisclosureId id, List<ItemInput> inputs) {
        TenantId tenant = caller.tenant();
        return runner.inTransaction(caller, DisclosureCommand.REPLACE_ITEMS.name(), id.toString(), attempt -> {
            Actor agent = attempt.granted(authz.require(caller, Action.ITEMS_REPLACE, Target.disclosure(id)));
            Loaded l = load(tenant, id);
            List<ItemDraft> drafts = new ArrayList<>();
            for (ItemInput input : inputs) {
                drafts.add(draft(tenant, l, input));
            }
            return apply(agent, l, l.disclosure().replaceItems(drafts, l.check()), JSON.createObjectNode().put("items", drafts.size()));
        });
    }

    /** DRAFT → COMPARED. */
    @UseCaseEntry(Action.COMPARE)
    public CommandResult compare(Caller caller, DisclosureId id) {
        TenantId tenant = caller.tenant();
        return runner.inTransaction(caller, DisclosureCommand.COMPARE.name(), id.toString(), attempt -> {
            Actor agent = attempt.granted(authz.require(caller, Action.COMPARE, Target.disclosure(id)));
            Loaded l = load(tenant, id);
            return apply(agent, l, l.disclosure().compare(l.check()), JSON.createObjectNode());
        });
    }

    /** 추천사유 입력(설계사 입력만). 고객 요청 항목에는 룰의 자동 부가 코드가 붙는다. */
    @UseCaseEntry(Action.RECOMMENDATIONS_SET)
    public CommandResult setRecommendations(Caller caller, DisclosureId id, List<AgentReason> reasons) {
        TenantId tenant = caller.tenant();
        return runner.inTransaction(caller, DisclosureCommand.SET_RECOMMENDATIONS.name(), id.toString(), attempt -> {
            Actor agent = attempt.granted(authz.require(caller, Action.RECOMMENDATIONS_SET, Target.disclosure(id)));
            Loaded l = load(tenant, id);
            return apply(agent, l, l.disclosure().setRecommendations(reasons, l.rule().autoReasonCodes(), l.check()),
                    JSON.createObjectNode().put("reasons", reasons.size()));
        });
    }

    // ------------------------------------------------------------------ 등급 산출

    /**
     * 등급·순위 산출(첫 산출·재산출). ① 짧은 트랜잭션: 표가 산출을 허용하는지 먼저 보고(허용 안 되면 엔진을 부르지 않는다) 요청·지문을 만든다
     * ② 트랜잭션 밖에서 엔진 호출 — 오류 응답·연결 실패는 명령 오류 ③ 쓰기 트랜잭션: 다시 잠그고 지문 대조(다르면 {@code GRADE_STALE}), 응답이
     * 스키마·정합성에서 거부됐으면 {@code GRADE_INCONSISTENT} 플래그 + 업무 거부(상태 유지), 수락이면 애그리게이트에 적용. 엔진 호출 결과는
     * 셋 다 {@code GRADE_FETCH} 감사로 남는다.
     */
    @UseCaseEntry(Action.GRADES_REQUEST)
    public CommandResult requestGrades(Caller caller, DisclosureId id) {
        TenantId tenant = caller.tenant();
        String command = DisclosureCommand.APPLY_SNAPSHOT.name();
        Prepared prepared = runner.inTransaction(caller, command, id.toString(), attempt -> {
            Actor agent = attempt.granted(authz.require(caller, Action.GRADES_REQUEST, Target.disclosure(id)));
            Loaded l = load(tenant, id);
            DisclosureStateTable.require(l.disclosure().status(), DisclosureCommand.APPLY_SNAPSHOT);
            EngineRequest request = l.disclosure().engineRequest();
            return new Prepared(request, request.fingerprint(), allowance(l.rule()), agent);
        });
        GradeSnapshotPort.Fetch fetch;
        try {
            fetch = engine.request(tenant, prepared.request(), prepared.allowance());
        } catch (RuntimeException e) {
            // 엔진이 스냅샷을 주지 않았다(업무 트랜잭션은 열리지 않았다) — 실패 사실만 남기고 다시 던진다
            throw runner.failed(tenant, prepared.actor(), command, id.toString(), e);
        }
        // 쓰기 트랜잭션에서 다시 인가한다 — 엔진을 기다리는 사이 연결·조직이 바뀌었을 수 있다
        return runner.inTransaction(caller, command, id.toString(), attempt -> {
            Actor agent = attempt.granted(authz.require(caller, Action.GRADES_REQUEST, Target.disclosure(id)));
            Loaded l = load(tenant, id);
            DisclosureStateTable.require(l.disclosure().status(), DisclosureCommand.APPLY_SNAPSHOT);
            ObjectNode fetchDetail = JSON.createObjectNode().put("requestFingerprint", prepared.fingerprint())
                    .put("products", prepared.request().products().size());
            if (!l.disclosure().engineRequest().fingerprint().equals(prepared.fingerprint())) {
                fetchDetail.put("outcome", "STALE");
                if (fetch instanceof GradeSnapshotPort.Fetch.Accepted a) {
                    fetchDetail.put("snapshotId", a.snapshot().snapshot().snapshotId().value());
                }
                record(agent, AuditAction.GRADE_FETCH, id, fetchDetail);
                return reject(agent, l, command, CommandResult.Rejection.GRADE_STALE, null, List.of(), List.of());
            }
            return switch (fetch) {
                case GradeSnapshotPort.Fetch.Rejected r -> {
                    fetchDetail.put("outcome", "REJECTED");
                    if (r.snapshotIdOrNull() != null) {
                        fetchDetail.put("snapshotId", r.snapshotIdOrNull());
                    }
                    ArrayNode v = fetchDetail.putArray("violations");
                    r.violations().forEach(v::add);
                    record(agent, AuditAction.GRADE_FETCH, id, fetchDetail);
                    raise(agent, id, DisclosureFlagPort.Type.GRADE_INCONSISTENT, SEVERITY_HIGH, CommandRunner.TARGET, id.toString());
                    yield reject(agent, l, command, CommandResult.Rejection.GRADE_REJECTED, null, List.of(), r.violations());
                }
                case GradeSnapshotPort.Fetch.Accepted a -> {
                    fetchDetail.put("outcome", "ACCEPTED").put("snapshotId", a.snapshot().snapshot().snapshotId().value());
                    record(agent, AuditAction.GRADE_FETCH, id, fetchDetail);
                    TransitionOutcome o = l.disclosure().applySnapshot(a.snapshot(), l.check());
                    if (o instanceof TransitionOutcome.Rejected) {
                        // 스냅샷을 적용한 모습이 GRADE 단계를 통과하지 못했다 — 엔진 응답을 쓸 수 없다는 신호
                        raise(agent, id, DisclosureFlagPort.Type.GRADE_INCONSISTENT, SEVERITY_HIGH, CommandRunner.TARGET, id.toString());
                    }
                    yield apply(agent, l, o, JSON.createObjectNode().put("snapshotId", a.snapshot().snapshot().snapshotId().value()));
                }
            };
        });
    }

    // ------------------------------------------------------------------ 검증·예외 승인

    /** 단계 검증 드라이런: 상태를 바꾸지 않고 결과만 돌려주며 감사에 남긴다. */
    @UseCaseEntry(Action.VALIDATE)
    public List<ValidationResult> validate(Caller caller, DisclosureId id, ValidationStage stage) {
        TenantId tenant = caller.tenant();
        return runner.inTransaction(caller, "VALIDATE_" + stage.name(), id.toString(), attempt -> {
            Actor actor = attempt.granted(authz.require(caller, Action.VALIDATE, Target.disclosure(id)));
            Loaded l = load(tenant, id);
            List<ValidationResult> results = l.check().run(stage, l.disclosure());
            recordValidation(actor, l, stage, results, true);
            return results;
        });
    }

    /**
     * 관리자 예외 승인 기록. 인가는 MANAGER(조직 범위, 6A)이고, 승인자가 룰의 예외 승인 역할을 {@code identity_link}에 가져야 한다 — 기록하는
     * 역할은 그 룰 역할이다(CLI 감사 역할 OPERATOR와 무관). 승인할 대상은 지금 확인서의 오버라이드 가능 실패(SEAL 단계 기준) 중 규칙·대상 해시가 같은 것이어야
     * 한다 — 호출자가 본 대상과 지금 대상이 다르면 명령 오류다(본 적 없는 대상을 승인하지 않는다).
     */
    @UseCaseEntry(Action.EXCEPTION_APPROVE)
    public Review approveException(Caller caller, DisclosureId id, String ruleId, String subjectHash, String reason) {
        TenantId tenant = caller.tenant();
        return runner.inTransaction(caller, "APPROVE_EXCEPTION", id.toString(), attempt -> {
            Actor manager = attempt.granted(authz.require(caller, Action.EXCEPTION_APPROVE, Target.disclosure(id)));
            Loaded l = load(tenant, id);
            if (!l.disclosure().status().isMutable()) {
                throw new CommandRejectedException("SEALED", "exception approvals are recorded only before sealing");
            }
            boolean current = l.check().run(ValidationStage.SEAL, l.disclosure()).stream()
                    .anyMatch(r -> r.overridable() && r.ruleId().equals(ruleId) && r.subjectHash().orElseThrow().equals(subjectHash));
            if (!current) {
                throw new CommandRejectedException("APPROVAL_SUBJECT_MISMATCH",
                        "no current overridable failure of " + ruleId + " with the given subject hash");
            }
            // 승인자는 룰의 예외 승인 역할을 identity_link에 가진다(6A — 역할은 토큰·CLI 인자가 아니라 연결에서, 절대 규칙 5)
            String approvalRole = l.rule().exceptionApprovalRole().name();
            if (!BusinessRoles.holds(agents, manager, approvalRole)) {
                throw new CommandRejectedException("APPROVAL_ROLE_REQUIRED", "the approver does not hold the exception approval role");
            }
            Disclosure d = l.disclosure();
            Review review = new Review(UUID.randomUUID(), id, ruleId, subjectHash, d.ruleVersionId(), d.tenantRuleVersionId().orElse(null),
                    manager.subject(), approvalRole, clock.instant(), reason);
            reviews.append(review);
            ObjectNode detail = JSON.createObjectNode().put("reviewId", review.reviewId().toString()).put("ruleId", ruleId)
                    .put("subjectHash", subjectHash).put("ruleVersionId", d.ruleVersionId().value());
            d.tenantRuleVersionId().ifPresentOrElse(v -> detail.put("tenantRuleVersionId", v.value()),
                    () -> detail.putNull("tenantRuleVersionId"));
            record(manager, AuditAction.EXCEPTION_APPROVE, id, detail);
            return review;
        });
    }

    /** 3B 봉인 조건의 판정 재료(3A W5): SEAL 단계 결과 중 승인 없이 봉인을 막는 것. 상태를 바꾸지 않는다. */
    @UseCaseEntry(Action.VALIDATE)
    public List<ValidationResult> sealBlockers(Caller caller, DisclosureId id) {
        TenantId tenant = caller.tenant();
        return runner.inTransaction(caller, "SEAL_GATE", id.toString(), attempt -> {
            Actor actor = attempt.granted(authz.require(caller, Action.VALIDATE, Target.disclosure(id)));
            Loaded l = load(tenant, id);
            List<ValidationResult> results = l.check().run(ValidationStage.SEAL, l.disclosure());
            recordValidation(actor, l, ValidationStage.SEAL, results, true);
            return SealGate.unapproved(results, reviews.findFor(id), l.disclosure().ruleVersionId(), l.disclosure().tenantRuleVersionId());
        });
    }

    // ------------------------------------------------------------------ 내부

    private record Prepared(EngineRequest request, String fingerprint, GradeSnapshotPort.Allowance allowance, Actor actor) {
    }

    private Loaded load(TenantId tenant, DisclosureId id) {
        return loader.load(tenant, id);
    }

    private static GradeSnapshotPort.Allowance allowance(EffectiveRule rule) {
        return new GradeSnapshotPort.Allowance(rule.allowedGradingPolicies(), rule.allowedRankingPolicies(), rule.allowedTieBreaks());
    }

    /**
     * 항목 입력 → 초안. 카탈로그 상품은 상담일 카탈로그 {@code defaults}에서 {@link Bind#CATALOG_DEFAULT}에 결속된 항목의 같은 코드 값을
     * 복사한다(카탈로그 파일 스키마: defaults = {항목 코드: 값}). 설계사가 입력할 수 있는 항목은 {@link Bind#AGENT_INPUT}이고, 임시등록은
     * 카탈로그 기본값이 없으므로 {@link Bind#CATALOG_DEFAULT} 항목도 설계사가 입력한다.
     */
    private ItemDraft draft(TenantId tenant, Loaded l, ItemInput input) {
        Set<Bind> editable = input instanceof ItemInput.Temp ? EnumSet.of(Bind.AGENT_INPUT, Bind.CATALOG_DEFAULT)
                : EnumSet.of(Bind.AGENT_INPUT);
        Map<String, FieldValue> values = new LinkedHashMap<>();
        return switch (input) {
            case ItemInput.Catalog c -> {
                CatalogProduct p = catalog.getProduct(tenant, c.productKey(), l.disclosure().consultDate())
                        .orElseThrow(() -> new CommandRejectedException("UNKNOWN_PRODUCT",
                                "product " + c.productKey() + " is not on sale on " + l.disclosure().consultDate()));
                for (TemplateField f : l.template().fields()) {
                    JsonNode v = p.defaults() == null ? null : p.defaults().get(f.code());
                    if (f.bind() == Bind.CATALOG_DEFAULT && v != null && !v.isNull()) {
                        values.put(f.code(), new FieldValue(canonical(v), FieldValue.Origin.CATALOG));
                    }
                }
                agentValues(l.template(), c.agentValues(), editable, values);
                yield ItemDraft.catalog(c.productKey(), p.group(), p.name(), c.recommended(), c.requestedByCustomer(), values);
            }
            case ItemInput.Temp t -> {
                agentValues(l.template(), t.agentValues(), editable, values);
                yield ItemDraft.temp(t.insurer(), l.disclosure().groupCode(), t.productName(), t.quoteDocNo(), t.recommended(),
                        t.requestedByCustomer(), values);
            }
        };
    }

    private static void agentValues(TemplateResolution template, Map<String, JsonNode> input, Set<Bind> editable,
                                     Map<String, FieldValue> into) {
        input.forEach((code, value) -> {
            TemplateField f = template.field(code)
                    .orElseThrow(() -> new CommandRejectedException("UNKNOWN_FIELD", "template has no field " + code));
            if (!editable.contains(f.bind())) {
                throw new CommandRejectedException("FIELD_NOT_EDITABLE", "field " + code + " (" + f.bind() + ") is not entered by the agent");
            }
            into.put(code, new FieldValue(canonical(value), FieldValue.Origin.AGENT));
        });
    }

    private static String canonical(JsonNode node) {
        return CanonicalValue.of(node);
    }

    /** 애그리게이트 결과를 저장·감사·플래그로 옮긴다(같은 트랜잭션). */
    private CommandResult apply(Actor actor, Loaded l, TransitionOutcome outcome, ObjectNode extra) {
        Disclosure d = l.disclosure();
        if (outcome.stage() != null) {
            recordValidation(actor, l, outcome.stage(), outcome.results(), false);
        }
        return switch (outcome) {
            case TransitionOutcome.Rejected r ->
                    reject(actor, l, r.command().name(), CommandResult.Rejection.VALIDATION_BLOCKED, r.stage(), r.results(), List.of());
            case TransitionOutcome.Applied a -> {
                store.save(d);
                ObjectNode detail = JSON.createObjectNode().put("command", a.command().name()).put("from", a.from().name())
                        .put("to", a.to().name());
                detail.setAll(extra);
                record(actor, AuditAction.DISCLOSURE_TRANSITION, d.id(), detail);
                for (ValidationResult f : a.overridableFailures()) {
                    raise(actor, d.id(), DisclosureFlagPort.Type.VALIDATION_OVERRIDE, SEVERITY_MEDIUM, "DISCLOSURE_RULE",
                            d.id() + "/" + f.ruleId());
                }
                yield new CommandResult(d.id(), d.status(), null, a.results(), List.of());
            }
        };
    }

    private CommandResult reject(Actor actor, Loaded l, String command, CommandResult.Rejection reason, ValidationStage stageOrNull,
                                 List<ValidationResult> results, List<String> engineViolations) {
        ObjectNode detail = JSON.createObjectNode().put("command", command).put("reason", reason.name())
                .put("status", l.disclosure().status().name());
        if (stageOrNull != null) {
            detail.put("stage", stageOrNull.name());
            ArrayNode blocking = detail.putArray("blocking");
            results.stream().filter(ValidationResult::blocking).forEach(r -> blocking.add(r.ruleId()));
        }
        record(actor, AuditAction.DISCLOSURE_REJECT, l.disclosure().id(), detail);
        return new CommandResult(l.disclosure().id(), l.disclosure().status(), reason, results, engineViolations);
    }

    /** 검증 1회: 단계·결과와 함께 판정에 쓴 룰·서식의 정체(고정 ID·본문 해시)를 남긴다 — 어떤 룰이 이 판정을 냈는지 재현할 수 있게. */
    private void recordValidation(Actor actor, Loaded l, ValidationStage stage, List<ValidationResult> results, boolean dryRun) {
        DisclosureId id = l.disclosure().id();
        ObjectNode detail = JSON.createObjectNode().put("stage", stage.name()).put("dryRun", dryRun)
                .put("ruleVersionId", l.rule().globalRuleVersionId().value()).put("ruleBodyHash", l.rule().bodyHash())
                .put("templateId", l.template().ref().templateId()).put("templateVersion", l.template().ref().version());
        l.rule().tenantRuleVersion().ifPresentOrElse(v -> detail.put("tenantRuleVersionId", v.value()),
                () -> detail.putNull("tenantRuleVersionId"));
        ArrayNode rows = detail.putArray("results");
        for (ValidationResult r : results) {
            ObjectNode row = rows.addObject().put("ruleId", r.ruleId()).put("passed", r.passed()).put("overridable", r.overridable())
                    .put("message", r.message());
            r.subjectHash().ifPresent(h -> row.put("subjectHash", h));
        }
        record(actor, AuditAction.DISCLOSURE_VALIDATE, id, detail);
    }

    private void raise(Actor actor, DisclosureId id, DisclosureFlagPort.Type type, String severity, String targetKind, String targetId) {
        DisclosureFlagPort.RaisedFlag flag = flags.raise(type, severity, id, targetKind, targetId, clock.instant());
        if (flag.created()) {
            audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.FLAG_RAISE, targetKind, targetId,
                    JSON.createObjectNode().put("flagId", flag.flagId().toString()).put("type", type.name()).put("severity", severity)));
        }
    }

    private void record(Actor actor, AuditAction action, DisclosureId id, ObjectNode detail) {
        audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), action, CommandRunner.TARGET, id.toString(), detail));
    }

}
