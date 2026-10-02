package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.disclosure.AgentReason;
import com.ga.disclosure.domain.disclosure.DisclosureCommand;
import com.ga.disclosure.domain.disclosure.DisclosureStateTable;
import com.ga.disclosure.domain.disclosure.FieldValue;
import com.ga.disclosure.domain.disclosure.ItemDraft;
import com.ga.disclosure.domain.disclosure.ItemGrade;
import com.ga.disclosure.domain.disclosure.Recommendation;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.IssuerMode;
import com.ga.disclosure.domain.enums.ValidationStage;
import com.ga.disclosure.domain.grade.EngineSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshotItem;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.domain.vo.TemplateRef;
import com.ga.disclosure.rules.template.BindingView;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;
import com.ga.disclosure.workflow.catalog.PanelEntry;
import com.ga.platform.canonical.Canonicalizer;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 확인서 애그리게이트(설계서 §6.1, 3A 계획 §2): 헤더·비교 항목·등급 복사본·추천사유·스냅샷 요약을 한 단위로 다룬다. 상태는 이 클래스의
 * 명령 메서드로만 바뀐다(setter 없음, 저장소는 {@code insert·loadForUpdate·save}만).
 *
 * <p>각 명령은 ① 상태 × 명령 표({@link DisclosureStateTable}, 밖이면 {@code IllegalTransition}) ② 명령을 적용한 <b>후보</b>에 대해 그 단계
 * 검증을 돌려 ③ 막는 실패(오버라이드 불가)가 없을 때만 후보를 적용한다({@link TransitionOutcome.Applied}). 막히면 아무것도 바뀌지 않는다
 * ({@link TransitionOutcome.Rejected}). 오버라이드 가능한 실패는 중간 단계에서 전이를 막지 않는다 — 승인 확인은 봉인 조건(3A 계획 Q2).
 *
 * <p>밖에 있는 것: 룰·서식 본문(유스케이스가 고정 ID로 로드해 {@link StageCheck}·{@link DisclosureContext}로 건넨다), 카탈로그, 엔진 호출
 * (검증을 마친 {@link EngineSnapshot}만 들어온다), 예외 승인, 준법 플래그·감사, 고객 개인정보({@code customerRef}만 가진다).
 * 상담일을 바꾸는 명령은 없다 — 상담일이 다르면 새 초안을 만든다.
 */
public final class Disclosure implements ValidationSubject {

    /** 항목 수 상한(열 수 — 저장 형식 {@code SMALLINT}·화면 한계에 대한 안전 상한이며 규제 룰이 아니다; 최소 개수는 룰 데이터). */
    public static final int MAX_ITEMS = 50;

    private final DisclosureId id;
    private final String agentId;
    private final CustomerRef customerRef;
    private final GroupCode groupCode;
    private final LocalDate consultDate;
    private final IssuerMode issuerMode;
    private final Lineage lineage;

    /** 고정 룰·서식과 그 판단 재료. 재기준(REBASE)만 바꾼다 — 상담일은 그대로다. */
    private RuleVersionId ruleVersionId;
    private RuleVersionId tenantRuleVersionIdOrNull;
    private TemplateRef template;
    private DisclosureContext context;

    private DisclosureStatus status;
    private List<DisclosureItem> items;
    private EngineSnapshot snapshotOrNull;
    private SealStamp sealOrNull;
    private VoidMark voidOrNull;
    private DisclosureId supersededByOrNull;
    private LifecycleReason supersedeReasonOrNull;

    private Disclosure(DisclosureId id, String agentId, CustomerRef customerRef, GroupCode groupCode, LocalDate consultDate,
                       RuleVersionId ruleVersionId, RuleVersionId tenantRuleVersionIdOrNull, TemplateRef template, IssuerMode issuerMode,
                       DisclosureContext context, Lineage lineage, DisclosureStatus status, List<DisclosureItem> items,
                       EngineSnapshot snapshotOrNull, SealStamp sealOrNull, VoidMark voidOrNull, DisclosureId supersededByOrNull,
                       LifecycleReason supersedeReasonOrNull) {
        this.id = Objects.requireNonNull(id, "id");
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.customerRef = Objects.requireNonNull(customerRef, "customerRef");
        this.groupCode = Objects.requireNonNull(groupCode, "groupCode");
        this.consultDate = Objects.requireNonNull(consultDate, "consultDate");
        this.issuerMode = Objects.requireNonNull(issuerMode, "issuerMode");
        this.lineage = Objects.requireNonNull(lineage, "lineage");
        pin(ruleVersionId, tenantRuleVersionIdOrNull, template, context);
        this.status = Objects.requireNonNull(status, "status");
        this.items = List.copyOf(items);
        this.snapshotOrNull = snapshotOrNull;
        this.sealOrNull = sealOrNull;
        this.voidOrNull = voidOrNull;
        this.supersededByOrNull = supersededByOrNull;
        this.supersedeReasonOrNull = supersedeReasonOrNull;
        checkInvariants();
    }

    private void pin(RuleVersionId rule, RuleVersionId tenantRuleOrNull, TemplateRef pinnedTemplate, DisclosureContext pinnedContext) {
        Objects.requireNonNull(rule, "ruleVersionId");
        Objects.requireNonNull(pinnedTemplate, "template");
        Objects.requireNonNull(pinnedContext, "context");
        if (!pinnedContext.template().ref().equals(pinnedTemplate)) {
            throw new IllegalArgumentException("context template " + pinnedContext.template().ref() + " is not the pinned " + pinnedTemplate);
        }
        this.ruleVersionId = rule;
        this.tenantRuleVersionIdOrNull = tenantRuleOrNull;
        this.template = pinnedTemplate;
        this.context = pinnedContext;
    }

    /** 새 초안: 항목 없음, 룰·서식 버전은 호출자가 상담일로 한 번 해석해 고정한 값. */
    public static Disclosure draft(DisclosureId id, String agentId, CustomerRef customerRef, GroupCode groupCode, LocalDate consultDate,
                                   RuleVersionId ruleVersionId, RuleVersionId tenantRuleVersionIdOrNull, TemplateRef template,
                                   IssuerMode issuerMode, DisclosureContext context) {
        return new Disclosure(id, agentId, customerRef, groupCode, consultDate, ruleVersionId, tenantRuleVersionIdOrNull, template,
                issuerMode, context, Lineage.FIRST, DisclosureStatus.DRAFT, List.of(), null, null, null, null, null);
    }

    /**
     * 정정본 초안(SUPERSEDE, 설계서 §6.6): 원본과 같은 고객·설계사·상품군·상담일·정본 모드, 버전 + 1과 원본 ID. 항목은 입력만 복제하고 등급
     * 복사본·추천사유는 복제하지 않는다(재산출·재입력 — 절대 규칙 7). 룰·서식은 호출자가 원본 상담일로 다시 해석해 고정한 값이다.
     */
    public static Disclosure supersedingDraft(DisclosureId newId, Disclosure original, RuleVersionId ruleVersionId,
                                              RuleVersionId tenantRuleVersionIdOrNull, TemplateRef template, DisclosureContext context) {
        List<DisclosureItem> copied = original.items.stream().map(DisclosureItem::ungraded).toList();
        return new Disclosure(newId, original.agentId, original.customerRef, original.groupCode, original.consultDate, ruleVersionId,
                tenantRuleVersionIdOrNull, template, original.issuerMode, context, original.lineage.next(original.id), DisclosureStatus.DRAFT,
                copied, null, null, null, null, null);
    }

    /** 저장소에서 복원. 불변식(스냅샷·봉인·무효·정정 ↔ 상태·항목)을 다시 검사한다. */
    public static Disclosure restore(DisclosureId id, String agentId, CustomerRef customerRef, GroupCode groupCode, LocalDate consultDate,
                                     RuleVersionId ruleVersionId, RuleVersionId tenantRuleVersionIdOrNull, TemplateRef template,
                                     IssuerMode issuerMode, DisclosureContext context, Lineage lineage, DisclosureStatus status,
                                     List<DisclosureItem> items, EngineSnapshot snapshotOrNull, SealStamp sealOrNull, VoidMark voidOrNull,
                                     DisclosureId supersededByOrNull, LifecycleReason supersedeReasonOrNull) {
        return new Disclosure(id, agentId, customerRef, groupCode, consultDate, ruleVersionId, tenantRuleVersionIdOrNull, template,
                issuerMode, context, lineage, status, items, snapshotOrNull, sealOrNull, voidOrNull, supersededByOrNull, supersedeReasonOrNull);
    }

    private Disclosure copy() {
        return new Disclosure(id, agentId, customerRef, groupCode, consultDate, ruleVersionId, tenantRuleVersionIdOrNull, template,
                issuerMode, context, lineage, status, items, snapshotOrNull, sealOrNull, voidOrNull, supersededByOrNull, supersedeReasonOrNull);
    }

    private void adopt(Disclosure candidate) {
        this.status = candidate.status;
        this.items = candidate.items;
        this.snapshotOrNull = candidate.snapshotOrNull;
        this.sealOrNull = candidate.sealOrNull;
        this.voidOrNull = candidate.voidOrNull;
        this.supersededByOrNull = candidate.supersededByOrNull;
        this.supersedeReasonOrNull = candidate.supersedeReasonOrNull;
        if (candidate.ruleVersionId != ruleVersionId || candidate.template != template || candidate.context != context
                || candidate.tenantRuleVersionIdOrNull != tenantRuleVersionIdOrNull) {
            pin(candidate.ruleVersionId, candidate.tenantRuleVersionIdOrNull, candidate.template, candidate.context);
        }
        checkInvariants();
    }

    // ------------------------------------------------------------------ 명령

    /**
     * 비교 항목 교체. DRAFT에서는 검증 없이 교체한다(작성 중). COMPARED 이후에서는 교체한 후보로 COMPARE 단계를 다시 통과해야 하고,
     * 산출 이후(GRADED·REASONED)면 스냅샷과 추천사유를 버리고 COMPARED로 돌아간다(설계서 §6.1 — 등급이 상품과 어긋나는 상태를 만들지 않는다).
     */
    public TransitionOutcome replaceItems(List<ItemDraft> drafts, StageCheck check) {
        DisclosureCommand command = DisclosureCommand.REPLACE_ITEMS;
        DisclosureStateTable.require(status, command);
        List<DisclosureItem> next = number(drafts);
        Disclosure candidate = copy();
        candidate.items = next;
        candidate.snapshotOrNull = null;
        if (status == DisclosureStatus.DRAFT) {
            candidate.status = DisclosureStateTable.target(status, command, DisclosureStatus.DRAFT);
            DisclosureStatus from = status;
            adopt(candidate);
            return new TransitionOutcome.Applied(command, from, status, null, List.of());
        }
        candidate.status = DisclosureStateTable.target(status, command, DisclosureStatus.COMPARED);
        return gate(command, ValidationStage.COMPARE, candidate, check);
    }

    /** DRAFT → COMPARED: 비교 단계 검증(항목 수·보험사 상이·상품군·패널·임시등록 발행번호). */
    public TransitionOutcome compare(StageCheck check) {
        DisclosureCommand command = DisclosureCommand.COMPARE;
        DisclosureStateTable.require(status, command);
        Disclosure candidate = copy();
        candidate.status = DisclosureStateTable.target(status, command, DisclosureStatus.COMPARED);
        return gate(command, ValidationStage.COMPARE, candidate, check);
    }

    /**
     * 엔진 스냅샷 적용(첫 산출·재산출, 3A 계획 Q5): COMPARED·GRADED·REASONED → GRADED. 스냅샷은 스키마·정합성 검증을 마친 것이어야 하고
     * 그 상품 집합은 지금 확인서의 요청 집합(임시등록 제외)과 같아야 한다 — 다르면 호출자 오류다(유스케이스가 먼저 {@code GRADE_STALE}로 거른다).
     * 엔진 결과를 항목에 복사하고 임시등록 항목은 로컬 {@code UNAVAILABLE(TEMP_PRODUCT, LOCAL)}로 채운다. 추천사유는 버린다(규칙 7).
     */
    public TransitionOutcome applySnapshot(EngineSnapshot engineSnapshot, StageCheck check) {
        DisclosureCommand command = DisclosureCommand.APPLY_SNAPSHOT;
        DisclosureStateTable.require(status, command);
        Objects.requireNonNull(engineSnapshot, "engineSnapshot");
        Map<ProductKey, ItemGrade> byKey = new HashMap<>();
        for (GradeSnapshotItem r : engineSnapshot.snapshot().items()) {
            ItemGrade grade = r.isAvailable()
                    ? new ItemGrade.Ok(r.gradeCode(), r.gradeLabel(), r.gradeOrdinal(), r.rankInSet(), r.tie(), r.ratioToAvg())
                    : ItemGrade.Unavailable.engine(r.unavailableReason());
            if (byKey.put(r.productKey(), grade) != null) {
                throw new IllegalArgumentException("snapshot lists " + r.productKey() + " twice");
            }
        }
        Set<ProductKey> requested = new HashSet<>(engineRequest().products());
        if (!byKey.keySet().equals(requested)) {
            throw new IllegalArgumentException("snapshot " + engineSnapshot.snapshot().snapshotId() + " does not cover exactly the requested items");
        }
        List<DisclosureItem> graded = new ArrayList<>();
        for (DisclosureItem item : items) {
            ItemGrade grade = item.draft().tempProduct() ? ItemGrade.Unavailable.localTempProduct()
                    : byKey.get(item.draft().productKey().orElseThrow());
            graded.add(item.ungraded().withGrade(grade));
        }
        Disclosure candidate = copy();
        candidate.items = List.copyOf(graded);
        candidate.snapshotOrNull = engineSnapshot;
        candidate.status = DisclosureStateTable.target(status, command, DisclosureStatus.GRADED);
        return gate(command, ValidationStage.GRADE, candidate, check);
    }

    /**
     * 추천사유 입력: GRADED·REASONED → REASONED. 설계사 입력은 <b>추천 항목</b>에만 받는다. 고객 요청 항목에는 룰 데이터가 {@code auto=true}로
     * 정한 코드를 시스템이 붙인다(R-REQUESTED, "고객 요청 보험사 포함" 표시) — 사유 텍스트나 설계사 선택 코드를 시스템이 만들지는 않는다
     * (절대 규칙 7). 입력은 이전 사유 전체를 대체한다.
     */
    public TransitionOutcome setRecommendations(List<AgentReason> reasons, Set<ReasonCode> autoCodes, StageCheck check) {
        DisclosureCommand command = DisclosureCommand.SET_RECOMMENDATIONS;
        DisclosureStateTable.require(status, command);
        Map<Integer, AgentReason> byItem = new HashMap<>();
        for (AgentReason r : reasons) {
            if (r.itemNo() > items.size()) {
                throw new IllegalArgumentException("no item " + r.itemNo());
            }
            if (!items.get(r.itemNo() - 1).draft().recommended()) {
                throw new IllegalArgumentException("item " + r.itemNo() + " is not recommended — reasons are entered only for recommended items");
            }
            if (byItem.put(r.itemNo(), r) != null) {
                throw new IllegalArgumentException("item " + r.itemNo() + " has two reason entries");
            }
        }
        List<DisclosureItem> next = new ArrayList<>();
        for (DisclosureItem item : items) {
            AgentReason agent = byItem.get(item.itemNo());
            Set<ReasonCode> codes = new LinkedHashSet<>(agent == null ? List.of() : agent.codes());
            if (item.draft().requestedByCustomer()) {
                codes.addAll(autoCodes);
            }
            String text = agent == null ? null : agent.textOrNull();
            next.add(codes.isEmpty() && text == null ? item.withoutRecommendation()
                    : item.withRecommendation(new Recommendation(List.copyOf(codes), text)));
        }
        Disclosure candidate = copy();
        candidate.items = List.copyOf(next);
        candidate.status = DisclosureStateTable.target(status, command, DisclosureStatus.REASONED);
        return gate(command, ValidationStage.REASON, candidate, check);
    }

    /**
     * 봉인(REASONED → SEALED): 봉인 조건 평가·채번·렌더·체인은 봉인 유스케이스가 한 트랜잭션에서 마쳤다 — 애그리게이트는 그 결과(봉인 도장)를
     * 받아 상태를 옮긴다. 항목·스냅샷·추천사유는 그대로이며 이후 불변이다(V3).
     */
    public TransitionOutcome seal(SealStamp stamp) {
        DisclosureCommand command = DisclosureCommand.SEAL;
        DisclosureStateTable.require(status, command);
        Objects.requireNonNull(stamp, "stamp");
        if (snapshotOrNull == null) {
            throw new IllegalStateException("only a graded and reasoned disclosure is sealed");
        }
        Disclosure candidate = copy();
        candidate.sealOrNull = stamp;
        candidate.status = DisclosureStateTable.target(status, command, DisclosureStatus.SEALED);
        DisclosureStatus from = status;
        adopt(candidate);
        return new TransitionOutcome.Applied(command, from, status, null, List.of());
    }

    /** 무효(VOID): 표가 허용하는 모든 상태에서. 봉인 후 무효는 번호·봉인 컬럼을 유지한다(재사용 없음, 3B 계획 승인 Q8). */
    public TransitionOutcome voidWith(VoidMark mark) {
        DisclosureCommand command = DisclosureCommand.VOID;
        DisclosureStateTable.require(status, command);
        Objects.requireNonNull(mark, "mark");
        Disclosure candidate = copy();
        candidate.voidOrNull = mark;
        candidate.status = DisclosureStateTable.target(status, command, DisclosureStatus.VOID);
        DisclosureStatus from = status;
        adopt(candidate);
        return new TransitionOutcome.Applied(command, from, status, null, List.of());
    }

    /**
     * 정정(SUPERSEDE): 봉인 이후 상태 → SUPERSEDED, 후속 버전 ID와 정정 사유를 한 번만 기록한다(V3 GD004, V8 GD100). 새 버전은
     * {@link #supersedingDraft}.
     */
    public TransitionOutcome supersede(DisclosureId next, LifecycleReason reason) {
        DisclosureCommand command = DisclosureCommand.SUPERSEDE;
        DisclosureStateTable.require(status, command);
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(reason, "reason");
        if (next.equals(id)) {
            throw new IllegalArgumentException("a disclosure cannot supersede itself");
        }
        Disclosure candidate = copy();
        candidate.supersededByOrNull = next;
        candidate.supersedeReasonOrNull = reason;
        candidate.status = DisclosureStateTable.target(status, command, DisclosureStatus.SUPERSEDED);
        DisclosureStatus from = status;
        adopt(candidate);
        return new TransitionOutcome.Applied(command, from, status, null, List.of());
    }

    /**
     * 재기준(REBASE, 3A 수용심사 §3-8): 상담일 재해석으로 얻은 룰·서식을 새로 고정하고 스냅샷·추천사유를 버린다(항목 입력은 그대로). 새 룰의 COMPARE
     * 단계 검증을 통과하면 COMPARED, 오버라이드 불가 실패가 있으면 DRAFT(3B 계획 승인 Q3) — 어느 쪽이든 적용되고 결과에 검증 결과를 싣는다.
     */
    public TransitionOutcome rebase(RuleVersionId newRule, RuleVersionId newTenantRuleOrNull, TemplateRef newTemplate,
                                    DisclosureContext newContext, StageCheck newCheck) {
        DisclosureCommand command = DisclosureCommand.REBASE;
        DisclosureStateTable.require(status, command);
        Disclosure candidate = copy();
        candidate.pin(newRule, newTenantRuleOrNull, newTemplate, newContext);
        candidate.items = items.stream().map(DisclosureItem::ungraded).toList();
        candidate.snapshotOrNull = null;
        candidate.status = DisclosureStatus.COMPARED;
        List<ValidationResult> results = newCheck.run(ValidationStage.COMPARE, candidate);
        boolean blocked = results.stream().anyMatch(ValidationResult::blocking);
        candidate.status = DisclosureStateTable.target(status, command, blocked ? DisclosureStatus.DRAFT : DisclosureStatus.COMPARED);
        DisclosureStatus from = status;
        adopt(candidate);
        return new TransitionOutcome.Applied(command, from, status, ValidationStage.COMPARE, results);
    }

    private TransitionOutcome gate(DisclosureCommand command, ValidationStage stage, Disclosure candidate, StageCheck check) {
        List<ValidationResult> results = check.run(stage, candidate);
        DisclosureStatus from = status;
        if (results.stream().anyMatch(ValidationResult::blocking)) {
            return new TransitionOutcome.Rejected(command, from, stage, results);
        }
        adopt(candidate);
        return new TransitionOutcome.Applied(command, from, status, stage, results);
    }

    private static List<DisclosureItem> number(List<ItemDraft> drafts) {
        if (drafts.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("at most " + MAX_ITEMS + " items");
        }
        Set<ProductKey> seen = new HashSet<>();
        List<DisclosureItem> out = new ArrayList<>();
        for (ItemDraft d : drafts) {
            d.productKey().ifPresent(k -> {
                if (!seen.add(k)) {
                    throw new IllegalArgumentException("product " + k + " appears twice");
                }
            });
            out.add(new DisclosureItem(out.size() + 1, d, null, null));
        }
        return List.copyOf(out);
    }

    /**
     * 불변식: 스냅샷 ↔ 상태·항목(W2 — 산출 전 상태에는 스냅샷이 없고, 스냅샷이 있으면 모든 항목이 산출돼 있으며 엔진 집합 = 요청 집합), 그리고
     * 봉인·무효·정정 ↔ 상태(V7과 같은 규칙: 봉인 도장은 가변 상태에 없고 VOID 밖의 봉인 이후 상태에는 있다, VOID ⇔ 무효 표시, SUPERSEDED ⇔ 후속 ID).
     */
    private void checkInvariants() {
        if (status.isMutable() && sealOrNull != null) {
            throw new IllegalStateException(status + " cannot carry a seal");
        }
        if (status.isSealedOrLater() && status != DisclosureStatus.VOID && sealOrNull == null) {
            throw new IllegalStateException(status + " requires a seal");
        }
        if ((status == DisclosureStatus.VOID) != (voidOrNull != null)) {
            throw new IllegalStateException("void mark exactly on VOID (status " + status + ")");
        }
        if ((status == DisclosureStatus.SUPERSEDED) != (supersededByOrNull != null)
                || (supersededByOrNull != null) != (supersedeReasonOrNull != null)) {
            throw new IllegalStateException("superseded-by and its reason exactly on SUPERSEDED (status " + status + ")");
        }
        if (sealOrNull != null && snapshotOrNull == null) {
            throw new IllegalStateException("a sealed disclosure carries its engine snapshot");
        }
        boolean graded = snapshotOrNull != null;
        if (graded && (status == DisclosureStatus.DRAFT || status == DisclosureStatus.COMPARED)) {
            throw new IllegalStateException(status + " cannot carry a snapshot");
        }
        if (!graded && (status == DisclosureStatus.GRADED || status == DisclosureStatus.REASONED)) {
            throw new IllegalStateException(status + " requires a snapshot");
        }
        for (int i = 0; i < items.size(); i++) {
            DisclosureItem item = items.get(i);
            if (item.itemNo() != i + 1) {
                throw new IllegalStateException("items must be numbered 1..n in order");
            }
            if (graded != item.grade().isPresent()) {
                throw new IllegalStateException("item " + item.itemNo() + " grade does not match the snapshot state");
            }
            if (item.recommendation().isPresent() && status != DisclosureStatus.REASONED && !status.isSealedOrLater()) {
                throw new IllegalStateException("recommendations exist only from REASONED on (found in " + status + ")");
            }
        }
        if (graded) {
            Set<ProductKey> engineKeys = new HashSet<>();
            snapshotOrNull.snapshot().items().forEach(r -> engineKeys.add(r.productKey()));
            Set<ProductKey> requested = new HashSet<>();
            items.stream().filter(i -> !i.draft().tempProduct()).forEach(i -> requested.add(i.draft().productKey().orElseThrow()));
            if (!engineKeys.equals(requested)) {
                throw new IllegalStateException("snapshot set differs from the requested items");
            }
        }
    }

    // ------------------------------------------------------------------ 조회

    public EngineRequest engineRequest() {
        return new EngineRequest(consultDate, groupCode,
                items.stream().filter(i -> !i.draft().tempProduct()).map(i -> i.draft().productKey().orElseThrow()).toList());
    }

    public DisclosureId id() {
        return id;
    }

    public String agentId() {
        return agentId;
    }

    public CustomerRef customerRef() {
        return customerRef;
    }

    public RuleVersionId ruleVersionId() {
        return ruleVersionId;
    }

    public Lineage lineage() {
        return lineage;
    }

    public Optional<SealStamp> sealStamp() {
        return Optional.ofNullable(sealOrNull);
    }

    public Optional<VoidMark> voidMark() {
        return Optional.ofNullable(voidOrNull);
    }

    public Optional<DisclosureId> supersededBy() {
        return Optional.ofNullable(supersededByOrNull);
    }

    public Optional<LifecycleReason> supersedeReason() {
        return Optional.ofNullable(supersedeReasonOrNull);
    }

    public Optional<RuleVersionId> tenantRuleVersionId() {
        return Optional.ofNullable(tenantRuleVersionIdOrNull);
    }

    public TemplateRef template() {
        return template;
    }

    public IssuerMode issuerMode() {
        return issuerMode;
    }

    public DisclosureStatus status() {
        return status;
    }

    public List<DisclosureItem> disclosureItems() {
        return items;
    }

    public Optional<EngineSnapshot> engineSnapshot() {
        return Optional.ofNullable(snapshotOrNull);
    }

    // ------------------------------------------------------------------ ValidationSubject

    @Override
    public GroupCode groupCode() {
        return groupCode;
    }

    @Override
    public LocalDate consultDate() {
        return consultDate;
    }

    @Override
    public boolean largeGa() {
        return context.largeGa();
    }

    @Override
    public List<Item> items() {
        return items.stream().<Item>map(ItemView::new).toList();
    }

    @Override
    public Optional<GradeSnapshot> gradeSnapshot() {
        return engineSnapshot().map(EngineSnapshot::snapshot);
    }

    /**
     * 서식 결속 단면(3B): 번호·성명은 봉인 전이라 없다(봉인이 채운다). 상품군 이름·패널·사유 라벨은 주입된 상담일 사실이고, 항목값은
     * 저장된 {@code field_values}(출처 포함), 등급은 항목의 복사본이다.
     */
    @Override
    public BindingView bindings() {
        List<String> panelNames = context.panelOnConsultDate().stream().map(PanelEntry::insurerName).toList();
        List<BindingView.Item> views = items.stream().<BindingView.Item>map(ItemBindings::new).toList();
        return new BindingView() {
            @Override
            public Optional<String> disclosureNo() {
                return Optional.empty();
            }

            @Override
            public LocalDate consultDate() {
                return consultDate;
            }

            @Override
            public String agentId() {
                return agentId;
            }

            @Override
            public Optional<String> customerName() {
                return Optional.empty();
            }

            @Override
            public Optional<String> productGroupName() {
                return context.productGroupName();
            }

            @Override
            public List<String> panelInsurerNames() {
                return panelNames;
            }

            @Override
            public List<? extends Item> items() {
                return views;
            }
        };
    }

    /** 항목의 추천사유 라벨(고정 룰의 라벨, 룰에 없는 코드는 코드 그대로 — R-REASON이 잡는다). */
    public List<String> reasonLabels(DisclosureItem item) {
        return item.recommendation().map(r -> r.codes().stream().map(c -> context.reasonLabels().getOrDefault(c, c.value())).toList())
                .orElse(List.of());
    }

    public DisclosureContext context() {
        return context;
    }

    @Override
    public List<SignatureMark> signatures() {
        return List.of();
    }

    @Override
    public Optional<Instant> signDeadline() {
        return Optional.empty();
    }

    @Override
    public boolean isInsurerOnPanel(InsurerCode insurer, LocalDate date) {
        return context.panel().test(insurer, date);
    }

    private final class ItemBindings implements BindingView.Item {

        private final DisclosureItem item;

        ItemBindings(DisclosureItem item) {
            this.item = item;
        }

        @Override
        public Optional<String> insurerName() {
            return context.insurerName(item.draft().insurer());
        }

        @Override
        public String productName() {
            return item.draft().productName();
        }

        @Override
        public Optional<JsonNode> fieldValue(String code) {
            return Optional.ofNullable(item.draft().fieldValues().get(code)).map(v -> Canonicalizer.parseStrict(v.canonicalJson()));
        }

        @Override
        public boolean enteredByAgent(String code) {
            FieldValue v = item.draft().fieldValues().get(code);
            return v != null && v.origin() == FieldValue.Origin.AGENT;
        }

        @Override
        public Optional<ItemGrade> grade() {
            return item.grade();
        }

        @Override
        public boolean recommended() {
            return item.draft().recommended();
        }

        @Override
        public List<String> reasonLabels() {
            return Disclosure.this.reasonLabels(item);
        }

        @Override
        public Optional<String> reasonText() {
            return item.recommendation().flatMap(Recommendation::text);
        }
    }

    private final class ItemView implements Item {

        private final DisclosureItem item;

        ItemView(DisclosureItem item) {
            this.item = item;
        }

        @Override
        public Optional<ProductKey> productKey() {
            return item.draft().productKey();
        }

        @Override
        public String productName() {
            return item.draft().productName();
        }

        @Override
        public InsurerCode insurerCode() {
            return item.draft().insurer();
        }

        @Override
        public GroupCode groupCode() {
            return item.draft().group();
        }

        @Override
        public boolean isRecommended() {
            return item.draft().recommended();
        }

        @Override
        public boolean requestedByCustomer() {
            return item.draft().requestedByCustomer();
        }

        @Override
        public boolean tempProduct() {
            return item.draft().tempProduct();
        }

        @Override
        public Optional<String> quoteDocNo() {
            return item.draft().quoteDocNo();
        }

        @Override
        public List<ReasonCode> reasonCodes() {
            return item.recommendation().map(Recommendation::codes).orElse(List.of());
        }

        @Override
        public Optional<String> reasonText() {
            return item.recommendation().flatMap(Recommendation::text);
        }
    }
}
