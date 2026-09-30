package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.disclosure.AgentReason;
import com.ga.disclosure.domain.disclosure.DisclosureCommand;
import com.ga.disclosure.domain.disclosure.DisclosureStateTable;
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
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject;

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
    private final RuleVersionId ruleVersionId;
    private final RuleVersionId tenantRuleVersionIdOrNull;
    private final TemplateRef template;
    private final IssuerMode issuerMode;
    private final DisclosureContext context;

    private DisclosureStatus status;
    private List<DisclosureItem> items;
    private EngineSnapshot snapshotOrNull;

    private Disclosure(DisclosureId id, String agentId, CustomerRef customerRef, GroupCode groupCode, LocalDate consultDate,
                       RuleVersionId ruleVersionId, RuleVersionId tenantRuleVersionIdOrNull, TemplateRef template, IssuerMode issuerMode,
                       DisclosureContext context, DisclosureStatus status, List<DisclosureItem> items, EngineSnapshot snapshotOrNull) {
        this.id = Objects.requireNonNull(id, "id");
        this.agentId = Objects.requireNonNull(agentId, "agentId");
        this.customerRef = Objects.requireNonNull(customerRef, "customerRef");
        this.groupCode = Objects.requireNonNull(groupCode, "groupCode");
        this.consultDate = Objects.requireNonNull(consultDate, "consultDate");
        this.ruleVersionId = Objects.requireNonNull(ruleVersionId, "ruleVersionId");
        this.tenantRuleVersionIdOrNull = tenantRuleVersionIdOrNull;
        this.template = Objects.requireNonNull(template, "template");
        this.issuerMode = Objects.requireNonNull(issuerMode, "issuerMode");
        this.context = Objects.requireNonNull(context, "context");
        if (!context.template().ref().equals(template)) {
            throw new IllegalArgumentException("context template " + context.template().ref() + " is not the pinned " + template);
        }
        this.status = Objects.requireNonNull(status, "status");
        this.items = List.copyOf(items);
        this.snapshotOrNull = snapshotOrNull;
        checkInvariants();
    }

    /** 새 초안: 항목 없음, 룰·서식 버전은 호출자가 상담일로 한 번 해석해 고정한 값. */
    public static Disclosure draft(DisclosureId id, String agentId, CustomerRef customerRef, GroupCode groupCode, LocalDate consultDate,
                                   RuleVersionId ruleVersionId, RuleVersionId tenantRuleVersionIdOrNull, TemplateRef template,
                                   IssuerMode issuerMode, DisclosureContext context) {
        return new Disclosure(id, agentId, customerRef, groupCode, consultDate, ruleVersionId, tenantRuleVersionIdOrNull, template,
                issuerMode, context, DisclosureStatus.DRAFT, List.of(), null);
    }

    /** 저장소에서 복원. 불변식(스냅샷 ↔ 상태·항목)을 다시 검사한다. */
    public static Disclosure restore(DisclosureId id, String agentId, CustomerRef customerRef, GroupCode groupCode, LocalDate consultDate,
                                     RuleVersionId ruleVersionId, RuleVersionId tenantRuleVersionIdOrNull, TemplateRef template,
                                     IssuerMode issuerMode, DisclosureContext context, DisclosureStatus status, List<DisclosureItem> items,
                                     EngineSnapshot snapshotOrNull) {
        return new Disclosure(id, agentId, customerRef, groupCode, consultDate, ruleVersionId, tenantRuleVersionIdOrNull, template,
                issuerMode, context, status, items, snapshotOrNull);
    }

    private Disclosure copy() {
        return new Disclosure(id, agentId, customerRef, groupCode, consultDate, ruleVersionId, tenantRuleVersionIdOrNull, template,
                issuerMode, context, status, items, snapshotOrNull);
    }

    private void adopt(Disclosure candidate) {
        this.status = candidate.status;
        this.items = candidate.items;
        this.snapshotOrNull = candidate.snapshotOrNull;
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

    /** 스냅샷 ↔ 상태·항목 불변식(W2): 산출 전 상태에는 스냅샷이 없고, 스냅샷이 있으면 모든 항목이 산출돼 있으며 엔진 집합 = 요청 집합이다. */
    private void checkInvariants() {
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

    @Override
    public Map<String, String> documentFieldValues() {
        return FieldValueView.document(context.template());
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
        public Map<String, String> fieldValues() {
            return FieldValueView.item(context.template(), item);
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
