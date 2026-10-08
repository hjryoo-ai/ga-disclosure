package com.ga.disclosure.workflow.contract;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.outbox.EventType;
import com.ga.disclosure.audit.outbox.OutboxPayloads;
import com.ga.disclosure.audit.outbox.OutboxPort;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.RetentionAnchor;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.sign.retention.RetentionAnchors;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 계약 연결 가져오기(6B 계획 §4·§A, 지시문 §3). 항목은 서로 독립 — 항목마다 한 트랜잭션이고, 한 항목의 거부가 배치를 막지 않는다.
 * <ol>
 *   <li>같은 출처 참조(배치 ID#순번)를 이미 처리했으면 그때의 결과(재수입 멱등, 새 행 없음).</li>
 *   <li>매칭: 청약번호 정확 일치 → (없거나 0건이면) 증권번호의 활성 연결. 무효·정정됨·폐기·파기는 후보가 아니다. 0건 {@code UNMATCHED}, 2건 이상
 *       {@code AMBIGUOUS_MATCH}, 봉인 전 {@code NOT_SEALED}, 가명이 다르면 {@code CUSTOMER_MISMATCH}, 그 증권이 다른 확인서에 활성으로 붙어 있으면
 *       {@code AMBIGUOUS_MATCH} — 전부 보고 행만 남는다.</li>
 *   <li>1건: 활성 연결과 내용이 같으면 {@code NOOP}, 다르면 이전 행 대체 + 새 행({@code CORRECTED}), 없으면 새 행({@code LINKED}). 확인서 현재값 투영,
 *       보존기한 = 확인서에 고정된 룰의 앵커 {@code CONTRACT_DATE}로 연장만({@link RetentionAnchors} — Phase 4 산식 그대로), 감사
 *       {@code CONTRACT_LINK_CHANGED}(증권번호는 SHA-256), 아웃박스 {@code PolicyLinked} v2(번호 없음).</li>
 * </ol>
 * 배치 끝에 요약 감사 {@code CONTRACT_LINK_IMPORT}(출처·배치 ID·입력 해시·결과별 수). 보고서에는 번호가 없다.
 */
public final class ContractLinkService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final int MAX_PURGE = 10_000;

    public enum Outcome {
        LINKED, CORRECTED, NOOP, UNMATCHED, AMBIGUOUS_MATCH, NOT_SEALED, CUSTOMER_MISMATCH
    }

    public enum RetentionChange {
        EXTENDED, NOT_EXTENDED, NONE
    }

    /** 항목 하나의 결과(번호 없음). */
    public record ItemResult(int index, Outcome outcome, boolean repeated, Optional<UUID> disclosureId, Optional<UUID> linkId,
                             RetentionChange retention) {
    }

    public record Report(String source, String batchId, String sha256, List<ItemResult> items) {
        public Report {
            items = List.copyOf(items);
        }

        public Map<Outcome, Integer> counts() {
            Map<Outcome, Integer> out = new EnumMap<>(Outcome.class);
            for (Outcome o : Outcome.values()) {
                out.put(o, 0);
            }
            items.forEach(i -> out.merge(i.outcome(), 1, Integer::sum));
            return out;
        }

        public ObjectNode toJson() {
            ObjectNode o = JSON.createObjectNode().put("kind", "CONTRACT_LINK_IMPORT").put("source", source).put("batchId", batchId).put("sha256", sha256);
            ObjectNode c = o.putObject("counts");
            counts().forEach((k, v) -> c.put(k.name(), v));
            ArrayNode list = o.putArray("items");
            for (ItemResult r : items) {
                ObjectNode n = list.addObject().put("index", r.index()).put("outcome", r.outcome().name()).put("repeated", r.repeated())
                        .put("retention", r.retention().name());
                if (r.disclosureId().isPresent()) {
                    n.put("disclosureId", r.disclosureId().get().toString());
                } else {
                    n.putNull("disclosureId");
                }
                if (r.linkId().isPresent()) {
                    n.put("linkId", r.linkId().get().toString());
                } else {
                    n.putNull("linkId");
                }
            }
            return o;
        }
    }

    public record PurgeReport(Instant receivedBefore, int purged, OptionalInt retentionDays) {
    }

    private final ContractLinkStore store;
    private final RuleResolver rules;
    private final AuditPort audit;
    private final OutboxPort outbox;
    private final WorkflowTransactions transactions;
    private final AuthorizationPort authz;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public ContractLinkService(ContractLinkStore store, RuleResolver rules, AuditPort audit, OutboxPort outbox, WorkflowTransactions transactions,
                               AuthorizationPort authz, Clock clock, Supplier<UUID> ids) {
        this.store = Objects.requireNonNull(store, "store");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @UseCaseEntry(Action.CONTRACT_LINK_IMPORT)
    public Report importBatch(Caller caller, ContractLinkBatch batch) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(batch, "batch");
        TenantId tenant = caller.tenant();
        Actor actor = transactions.inTenant(tenant, () -> authz.require(caller, Action.CONTRACT_LINK_IMPORT, Target.none()));
        List<ItemResult> results = new ArrayList<>();
        for (ContractLinkBatch.Item item : batch.items()) {
            results.add(transactions.inTenant(tenant, () -> one(tenant, actor, batch, item)));
        }
        Report report = new Report(batch.source(), batch.batchId(), batch.sha256(), results);
        transactions.inTenant(tenant, () -> {
            ObjectNode detail = JSON.createObjectNode().put("source", batch.source()).put("batchId", batch.batchId()).put("sha256", batch.sha256())
                    .put("items", batch.items().size());
            ObjectNode counts = detail.putObject("counts");
            report.counts().forEach((k, v) -> counts.put(k.name(), v));
            audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.CONTRACT_LINK_IMPORT, "TENANT", tenant.value(), detail));
            return null;
        });
        return report;
    }

    private ItemResult one(TenantId tenant, Actor actor, ContractLinkBatch batch, ContractLinkBatch.Item item) {
        String sourceRef = batch.sourceRef(item);
        Optional<UUID> done = store.linkBySource(batch.source(), sourceRef);
        if (done.isPresent()) {
            return new ItemResult(item.index(), Outcome.NOOP, true, Optional.empty(), done, RetentionChange.NONE);
        }
        Optional<String> reported = store.unmatchedBySource(batch.source(), sourceRef);
        if (reported.isPresent()) {
            return new ItemResult(item.index(), Outcome.valueOf(reported.get()), true, Optional.empty(), Optional.empty(), RetentionChange.NONE);
        }
        List<ContractLinkStore.Candidate> candidates = item.applicationNo().map(store::byApplicationNo).orElse(List.of());
        if (candidates.isEmpty()) {
            candidates = store.byActivePolicy(item.policyNo());
        }
        Outcome rejected = null;
        ContractLinkStore.Candidate target = null;
        if (candidates.isEmpty()) {
            rejected = Outcome.UNMATCHED;
        } else if (candidates.size() > 1) {
            rejected = Outcome.AMBIGUOUS_MATCH;
        } else {
            target = candidates.getFirst();
            DisclosureStatus status = DisclosureStatus.valueOf(target.status());
            ContractLinkStore.Candidate t = target;
            if (status.isMutable()) {
                rejected = Outcome.NOT_SEALED;
            } else if (item.customerRef().isPresent() && !item.customerRef().get().equals(target.customerRef())) {
                rejected = Outcome.CUSTOMER_MISMATCH;
            } else if (store.byActivePolicy(item.policyNo()).stream().anyMatch(c -> !c.id().equals(t.id()))) {
                rejected = Outcome.AMBIGUOUS_MATCH;          // 그 증권이 다른 확인서에 활성으로 붙어 있다
            }
        }
        Instant now = clock.instant();
        if (rejected != null) {
            store.insertUnmatched(ids.get(), item, rejected.name(), batch.source(), sourceRef, now);
            return new ItemResult(item.index(), rejected, false, Optional.ofNullable(target).map(c -> c.id().value()), Optional.empty(),
                    RetentionChange.NONE);
        }
        return link(tenant, actor, batch, item, sourceRef, target, now);
    }

    private ItemResult link(TenantId tenant, Actor actor, ContractLinkBatch batch, ContractLinkBatch.Item item, String sourceRef,
                            ContractLinkStore.Candidate target, Instant now) {
        Optional<ContractLinkStore.ActiveLink> active = store.activeLink(target.id());
        if (active.isPresent() && same(active.get(), item)) {
            return new ItemResult(item.index(), Outcome.NOOP, false, Optional.of(target.id().value()), Optional.of(active.get().linkId()),
                    RetentionChange.NONE);
        }
        UUID linkId = ids.get();
        active.ifPresent(a -> store.supersede(a.linkId(), linkId, now));
        store.insertLink(new ContractLinkStore.NewLink(linkId, target.id(), item.policyNo(), item.applicationNo(), item.contractDate(), item.insurerCode(),
                item.productKey(), batch.source(), sourceRef, now, actor.subject()));
        store.mirror(target.id(), item.policyNo(), item.contractDate());

        EffectiveRule pinned = rules.load(tenant, target.consultDate(), target.globalRule(), target.tenantRule().orElse(null));
        LocalDate before = target.retentionUntil().orElseThrow(() -> new IllegalStateException("sealed disclosure " + target.id() + " has no retention"));
        LocalDate after = RetentionAnchors.until(before, pinned.retentionAnchors(), Map.of(RetentionAnchor.CONTRACT_DATE, item.contractDate()),
                pinned.retentionPeriod());
        RetentionChange retention = after.isAfter(before) && store.extendRetention(target.id(), after) ? RetentionChange.EXTENDED
                : RetentionChange.NOT_EXTENDED;

        Outcome outcome = active.isPresent() ? Outcome.CORRECTED : Outcome.LINKED;
        ObjectNode detail = JSON.createObjectNode().put("outcome", outcome.name()).put("linkId", linkId.toString())
                .put("source", batch.source()).put("sourceRef", sourceRef)
                .put("policyNoSha256", Sha256.of(item.policyNo().getBytes(StandardCharsets.UTF_8)))
                .put("contractDateAfter", item.contractDate().toString())
                .put("retentionUntilBefore", before.toString()).put("retentionUntilAfter", (retention == RetentionChange.EXTENDED ? after : before).toString())
                .put("retention", retention.name()).put("ruleVersionId", pinned.globalRuleVersionId().value());
        if (active.isPresent()) {
            detail.put("previousLinkId", active.get().linkId().toString()).put("contractDateBefore", active.get().contractDate().toString());
        } else {
            detail.putNull("previousLinkId").putNull("contractDateBefore");
        }
        audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.CONTRACT_LINK_CHANGED, "DISCLOSURE", target.id().toString(), detail));
        outbox.append(EventType.PolicyLinked, target.id().toString(), now, OutboxPayloads.policyLinked(target.id().value(),
                target.disclosureNo().orElseThrow(), linkId, item.contractDate(), item.insurerCode(), active.map(ContractLinkStore.ActiveLink::linkId).orElse(null)));
        return new ItemResult(item.index(), outcome, false, Optional.of(target.id().value()), Optional.of(linkId), retention);
    }

    private static boolean same(ContractLinkStore.ActiveLink a, ContractLinkBatch.Item i) {
        return a.policyNo().equals(i.policyNo()) && a.applicationNo().equals(i.applicationNo()) && a.contractDate().equals(i.contractDate())
                && a.insurerCode().equals(i.insurerCode()) && a.productKey().equals(i.productKey());
    }

    /**
     * 미매칭 보고 행 정리(작업 {@code CONTRACT_LINK_UNMATCHED_PURGE}): 실행 시점(오늘 KST)의 ACTIVE 룰 {@code contractLink.unmatchedRetentionDays}가 지난
     * 행을 지운다. 룰 값이 null이면 아무것도 지우지 않는다(실값 미정 — §14 #16).
     */
    @UseCaseEntry(Action.CONTRACT_LINK_UNMATCHED_PURGE)
    public PurgeReport purgeUnmatched(Caller caller, int limit) {
        Objects.requireNonNull(caller, "caller");
        if (limit < 1 || limit > MAX_PURGE) {
            throw new IllegalArgumentException("limit must be 1.." + MAX_PURGE);
        }
        Instant now = clock.instant();
        return transactions.inTenant(caller.tenant(), () -> {
            Actor actor = authz.require(caller, Action.CONTRACT_LINK_UNMATCHED_PURGE, Target.none());
            EffectiveRule rule = rules.resolve(caller.tenant(), LocalDate.ofInstant(now, SEOUL));
            OptionalInt days = rule.contractLinkUnmatchedRetentionDays();
            if (days.isEmpty()) {
                return new PurgeReport(now, 0, days);
            }
            Instant before = now.minus(java.time.Duration.ofDays(days.getAsInt()));
            int purged = store.purgeUnmatched(before, limit);
            audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.CONTRACT_LINK_UNMATCHED_PURGE, "TENANT", caller.tenant().value(),
                    JSON.createObjectNode().put("receivedBefore", before.toString()).put("purged", purged).put("retentionDays", days.getAsInt())
                            .put("ruleVersionId", rule.globalRuleVersionId().value())));
            return new PurgeReport(before, purged, days);
        });
    }
}
