package com.ga.disclosure.workflow.kek;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.workflow.customer.KeyProviderPort;
import com.ga.disclosure.workflow.kek.KekRewrapStore.Wrapped;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 테넌트 KEK 등록과 재래핑(Phase 8, 8 계획 승인 Q2). 둘 다 운영자 CLI만(행위 {@code KEK_REGISTER}·{@code KEK_REWRAP} — 사람·서비스 역할 칸 없음).
 * <ul>
 *   <li>등록: 비밀 출처에 그 키가 있고 감싸기·풀기가 되는지 확인한 뒤 레지스트리에서 CURRENT를 바꾼다(이전 CURRENT는 RETIRED) + 감사 {@code KEK_REGISTERED}.
 *       키 바이트를 지우는 것은 등록과 무관한 별도 단계다 — 재래핑이 끝나기 전에 지우면 그 키로 감싼 것이 모두 풀리지 않는다.</li>
 *   <li>재래핑(작업 {@code KEK_REWRAP}): 문서 키·고객 데이터 키·작업 보고서 키 중 CURRENT가 아닌 KEK로 감싼 <b>살아 있는</b> 행(파기된 키는 대상이 아니다 —
 *       묘비를 되살리지 않는다)을 행마다 어댑터에서 다시 감싸고(DEK 평문은 어댑터 밖으로 나가지 않는다), 한 트랜잭션에서 DB 함수로 바꾼 뒤 행마다 감사
 *       {@code KEK_REWRAPPED}(이전·이후 KEK, 작업 ID). 기본 dry-run(세기만, 쓰기·행 감사 없음). 이미 CURRENT인 행은 대상이 아니므로 두 번째 실행은 0건이고,
 *       중간에 멈추면 다시 실행해 이어간다. 풀 수 없는 행은 {@code FAILED}로 보고하고 그대로 둔다(다음 행으로). 보존기한·잠금은 건드리지 않는다.</li>
 * </ul>
 */
public final class TenantKekService {

    static final int PAGE = 500;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String REGISTRY_TARGET = "TENANT_KEK";

    public enum Outcome { REWRAPPED, PENDING, SKIPPED, FAILED }

    public record Item(KekRewrapStore.Target target, String rowKey, String fromKekId, Outcome outcome) {
        public Item {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(rowKey, "rowKey");
            Objects.requireNonNull(fromKekId, "fromKekId");
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    public record Report(String toKekId, boolean apply, List<Item> items) {
        public Report {
            Objects.requireNonNull(toKekId, "toKekId");
            items = List.copyOf(items);
        }

        public long count(Outcome outcome) {
            return items.stream().filter(i -> i.outcome() == outcome).count();
        }

        /** 보고서 JSON({@code contracts/verify/v1/kek-rewrap-report.schema.json}). 키 바이트·DEK는 없다 — 행 식별자와 KEK ID뿐. */
        public ObjectNode toJson() {
            ObjectNode o = JSON.createObjectNode().put("reportVersion", 1).put("kind", "KEK_REWRAP").put("toKekId", toKekId).put("apply", apply);
            ArrayNode rows = o.putArray("items");
            items.forEach(i -> rows.addObject().put("target", i.target().name()).put("rowKey", i.rowKey()).put("fromKekId", i.fromKekId())
                    .put("outcome", i.outcome().name()));
            for (Outcome outcome : Outcome.values()) {
                o.put(outcome.name().toLowerCase(Locale.ROOT), count(outcome));
            }
            return o;
        }
    }

    private final TenantKekStore registry;
    private final KekRewrapStore rows;
    private final KeyProviderPort keys;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final AuthorizationPort authz;
    private final Clock clock;

    public TenantKekService(TenantKekStore registry, KekRewrapStore rows, KeyProviderPort keys, AuditPort audit, WorkflowTransactions transactions,
                            AuthorizationPort authz, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.rows = Objects.requireNonNull(rows, "rows");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 등록: 키 확인(감싸기·풀기) → CURRENT 교체 + 감사. 이미 CURRENT인 ID는 NOOP(false — 감사 없음, 스크립트 재실행). 물러난 ID의 재등록은 DB가
     * 거부한다(기본 키). 반환 = 레지스트리가 바뀌었는가.
     */
    @UseCaseEntry(Action.KEK_REGISTER)
    public boolean register(Caller caller, String kekId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(kekId, "kekId");
        TenantId tenant = caller.tenant();
        Actor actor = transactions.inTenant(tenant, () -> authz.require(caller, Action.KEK_REGISTER, Target.none()));
        probe(tenant, kekId);
        return transactions.inTenant(tenant, () -> {
            Optional<String> previous = registry.current();
            if (previous.filter(kekId::equals).isPresent()) {
                return false;
            }
            registry.register(kekId, clock.instant(), actor.subject());
            ObjectNode detail = JSON.createObjectNode();
            previous.ifPresentOrElse(p -> detail.put("retired", p), () -> detail.putNull("retired"));
            audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.KEK_REGISTERED, REGISTRY_TARGET, kekId, detail));
            return true;
        });
    }

    @UseCaseEntry(Action.KEK_REWRAP)
    public Report rewrap(Caller caller, boolean apply, UUID jobId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(jobId, "jobId");
        TenantId tenant = caller.tenant();
        Plan plan = transactions.inTenant(tenant, () -> new Plan(authz.require(caller, Action.KEK_REWRAP, Target.none()),
                registry.current().orElseThrow(() -> new IllegalStateException("tenant " + tenant + " has no current KEK"))));
        List<Item> items = new ArrayList<>();
        for (KekRewrapStore.Target target : KekRewrapStore.Target.values()) {
            Optional<String> after = Optional.empty();
            while (true) {
                Optional<String> from = after;
                List<Wrapped> page = transactions.inTenant(tenant, () -> rows.notUnder(target, plan.to(), from, PAGE));
                for (Wrapped w : page) {
                    items.add(new Item(target, w.rowKey(), w.kekId(), apply ? move(tenant, plan, w, jobId) : Outcome.PENDING));
                }
                if (page.size() < PAGE) {
                    break;
                }
                after = Optional.of(page.getLast().rowKey());
            }
        }
        return new Report(plan.to(), apply, items);
    }

    private record Plan(Actor actor, String to) {
    }

    /** 한 행: 어댑터에서 다시 감싸고(풀 수 없으면 FAILED, 그대로 둔다) 한 트랜잭션에서 DB 함수 + 감사. 그 사이 옮겨졌거나 파기됐으면 SKIPPED. */
    private Outcome move(TenantId tenant, Plan plan, Wrapped w, UUID jobId) {
        byte[] fresh;
        try {
            fresh = keys.rewrap(tenant, w.keyId(), w.kekId(), plan.to(), w.wrapped());
        } catch (RuntimeException e) {
            return Outcome.FAILED;
        }
        boolean moved = transactions.inTenant(tenant, () -> {
            if (!rows.rewrap(w.target(), w.rowKey(), w.kekId(), plan.to(), fresh)) {
                return false;
            }
            audit.append(new AuditEntry(clock.instant(), plan.actor().subject(), plan.actor().role(), AuditAction.KEK_REWRAPPED, auditTarget(w.target()),
                    w.rowKey(), JSON.createObjectNode().put("fromKekId", w.kekId()).put("toKekId", plan.to()).put("jobId", jobId.toString())));
            return true;
        });
        return moved ? Outcome.REWRAPPED : Outcome.SKIPPED;
    }

    private static String auditTarget(KekRewrapStore.Target target) {
        return switch (target) {
            case DOCUMENT_KEY -> "DOCUMENT_KEY";
            case CUSTOMER_DATA_KEY -> "CUSTOMER_DATA_KEY";
            case JOB_REPORT -> "JOB";
        };
    }

    private void probe(TenantId tenant, String kekId) {
        byte[] dek = new byte[32];
        RANDOM.nextBytes(dek);
        try {
            byte[] back = keys.unwrap(tenant, "KEK-PROBE", kekId, keys.wrap(tenant, "KEK-PROBE", kekId, dek));
            boolean same = MessageDigest.isEqual(dek, back);
            Arrays.fill(back, (byte) 0);
            if (!same) {
                throw new IllegalStateException("KEK " + kekId + " does not round-trip");
            }
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }
}
