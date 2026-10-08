package com.ga.disclosure.workflow.retention;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.outbox.EventType;
import com.ga.disclosure.audit.outbox.OutboxPayloads;
import com.ga.disclosure.audit.outbox.OutboxPort;
import com.ga.disclosure.domain.enums.RetentionAnchor;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.sign.retention.RetentionDecision;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.disclosure.workflow.artifact.ObjectLockedException;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * 파기 배치(5 계획 §5.2·§8.5, CLAUDE.md 절대 규칙 2). 테넌트 하나, 판정 시각 {@code asOf}(기본 = 시계). 확인서마다 단계별로 멱등이다 — 어디서 멈춰도 다시
 * 돌리면 남은 단계부터 잇는다.
 * <ol start="0">
 *   <li>판정({@link RetentionDecision}): 고정 룰의 {@code retentionAnchors} + 판정 시점 룰의 {@code contractLinkWaitDays}. 객체 잠금이 하나라도 오늘
 *       이후까지면 {@code LOCK_NOT_EXPIRED}로 <b>아무것도 바꾸지 않는다</b>(키 파기는 되돌릴 수 없다).</li>
 *   <li>키가 살아 있으면 트랜잭션 하나: 감싼 키의 해시 감사 {@code DOCUMENT_KEY_SHREDDED} → 파기자 롤로 {@code ga_document_key_shred}.</li>
 *   <li>객체 키마다 모든 버전·삭제 마커를 버전 ID로 지운다. 저장소가 잠금·보류로 거부하면 멈춘다(③ 진입 금지, 다음 실행이 ②부터).</li>
 *   <li>모든 키의 버전·마커 0을 확인한 뒤 트랜잭션 하나: 지운 값의 표현(승인 Q3) 감사 {@code DISCLOSURE_DESTROYED} + 아웃박스
 *       {@code DisclosureDestroyed} → 파기자 롤로 {@code ga_disclosure_destroy}. 함수가 거부하면(그 사이 보류 등) 감사·아웃박스도 롤백된다.</li>
 * </ol>
 * 이어서 고객 파기(판정 시점 룰의 유예), 끝에 테넌트당 요약 감사 {@code DESTRUCTION_BATCH_RUN}. dry-run은 판정만 하고 쓰기 0(감사도 0)이다.
 */
public final class DestructionJob {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Destroyed(DisclosureId id, String disclosureNo, List<RetentionAnchor> anchorsWaived) {
    }

    public record Skipped(DisclosureId id, String reason, List<String> reasons) {
    }

    public record Failed(DisclosureId id, String stage, String code) {
    }

    public record Report(TenantId tenant, Instant asOf, boolean dryRun, String ruleVersionId, int candidates, List<Destroyed> destroyed,
                         List<Destroyed> wouldDestroy, List<Skipped> skipped, List<Failed> failed, int holdAfterShred, int customerCandidates,
                         List<CustomerRef> customersDestroyed, Map<String, Integer> customersSkipped) {
        public Report {
            destroyed = List.copyOf(destroyed);
            wouldDestroy = List.copyOf(wouldDestroy);
            skipped = List.copyOf(skipped);
            failed = List.copyOf(failed);
            customersDestroyed = List.copyOf(customersDestroyed);
            customersSkipped = Map.copyOf(customersSkipped);
        }

        public long anchorsWaived() {
            return destroyed.stream().filter(d -> !d.anchorsWaived().isEmpty()).count();
        }

        public Map<String, Integer> skippedByReason() {
            Map<String, Integer> out = new TreeMap<>();
            skipped.forEach(s -> out.merge(s.reason(), 1, Integer::sum));
            return out;
        }

        /** 보고서 JSON(스키마 {@code contracts/verify/v1/destruction-report.schema.json}). 개인정보 없음 — ID·번호·사유 코드·수. */
        public ObjectNode toJson() {
            ObjectNode n = JSON.createObjectNode();
            n.put("reportVersion", 1).put("tenantId", tenant.value()).put("asOf", asOf.toString()).put("dryRun", dryRun).put("ruleVersionId", ruleVersionId);
            n.put("candidates", candidates);
            destroyedArray(n.putArray("destroyed"), destroyed);
            destroyedArray(n.putArray("wouldDestroy"), wouldDestroy);
            ArrayNode s = n.putArray("skipped");
            skipped.forEach(x -> {
                ObjectNode o = s.addObject().put("disclosureId", x.id().toString()).put("reason", x.reason());
                ArrayNode all = o.putArray("reasons");
                x.reasons().forEach(all::add);
            });
            ObjectNode by = n.putObject("skippedByReason");
            skippedByReason().forEach(by::put);
            ArrayNode f = n.putArray("failed");
            failed.forEach(x -> f.addObject().put("disclosureId", x.id().toString()).put("stage", x.stage()).put("code", x.code()));
            n.put("anchorsWaived", anchorsWaived()).put("holdAfterShred", holdAfterShred);
            ObjectNode c = n.putObject("customers");
            c.put("candidates", customerCandidates);
            ArrayNode cd = c.putArray("destroyed");
            customersDestroyed.forEach(r -> cd.add(r.value()));
            ObjectNode cs = c.putObject("skippedByReason");
            new TreeMap<>(customersSkipped).forEach(cs::put);
            return n;
        }

        private static void destroyedArray(ArrayNode a, List<Destroyed> list) {
            list.forEach(d -> {
                ObjectNode o = a.addObject().put("disclosureId", d.id().toString()).put("disclosureNo", d.disclosureNo());
                ArrayNode w = o.putArray("anchorsWaived");
                d.anchorsWaived().forEach(x -> w.add(x.name()));
            });
        }
    }

    private final RetentionStore store;
    private final ErasureReader erasure;
    private final DestroyerPort destroyer;
    private final DocumentRecordStore records;
    private final ArtifactStore storage;
    private final RuleResolver rules;
    private final AuditPort audit;
    private final OutboxPort outbox;
    private final WorkflowTransactions transactions;
    private final Clock clock;
    private final AuthorizationPort authz;

    public DestructionJob(RetentionStore store, ErasureReader erasure, DestroyerPort destroyer, DocumentRecordStore records, ArtifactStore storage,
                          RuleResolver rules, AuditPort audit, OutboxPort outbox, WorkflowTransactions transactions, Clock clock,
                          AuthorizationPort authz) {
        this.store = Objects.requireNonNull(store, "store");
        this.erasure = Objects.requireNonNull(erasure, "erasure");
        this.destroyer = Objects.requireNonNull(destroyer, "destroyer");
        this.records = Objects.requireNonNull(records, "records");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authz = Objects.requireNonNull(authz, "authz");
    }

    /** 파기 배치는 사람 역할에 없다 — 스케줄러·운영자만(5 수용심사 결정 1). dry-run은 별도 행위다(준법은 보고서 열람만). */
    @UseCaseEntry({Action.DESTROY, Action.DESTROY_DRY_RUN})
    public Report run(Caller caller, Instant asOf, boolean dryRun, int limit) {
        Objects.requireNonNull(asOf, "asOf");
        TenantId tenant = caller.tenant();
        Actor actor = transactions.inTenant(tenant, () -> authz.require(caller, dryRun ? Action.DESTROY_DRY_RUN : Action.DESTROY, Target.none()));
        LocalDate today = LocalDate.ofInstant(asOf, SEOUL);
        EffectiveRule active = transactions.inTenant(tenant, () -> rules.resolve(tenant, today));
        List<DisclosureId> candidates = transactions.inTenant(tenant, () -> store.candidates(today, limit));
        List<Destroyed> destroyed = new ArrayList<>();
        List<Destroyed> would = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<Failed> failed = new ArrayList<>();
        int holdAfterShred = 0;
        for (DisclosureId id : candidates) {
            Optional<RetentionStore.Candidate> loaded = transactions.inTenant(tenant, () -> store.candidate(id, today));
            if (loaded.isEmpty()) {
                continue;
            }
            RetentionStore.Candidate c = loaded.get();
            EffectiveRule pinned = transactions.inTenant(tenant, () -> rules.load(tenant, c.consultDate(), c.ruleVersion(), c.tenantRuleVersionOrNull()));
            RetentionDecision.Verdict verdict = RetentionDecision.decide(subject(c), new RetentionDecision.Policy(pinned.retentionAnchors(),
                    active.contractLinkWaitDays()), asOf);
            if (verdict instanceof RetentionDecision.Verdict.Skip s) {
                skipped.add(new Skipped(id, s.reason().name(), s.all().stream().map(Enum::name).toList()));
                continue;
            }
            List<RetentionAnchor> waived = ((RetentionDecision.Verdict.Destroy) verdict).anchorsWaived();
            if (dryRun) {
                would.add(new Destroyed(id, c.disclosureNoOrNull(), waived));
                continue;
            }
            Outcome outcome = destroyOne(tenant, actor, c, waived, active, today);
            switch (outcome) {
                case Outcome.Done d -> destroyed.add(new Destroyed(id, c.disclosureNoOrNull(), waived));
                case Outcome.Locked l -> skipped.add(new Skipped(id, "LOCK_NOT_EXPIRED", List.of("LOCK_NOT_EXPIRED")));
                case Outcome.Fail f -> {
                    failed.add(new Failed(id, f.stage(), f.code()));
                    if (f.holdAfterShred()) {
                        holdAfterShred++;
                    }
                }
            }
        }
        List<CustomerRef> customersDestroyed = new ArrayList<>();
        Map<String, Integer> customersSkipped = new TreeMap<>();
        List<CustomerRef> customers = transactions.inTenant(tenant, () -> store.customerCandidates(limit));
        for (CustomerRef ref : customers) {
            Optional<RetentionStore.CustomerState> state = transactions.inTenant(tenant, () -> store.customer(ref));
            if (state.isEmpty()) {
                continue;
            }
            RetentionStore.CustomerState s = state.get();
            Optional<RetentionDecision.CustomerReason> reason = RetentionDecision.customer(s.destroyedAtOrNull() != null, s.disclosures(), s.live(), s.held(),
                    s.createdAt(), s.lastDestroyedAtOrNull(), active.customerGraceDaysAfterLastDestruction(), active.customerAbandonedDays(), asOf);
            if (reason.isPresent()) {
                customersSkipped.merge(reason.get().name(), 1, Integer::sum);
                continue;
            }
            if (dryRun) {
                customersSkipped.merge("DRY_RUN", 1, Integer::sum);
                continue;
            }
            try {
                transactions.inTenant(tenant, () -> {
                    Instant now = clock.instant();
                    ObjectNode detail = JSON.createObjectNode().put("asOf", asOf.toString()).put("ruleVersionId", active.globalRuleVersionId().value());
                    erasedArray(detail, erasure.customer(ref));
                    audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.CUSTOMER_REF_DESTROYED, "CUSTOMER_REF", ref.value(), detail));
                    destroyer.destroyCustomerRef(ref, now, actor.subject());
                    return null;
                });
                customersDestroyed.add(ref);
            } catch (DestructionRefusedException e) {
                customersSkipped.merge("REFUSED_" + e.sqlState(), 1, Integer::sum);
            }
        }
        Report report = new Report(tenant, asOf, dryRun, active.globalRuleVersionId().value(), candidates.size(), destroyed, would, skipped, failed,
                holdAfterShred, customers.size(), customersDestroyed, customersSkipped);
        if (!dryRun) {
            transactions.inTenant(tenant, () -> {
                ObjectNode detail = JSON.createObjectNode().put("asOf", asOf.toString()).put("ruleVersionId", report.ruleVersionId())
                        .put("candidates", report.candidates()).put("destroyed", destroyed.size()).put("failed", failed.size())
                        .put("anchorsWaived", report.anchorsWaived()).put("holdAfterShred", report.holdAfterShred())
                        .put("customersDestroyed", customersDestroyed.size());
                ObjectNode by = detail.putObject("skipped");
                report.skippedByReason().forEach(by::put);
                audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.DESTRUCTION_BATCH_RUN, "TENANT", tenant.value(),
                        detail));
                return null;
            });
        }
        return report;
    }

    private sealed interface Outcome {
        record Done() implements Outcome {
        }

        record Locked() implements Outcome {
        }

        record Fail(String stage, String code, boolean holdAfterShred) implements Outcome {
        }
    }

    private Outcome destroyOne(TenantId tenant, Actor actor, RetentionStore.Candidate c, List<RetentionAnchor> waived, EffectiveRule active, LocalDate today) {
        DisclosureId id = c.id();
        Set<String> keys = transactions.inTenant(tenant, () -> {
            Set<String> out = new LinkedHashSet<>();
            records.artifacts(id).forEach(a -> out.add(a.storageKey()));
            records.evidence(id).forEach(e -> out.add(e.storageKey()));
            return out;
        });
        // ① 문서 키
        if (c.keyLive()) {
            try {
                transactions.inTenant(tenant, () -> {
                    Instant now = clock.instant();
                    ObjectNode detail = JSON.createObjectNode().put("asOf", today.toString()).put("ruleVersionId", active.globalRuleVersionId().value());
                    erasedArray(detail, erasure.documentKey(id));
                    audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.DOCUMENT_KEY_SHREDDED, "DISCLOSURE", id.toString(), detail));
                    destroyer.shredDocumentKey(id, today, now, actor.subject());
                    return null;
                });
            } catch (DestructionRefusedException e) {
                return new Outcome.Fail("SHRED", e.sqlState(), false);
            }
        }
        // ② 객체(모든 버전·마커, 버전 ID로)
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (ObjectLockedException e) {
                return heldNow(tenant, c) ? new Outcome.Fail("DELETE_OBJECTS", "HOLD", true) : new Outcome.Locked();
            } catch (RuntimeException e) {
                return new Outcome.Fail("DELETE_OBJECTS", e.getClass().getSimpleName(), false);      // 다음 실행이 ②부터 잇는다
            }
        }
        for (String key : keys) {
            if (!storage.versionCount(key).isEmpty()) {
                return new Outcome.Fail("DELETE_OBJECTS", "OBJECTS_REMAIN", false);
            }
        }
        // ③ 묘비
        try {
            transactions.inTenant(tenant, () -> {
                Instant now = clock.instant();
                ObjectNode detail = JSON.createObjectNode().put("asOf", today.toString()).put("ruleVersionId", active.globalRuleVersionId().value())
                        .put("objectsDeleted", keys.size());
                ArrayNode w = detail.putArray("anchorsWaived");
                waived.forEach(a -> w.add(a.name()));
                erasedArray(detail, erasure.disclosure(id));
                audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.DISCLOSURE_DESTROYED, "DISCLOSURE", id.toString(), detail));
                outbox.append(EventType.DisclosureDestroyed, id.toString(), now, OutboxPayloads.disclosureDestroyed(id.value(), c.disclosureNoOrNull(), now));
                destroyer.destroyDisclosure(id, today, now, actor.subject());
                return null;
            });
        } catch (DestructionRefusedException e) {
            boolean held = heldNow(tenant, c);
            return new Outcome.Fail("DESTROY", held ? "HOLD" : e.sqlState(), held);
        }
        return new Outcome.Done();
    }

    private boolean heldNow(TenantId tenant, RetentionStore.Candidate c) {
        return transactions.inTenant(tenant, () -> store.candidate(c.id(), LocalDate.ofInstant(clock.instant(), SEOUL))
                .map(RetentionStore.Candidate::held).orElse(false));
    }

    private static RetentionDecision.Subject subject(RetentionStore.Candidate c) {
        Map<RetentionAnchor, LocalDate> dates = new EnumMap<>(RetentionAnchor.class);
        if (c.sealedAt() != null) {
            dates.put(RetentionAnchor.SEAL, LocalDate.ofInstant(c.sealedAt(), SEOUL));
        }
        if (c.completedAtOrNull() != null) {
            dates.put(RetentionAnchor.COMPLETION, LocalDate.ofInstant(c.completedAtOrNull(), SEOUL));
        }
        if (c.contractDateOrNull() != null) {
            dates.put(RetentionAnchor.CONTRACT_DATE, c.contractDateOrNull());
        }
        return new RetentionDecision.Subject(c.status(), c.disclosureNoOrNull() != null, c.retentionUntil(), c.completedAtOrNull(), dates,
                c.destroyedAtOrNull() != null, c.held(), c.locksExpired());
    }

    private static void erasedArray(ObjectNode detail, List<ErasureReader.Erased> erased) {
        ArrayNode a = detail.putArray("erased");
        erased.forEach(e -> a.addObject().put("table", e.table()).put("column", e.column()).put("row", e.row()).put("repr", e.repr())
                .put("value", e.value()));
    }
}
