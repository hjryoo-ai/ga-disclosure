package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.enums.RetentionAnchor;
import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.RetentionPeriod;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.version.RuleVersion;
import com.ga.disclosure.rules.version.RuleVersionPort;
import com.ga.disclosure.sign.retention.RetentionAnchors;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.disclosure.workflow.artifact.LockedObject;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 보존 재계산(6B 지시문 §7, 계획 §8, 설계서 §9 — §14 #14 "규제 변경 시 기존 문서의 보존기한 재계산"). 행위 {@code RETENTION_RECOMPUTE}(준법·운영자 —
 * 스케줄러 없음: 규제 변경 대응은 사람이 결정한다).
 * <ul>
 *   <li>룰: 이 테넌트에 배포된 <b>GLOBAL</b> 버전이고 상태가 <b>ACTIVE</b>여야 한다(아니면 {@code RULE_VERSION_NOT_USABLE} 422 — APPROVED·DRAFT·RETIRED·사규는
 *       쓰지 않는다). APPROVED는 아직 시행되지 않았고 시행 전에 다른 초안으로 대체될 수 있다 — "시행되지 않은 규칙을 적용했다"는 감사 행을 남기지 않는다(9단계
 *       회신). 보존기간·앵커는 그 버전의 것이다.</li>
 *   <li>대상: 봉인 이후·미파기 확인서(파기된 것은 수만 보고). 후보 = Phase 4 산식({@link RetentionAnchors})에 그 확인서의 앵커 날짜(봉인일·완료일 KST·계약일)와
 *       지정 버전의 기간 — 지금 기한과 무관하게 계산하므로 짧을 수 있다(보고서에 그대로).</li>
 *   <li>결과: 후보가 지금보다 길 때만 {@code EXTENDED}, 같거나 짧으면 {@code UNCHANGED}. <b>단축을 쓰는 코드가 없다</b> — 쓰기는 "더 길 때만·미파기일 때만"
 *       UPDATE 하나({@link RetentionRecomputeStore#extendRetention})이고 DB GD094가 한 번 더 막는다.</li>
 *   <li>기본은 dry-run: 쓰기·감사 없음(작업 행의 감사만). 적용({@code apply})이면 확인서마다 한 트랜잭션에서 연장 + 감사 {@code RETENTION_RECOMPUTED}(이전·이후·
 *       룰 버전·작업 ID), 커밋 뒤 그 확인서의 산출물·서명 증거 잠금을 늘어난 기한으로 다시 건다(Phase 4 {@link RetentionLocks} 경로 — 실패는 재적용 대상으로
 *       남고 {@code RECONCILE}이 잡는다).</li>
 *   <li>재실행은 이미 연장된 건을 {@code UNCHANGED}로 본다(멱등).</li>
 * </ul>
 */
public final class RetentionRecomputeService {

    static final int PAGE = 500;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public enum Outcome { EXTENDED, UNCHANGED }

    /** 확인서 한 건: 이전 기한, 지정 버전의 후보(짧을 수 있다), 결과. */
    public record Item(DisclosureId id, String disclosureNo, LocalDate before, LocalDate candidate, Outcome outcome) {
        public Item {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(disclosureNo, "disclosureNo");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(candidate, "candidate");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public record Report(RuleVersionId ruleVersionId, RetentionPeriod period, boolean apply, List<Item> items, int destroyedExcluded, int relockPending) {
        public Report {
            Objects.requireNonNull(ruleVersionId, "ruleVersionId");
            Objects.requireNonNull(period, "period");
            items = List.copyOf(items);
        }

        public long count(Outcome outcome) {
            return items.stream().filter(i -> i.outcome() == outcome).count();
        }

        /** 보고서 JSON({@code contracts/verify/v1/retention-recompute-report.schema.json}). */
        public tools.jackson.databind.node.ObjectNode toJson() {
            tools.jackson.databind.node.ObjectNode o = JSON.createObjectNode().put("reportVersion", 1).put("kind", "RETENTION_RECOMPUTE")
                    .put("ruleVersionId", ruleVersionId.value()).put("retentionYears", period.years()).put("retentionDays", period.days()).put("apply", apply);
            tools.jackson.databind.node.ArrayNode rows = o.putArray("items");
            items.forEach(i -> rows.addObject().put("disclosureId", i.id().toString()).put("disclosureNo", i.disclosureNo())
                    .put("before", i.before().toString()).put("candidate", i.candidate().toString()).put("outcome", i.outcome().name()));
            return o.put("extended", count(Outcome.EXTENDED)).put("unchanged", count(Outcome.UNCHANGED)).put("destroyedExcluded", destroyedExcluded)
                    .put("relockPending", relockPending);
        }
    }

    private final RetentionRecomputeStore store;
    private final RuleVersionPort versions;
    private final DocumentRecordStore records;
    private final RetentionLocks locks;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final AuthorizationPort authz;
    private final Clock clock;

    public RetentionRecomputeService(RetentionRecomputeStore store, RuleVersionPort versions, DocumentRecordStore records, ArtifactStore storage, AuditPort audit,
                                     WorkflowTransactions transactions, AuthorizationPort authz, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.records = Objects.requireNonNull(records, "records");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.locks = new RetentionLocks(records, Objects.requireNonNull(storage, "storage"), audit, transactions, clock);
    }

    /** 제출 전 검사(HTTP — 작업을 만들기 전에): 인가, 룰 버전이 쓸 수 있는가({@code RULE_VERSION_NOT_USABLE} 422). 본체가 같은 검사를 다시 한다. */
    @UseCaseEntry(Action.RETENTION_RECOMPUTE)
    public void admit(Caller caller, RuleVersionId ruleVersion) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(ruleVersion, "ruleVersion");
        transactions.inTenant(caller.tenant(), () -> {
            authz.require(caller, Action.RETENTION_RECOMPUTE, Target.none());
            return usable(caller.tenant(), ruleVersion);
        });
    }

    @UseCaseEntry(Action.RETENTION_RECOMPUTE)
    public Report run(Caller caller, RuleVersionId ruleVersion, boolean apply, UUID jobId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(ruleVersion, "ruleVersion");
        Objects.requireNonNull(jobId, "jobId");
        TenantId tenant = caller.tenant();
        record Plan(Actor actor, EffectiveRule rule, int destroyed) {
        }
        Plan plan = transactions.inTenant(tenant, () -> new Plan(authz.require(caller, Action.RETENTION_RECOMPUTE, Target.none()), usable(tenant, ruleVersion),
                store.destroyed()));
        RetentionPeriod period = plan.rule().retentionPeriod();
        List<Item> items = new ArrayList<>();
        int relockPending = 0;
        Optional<DisclosureId> after = Optional.empty();
        while (true) {
            Optional<DisclosureId> from = after;
            List<RetentionRecomputeStore.Target> page = transactions.inTenant(tenant, () -> store.live(from, PAGE));
            for (RetentionRecomputeStore.Target t : page) {
                LocalDate candidate = RetentionAnchors.until(null, plan.rule().retentionAnchors(), dates(t), period);
                boolean longer = candidate.isAfter(t.retentionUntil());
                boolean extended = longer && apply && transactions.inTenant(tenant, () -> {
                    if (!store.extendRetention(t.id(), candidate)) {
                        return false;                                         // 그 사이 더 늘었거나 파기됐다
                    }
                    audit.append(new AuditEntry(clock.instant(), plan.actor().subject(), plan.actor().role(), AuditAction.RETENTION_RECOMPUTED, "DISCLOSURE",
                            t.id().toString(), JSON.createObjectNode().put("before", t.retentionUntil().toString()).put("after", candidate.toString())
                                    .put("ruleVersionId", ruleVersion.value()).put("jobId", jobId.toString())));
                    return true;
                });
                if (extended && locks.apply(tenant, plan.actor(), allLocked(tenant, t.id()), candidate)) {
                    relockPending++;
                }
                // dry-run은 "적용했다면" 연장될 건을 EXTENDED로 보고한다 — 쓰지 않는다
                Outcome outcome = (apply ? extended : longer) ? Outcome.EXTENDED : Outcome.UNCHANGED;
                items.add(new Item(t.id(), t.disclosureNo(), t.retentionUntil(), candidate, outcome));
            }
            if (page.size() < PAGE) {
                break;
            }
            after = Optional.of(page.getLast().id());
        }
        return new Report(ruleVersion, period, apply, items, plan.destroyed(), relockPending);
    }

    /** 지정 버전: 이 테넌트의 GLOBAL·ACTIVE. 그 버전 본문만으로 유효 룰을 만든다(사규 덮어쓰기 없음 — 규제 기간의 변경). */
    private EffectiveRule usable(TenantId tenant, RuleVersionId id) {
        RuleVersion v = versions.findById(tenant, id).filter(r -> r.scope() == RuleScope.GLOBAL && r.status() == RuleStatus.ACTIVE)
                .orElseThrow(() -> new CommandRejectedException(CommandRejectedException.Code.RULE_VERSION_NOT_USABLE,
                        "the rule version is not a deployed ACTIVE GLOBAL version of this tenant"));
        return RuleResolver.merge(v.applyFrom(), v, null);
    }

    private static Map<RetentionAnchor, LocalDate> dates(RetentionRecomputeStore.Target t) {
        Map<RetentionAnchor, LocalDate> dates = new EnumMap<>(RetentionAnchor.class);
        dates.put(RetentionAnchor.SEAL, LocalDate.ofInstant(t.sealedAt(), SealService.SEOUL));
        t.completedAt().ifPresent(at -> dates.put(RetentionAnchor.COMPLETION, LocalDate.ofInstant(at, SealService.SEOUL)));
        t.contractDate().ifPresent(d -> dates.put(RetentionAnchor.CONTRACT_DATE, d));
        return dates;
    }

    /** 다시 걸 객체: 그 확인서의 산출물·서명 증거 전부(완료 때와 같다). */
    private List<LockedObject> allLocked(TenantId tenant, DisclosureId id) {
        return transactions.inTenant(tenant, () -> {
            List<LockedObject> all = new ArrayList<>(records.artifacts(id));
            all.addAll(records.evidence(id));
            return List.copyOf(new LinkedHashSet<>(all));
        });
    }
}
