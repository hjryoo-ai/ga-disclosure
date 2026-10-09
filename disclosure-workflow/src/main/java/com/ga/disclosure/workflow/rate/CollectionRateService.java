package com.ga.disclosure.workflow.rate;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.metric.CollectionRates;
import com.ga.disclosure.rules.resolve.CollectionRateFormula;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolutionException;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.RejectionCategory;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.ListGrant;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.workflow.disclosure.CommandRejectedException;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 징구율(6B 지시문 §4, 계획 §5·승인 §3·§5, 설계서 §6.8). <b>내부 지표 — 규제 정의 없음</b>(§14 #17): 응답·보고서·감사에 정의 표기와 산식 ID·룰 버전을
 * 함께 싣는다.
 * <ul>
 *   <li><b>스냅샷</b>({@code COLLECTION_RATE_SNAPSHOT}): 기준월(KST, 기본 전월 — 끝난 달만)을 그 달 마지막 날(KST)에 시행 중이던 GLOBAL 룰의 산식으로
 *       계산해 한 트랜잭션에 조직별 + 테넌트 전체 행을 넣는다(append-only). 같은 (달, 룰 버전)이 이미 있으면 새 행 없이 거부 {@value #SNAPSHOT_EXISTS}
 *       (감사 1행) — 정정은 룰 버전을 올리는 것(소급 배포가 그 달의 시행 버전을 바꾼다)이고, 그러면 새 {@code rule_version_id}로 새 행이 생긴다. 묘비
 *       제외는 계산 시점 기준 — 저장 뒤의 파기는 행을 바꾸지 않는다.</li>
 *   <li><b>조회</b>({@code COLLECTION_RATE_READ}): 준법은 테넌트 전체(테넌트 행 포함), 관리자는 조직 아래 행만. 달마다 기본은 그 달 마지막 날(KST)에
 *       시행 중이던 GLOBAL 룰 버전의 행(그 날 룰이 없으면 그 달은 비어 있다), {@code ruleVersionId}를 주면 그 버전의 행.</li>
 * </ul>
 */
public final class CollectionRateService {

    public static final String SNAPSHOT_EXISTS = CommandRejectedException.Code.SNAPSHOT_EXISTS.name();
    /** 한 번에 조회하는 달 수 상한. */
    public static final int MAX_MONTHS = 24;
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 스냅샷 결과(개인정보·번호 없음 — 해시와 수치뿐). */
    public record Report(YearMonth period, RuleVersionId ruleVersionId, CollectionRates.Result result, Instant computedAt, UUID jobId) {
        public Report {
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(ruleVersionId, "ruleVersionId");
            Objects.requireNonNull(result, "result");
            Objects.requireNonNull(computedAt, "computedAt");
            Objects.requireNonNull(jobId, "jobId");
        }
    }

    private final CollectionRateStore store;
    private final RuleResolver rules;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final AuthorizationPort authz;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public CollectionRateService(CollectionRateStore store, RuleResolver rules, AuditPort audit, WorkflowTransactions transactions, AuthorizationPort authz,
                                 Clock clock, Supplier<UUID> ids) {
        this.store = Objects.requireNonNull(store, "store");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    /** 실행 시각(KST)의 전월 — 작업의 기본 기준월. */
    public YearMonth previousMonth() {
        return YearMonth.from(LocalDate.ofInstant(clock.instant(), SEOUL)).minusMonths(1);
    }

    /** 끝난 달인가(KST) — 이번 달·미래 달은 스냅샷을 만들지 않는다(입력이 아직 들어온다). */
    public void requireFinished(YearMonth period) {
        Objects.requireNonNull(period, "period");
        if (!period.isBefore(YearMonth.from(LocalDate.ofInstant(clock.instant(), SEOUL)))) {
            throw new IllegalArgumentException("periodMonth must be a finished month (KST)");
        }
    }

    /**
     * 제출 전 검사(HTTP — 작업을 만들기 전에): 끝난 달인가(400), 같은 (달, 룰 버전)이 이미 있으면 감사 1행 뒤 {@value #SNAPSHOT_EXISTS}(409). 작업
     * 본체({@link #snapshot})가 같은 검사를 잠금 아래에서 다시 한다.
     */
    @UseCaseEntry(Action.COLLECTION_RATE_SNAPSHOT)
    public void admit(Caller caller, YearMonth period) {
        Objects.requireNonNull(caller, "caller");
        requireFinished(period);
        boolean exists = transactions.inTenant(caller.tenant(), () -> {
            Actor actor = authz.require(caller, Action.COLLECTION_RATE_SNAPSHOT, Target.none());
            EffectiveRule rule = ruleFor(caller.tenant(), period);
            if (store.exists(period.atDay(1), rule.globalRuleVersionId())) {
                auditExists(caller.tenant(), actor, period, rule, Optional.empty());
                return true;
            }
            return false;
        });
        if (exists) {
            throw exists();
        }
    }

    /** 작업 본체: 한 트랜잭션에 계산·저장·감사. 이미 있으면 감사 1행(별도 트랜잭션) 뒤 {@value #SNAPSHOT_EXISTS}. */
    @UseCaseEntry(Action.COLLECTION_RATE_SNAPSHOT)
    public Report snapshot(Caller caller, YearMonth period, UUID jobId) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(jobId, "jobId");
        requireFinished(period);
        TenantId tenant = caller.tenant();
        Optional<Report> done = transactions.inTenant(tenant, () -> {
            Actor actor = authz.require(caller, Action.COLLECTION_RATE_SNAPSHOT, Target.none());
            EffectiveRule rule = ruleFor(tenant, period);
            RuleVersionId version = rule.globalRuleVersionId();
            LocalDate month = period.atDay(1);
            if (store.exists(month, version)) {
                return Optional.<Report>empty();
            }
            CollectionRateFormula formula = rule.collectionRateFormula();
            LocalDate next = period.plusMonths(1).atDay(1);
            List<String> unmatched = formula == CollectionRateFormula.TARGET_INCLUDING_UNMATCHED ? store.unmatchedPolicyKeys(month, next) : List.of();
            CollectionRates.Result result = CollectionRates.compute(formula, period, store.linked(month, next), unmatched);
            Instant at = clock.instant();
            for (CollectionRates.Group g : result.groups()) {
                store.insert(new CollectionRateStore.Row(ids.get(), month, g.orgPath(), formula.name(), g.denominator(), g.numerator(), g.rateBp(), at, version,
                        g.inputsHash(), jobId));
            }
            CollectionRates.Group all = result.tenantWide();
            ObjectNode detail = detail(period, rule).put("jobId", jobId.toString()).put("rows", result.groups().size())
                    .put("denominator", all.denominator()).put("numerator", all.numerator()).put("inputsHash", all.inputsHash());
            if (all.rateBp().isPresent()) {
                detail.put("rateBp", all.rateBp().getAsInt());
            } else {
                detail.putNull("rateBp");
            }
            audit.append(new AuditEntry(at, actor.subject(), actor.role(), AuditAction.COLLECTION_RATE_SNAPSHOT, "TENANT", tenant.value(), detail));
            return Optional.of(new Report(period, version, result, at, jobId));
        });
        if (done.isPresent()) {
            return done.get();
        }
        transactions.inTenant(tenant, () -> {
            Actor actor = authz.require(caller, Action.COLLECTION_RATE_SNAPSHOT, Target.none());
            auditExists(tenant, actor, period, ruleFor(tenant, period), Optional.of(jobId));
            return null;
        });
        throw exists();
    }

    /** 조회: 범위로 걸러진 행, 달마다 기본 룰 버전(또는 주어진 버전)만. 달·조직 경로 순. */
    @UseCaseEntry(Action.COLLECTION_RATE_READ)
    public List<CollectionRateStore.Row> read(Caller caller, YearMonth from, YearMonth to, Optional<String> orgPath, Optional<RuleVersionId> ruleVersion) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(orgPath, "orgPath");
        Objects.requireNonNull(ruleVersion, "ruleVersion");
        if (to.isBefore(from) || from.plusMonths(MAX_MONTHS - 1).isBefore(to)) {
            throw new IllegalArgumentException("from <= to, at most " + MAX_MONTHS + " months");
        }
        TenantId tenant = caller.tenant();
        return transactions.inTenant(tenant, () -> {
            ListGrant grant = authz.requireList(caller, Action.COLLECTION_RATE_READ);
            Map<LocalDate, Optional<RuleVersionId>> versions = new HashMap<>();
            List<CollectionRateStore.Row> out = new ArrayList<>();
            for (CollectionRateStore.Row row : store.list(grant.scope(), from.atDay(1), to.atDay(1), orgPath)) {
                Optional<RuleVersionId> wanted = ruleVersion.isPresent() ? ruleVersion
                        : versions.computeIfAbsent(row.periodMonth(), m -> inForceAtMonthEnd(tenant, YearMonth.from(m)));
                if (wanted.isPresent() && wanted.get().equals(row.ruleVersionId())) {
                    out.add(row);
                }
            }
            return out;
        });
    }

    /** 그 달 마지막 날(KST)에 시행 중이던 룰 — 스냅샷이 쓰는 룰이자 조회의 기본 버전. */
    private EffectiveRule ruleFor(TenantId tenant, YearMonth period) {
        return rules.resolve(tenant, period.atEndOfMonth());
    }

    private Optional<RuleVersionId> inForceAtMonthEnd(TenantId tenant, YearMonth period) {
        try {
            return Optional.of(ruleFor(tenant, period).globalRuleVersionId());
        } catch (RuleResolutionException e) {
            return Optional.empty();                                        // 그 날 시행 룰이 없으면 기본 행도 없다
        }
    }

    private static ObjectNode detail(YearMonth period, EffectiveRule rule) {
        return JSON.createObjectNode().put("periodMonth", period.toString()).put("ruleVersionId", rule.globalRuleVersionId().value())
                .put("formula", rule.collectionRateFormula().name()).put("definition", CollectionRates.DEFINITION);
    }

    private void auditExists(TenantId tenant, Actor actor, YearMonth period, EffectiveRule rule, Optional<UUID> jobId) {
        ObjectNode detail = detail(period, rule).put("code", SNAPSHOT_EXISTS);
        jobId.ifPresent(id -> detail.put("jobId", id.toString()));
        audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.COLLECTION_RATE_SNAPSHOT_REJECTED, "TENANT", tenant.value(),
                detail));
    }

    private static CommandRejectedException exists() {
        return new CommandRejectedException(CommandRejectedException.Code.SNAPSHOT_EXISTS,
                "a collection-rate snapshot for this month and rule version already exists; recompute under a new rule version");
    }
}
