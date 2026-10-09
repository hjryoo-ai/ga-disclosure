package com.ga.disclosure.workflow.job;

import com.ga.disclosure.audit.verify.VerifyReport;
import com.ga.disclosure.workflow.anchor.AnchorJob;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.disclosure.ExpireService;
import com.ga.disclosure.workflow.contract.ContractLinkBatch;
import com.ga.disclosure.workflow.contract.ContractLinkService;
import com.ga.disclosure.workflow.disclosure.NotificationDispatcher;
import com.ga.disclosure.workflow.flag.FlagCommandService;
import com.ga.disclosure.workflow.idempotency.IdempotencyPurge;
import com.ga.disclosure.workflow.rate.CollectionRateService;
import com.ga.disclosure.rules.metric.CollectionRates;
import com.ga.disclosure.workflow.retention.DestructionJob;
import com.ga.disclosure.workflow.verify.TenantVerifier;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 4·5 유스케이스를 작업 본체로 감싼다(6A 계획 §6.2) — CLI와 HTTP가 같은 본체·같은 보고서 형식을 쓴다. 보고서는 JCS 바이트이고 그 테넌트의 것만 싣는다.
 * 매개변수(작업 행 {@code params})도 여기서 읽는다: 모르는 키는 거부(400).
 */
public final class StandardJobs {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final int DEFAULT_EXPIRE_LIMIT = 500;
    public static final int DEFAULT_RECONCILE_LIMIT = 500;
    public static final int DEFAULT_DESTROY_LIMIT = 100;
    public static final int DEFAULT_NOTIFY_LIMIT = 100;
    public static final int DEFAULT_PURGE_LIMIT = 10_000;
    public static final int DEFAULT_SLA_SWEEP_LIMIT = 500;
    public static final int DEFAULT_ABANDON_LIMIT = 200;

    private StandardJobs() {
    }

    /** 단일 테넌트 본체: 호출자 하나로 부르고 보고서를 만든다. */
    private static <R> JobWork<R> single(java.util.function.Function<Caller, R> run, java.util.function.Function<R, byte[]> report) {
        return new JobWork<>() {
            @Override
            public R run(List<Caller> acquired) {
                if (acquired.size() != 1) {
                    throw new IllegalArgumentException("a single-tenant job runs with exactly one caller");
                }
                return run.apply(acquired.getFirst());
            }

            @Override
            public byte[] report(R result, TenantId tenant) {
                return report.apply(result);
            }
        };
    }

    // ------------------------------------------------------------------ 본체

    public static JobWork<ExpireService.Report> expire(ExpireService service, Instant asOf, int limit) {
        return single(c -> service.run(c, asOf, limit), r -> {
            ObjectNode o = JSON.createObjectNode().put("kind", JobKind.EXPIRE.name()).put("asOf", asOf.toString()).put("stillOpen", r.stillOpen())
                    .put("sessionsExpired", r.sessionsExpired());
            ArrayNode ids = o.putArray("expired");
            r.expired().forEach(id -> ids.add(id.toString()));
            return Canonicalizer.canonicalize(o);
        });
    }

    public static JobWork<ArtifactService.ReconcileReport> reconcile(ArtifactService service, int limit) {
        return single(c -> service.reconcile(c, limit), r -> Canonicalizer.canonicalize(JSON.createObjectNode().put("kind", JobKind.RECONCILE.name())
                .put("applied", r.applied()).put("failed", r.failed())));
    }

    /** 파기·dry-run: 보고서는 계약 스키마({@code destruction-report.schema.json}) 그대로. */
    public static JobWork<DestructionJob.Report> destroy(DestructionJob job, Instant asOf, boolean dryRun, int limit) {
        return single(c -> job.run(c, asOf, dryRun, limit), r -> Canonicalizer.canonicalize(r.toJson()));
    }

    /** 서명 링크 통지(6A 계획 §7.2): 보고서는 통지 ID 목록(번호·토큰 없음). */
    public static JobWork<NotificationDispatcher.Report> notify(NotificationDispatcher dispatcher, int limit) {
        return single(c -> dispatcher.run(c, limit), r -> Canonicalizer.canonicalize(r.toJson().put("kind", JobKind.NOTIFY.name())));
    }

    /** 만료 Idempotency-Key 정리(승인 Q12): 보고서는 지운 건수만(키·주체 없음). */
    public static JobWork<IdempotencyPurge.Report> idempotencyPurge(IdempotencyPurge purge, int limit) {
        return single(c -> purge.run(c, limit), r -> Canonicalizer.canonicalize(JSON.createObjectNode().put("kind", JobKind.IDEMPOTENCY_PURGE.name())
                .put("asOf", r.asOf().toString()).put("purged", r.purged())));
    }

    /** 계약 연결 배치(6B 계획 §4): 보고서는 항목별 결과·확인서·링크 ID(증권·청약 번호 없음). */
    public static JobWork<ContractLinkService.Report> contractLinkImport(ContractLinkService links, ContractLinkBatch batch) {
        return single(c -> links.importBatch(c, batch), r -> Canonicalizer.canonicalize(r.toJson()));
    }

    /** 작업 행에 남길 배치 요약(번호 없음): 출처·배치 ID·항목 수·입력 해시. */
    public static ObjectNode contractLinkSummary(ContractLinkBatch batch) {
        return JSON.createObjectNode().put("source", batch.source()).put("batchId", batch.batchId()).put("items", batch.items().size())
                .put("sha256", batch.sha256());
    }

    /** 미매칭 보고 행 정리: 보고서는 기준 시각·지운 수·룰 일수. */
    public static JobWork<ContractLinkService.PurgeReport> contractLinkUnmatchedPurge(ContractLinkService links, int limit) {
        return single(c -> links.purgeUnmatched(c, limit), r -> {
            ObjectNode o = JSON.createObjectNode().put("kind", JobKind.CONTRACT_LINK_UNMATCHED_PURGE.name()).put("receivedBefore", r.receivedBefore().toString())
                    .put("purged", r.purged());
            if (r.retentionDays().isPresent()) {
                o.put("retentionDays", r.retentionDays().getAsInt());
            } else {
                o.putNull("retentionDays");
            }
            return Canonicalizer.canonicalize(o);
        });
    }

    /** 방치 초안 폐기(6B 지시문 §6): 보고서는 룰 일수·기준 시각·폐기한 확인서 ID·건너뛴 수(번호·개인정보 없음 — 봉인 전이라 번호도 없다). */
    public static JobWork<com.ga.disclosure.workflow.disclosure.DraftAbandonService.BatchReport> abandonDrafts(
            com.ga.disclosure.workflow.disclosure.DraftAbandonService drafts, int limit) {
        return single(c -> drafts.abandonIdle(c, limit), r -> {
            ObjectNode o = JSON.createObjectNode().put("kind", JobKind.ABANDON_DRAFTS.name());
            if (r.abandonAfterDays().isPresent()) {
                o.put("abandonAfterDays", r.abandonAfterDays().getAsInt()).put("changedBefore", r.changedBefore().orElseThrow().toString());
            } else {
                o.putNull("abandonAfterDays").putNull("changedBefore");
            }
            ArrayNode ids = o.putArray("abandoned");
            r.abandoned().forEach(id -> ids.add(id.toString()));
            o.put("skipped", r.skipped()).put("held", r.held());
            return Canonicalizer.canonicalize(o);
        });
    }

    /**
     * 징구율 스냅샷(6B 계획 §5): 본체는 작업 ID를 행에 남긴다. HTTP 제출 전 같은 (달, 룰 버전) 검사(409 {@code SNAPSHOT_EXISTS}). 보고서는 기준월·룰 버전·
     * 산식·정의 표기와 묶음별 수치·입력 해시(번호·개인정보 없음).
     */
    public static JobWork<CollectionRateService.Report> collectionRateSnapshot(CollectionRateService rates, YearMonth period) {
        return new JobWork<>() {
            @Override
            public CollectionRateService.Report run(List<Caller> acquired) {
                throw new IllegalStateException("the collection-rate snapshot records its job id — run(acquired, jobIds)");
            }

            @Override
            public CollectionRateService.Report run(List<Caller> acquired, List<UUID> jobIds) {
                if (acquired.size() != 1 || jobIds.size() != 1) {
                    throw new IllegalArgumentException("a single-tenant job runs with exactly one caller");
                }
                return rates.snapshot(acquired.getFirst(), period, jobIds.getFirst());
            }

            @Override
            public void admit(Caller caller) {
                rates.admit(caller, period);
            }

            @Override
            public byte[] report(CollectionRateService.Report r, TenantId tenant) {
                ObjectNode o = JSON.createObjectNode().put("kind", JobKind.COLLECTION_RATE_SNAPSHOT.name()).put("periodMonth", r.period().toString())
                        .put("ruleVersionId", r.ruleVersionId().value()).put("formula", r.result().formula().name())
                        .put("definition", CollectionRates.DEFINITION).put("computedAt", r.computedAt().toString());
                ArrayNode groups = o.putArray("groups");
                r.result().groups().forEach(g -> {
                    ObjectNode row = groups.addObject().put("orgPath", g.orgPath()).put("denominator", g.denominator()).put("numerator", g.numerator())
                            .put("inputsHash", g.inputsHash());
                    if (g.rateBp().isPresent()) {
                        row.put("rateBp", g.rateBp().getAsInt());
                    } else {
                        row.putNull("rateBp");
                    }
                });
                return Canonicalizer.canonicalize(o);
            }
        };
    }

    /**
     * 보존 재계산(6B 계획 §8): 본체는 작업 ID를 감사에 남긴다. HTTP 제출 전 룰 버전 검사(422 {@code RULE_VERSION_NOT_USABLE}). 보고서는 계약 스키마
     * ({@code retention-recompute-report.schema.json}) 그대로.
     */
    public static JobWork<com.ga.disclosure.workflow.disclosure.RetentionRecomputeService.Report> retentionRecompute(
            com.ga.disclosure.workflow.disclosure.RetentionRecomputeService service, com.ga.disclosure.domain.vo.RuleVersionId ruleVersion, boolean apply) {
        return new JobWork<>() {
            @Override
            public com.ga.disclosure.workflow.disclosure.RetentionRecomputeService.Report run(List<Caller> acquired) {
                throw new IllegalStateException("the retention recompute records its job id — run(acquired, jobIds)");
            }

            @Override
            public com.ga.disclosure.workflow.disclosure.RetentionRecomputeService.Report run(List<Caller> acquired, List<UUID> jobIds) {
                if (acquired.size() != 1 || jobIds.size() != 1) {
                    throw new IllegalArgumentException("a single-tenant job runs with exactly one caller");
                }
                return service.run(acquired.getFirst(), ruleVersion, apply, jobIds.getFirst());
            }

            @Override
            public void admit(Caller caller) {
                service.admit(caller, ruleVersion);
            }

            @Override
            public byte[] report(com.ga.disclosure.workflow.disclosure.RetentionRecomputeService.Report r, TenantId tenant) {
                return Canonicalizer.canonicalize(r.toJson());
            }
        };
    }

    /** 룰 버전 매개변수(필수, 룰 버전 ID 형식). */
    public static com.ga.disclosure.domain.vo.RuleVersionId ruleVersion(ObjectNode params, String name) {
        JsonNode v = params.get(name);
        if (v == null || !v.isString() || !v.asString().matches("[A-Z0-9][A-Z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("job parameter " + name + " must be a rule version id");
        }
        return com.ga.disclosure.domain.vo.RuleVersionId.of(v.asString());
    }

    /** 불리언 매개변수(없으면 기본값). */
    public static boolean bool(ObjectNode params, String name, boolean dflt) {
        JsonNode v = params.get(name);
        if (v == null || v.isNull()) {
            return dflt;
        }
        if (!v.isBoolean()) {
            throw new IllegalArgumentException("job parameter " + name + " must be true or false");
        }
        return v.asBoolean();
    }

    /** 기준월 매개변수 {@code periodMonth}(YYYY-MM) — 없으면 빈 값(본체가 전월로). */
    public static Optional<YearMonth> yearMonth(ObjectNode params, String name) {
        JsonNode v = params.get(name);
        if (v == null || v.isNull()) {
            return Optional.empty();
        }
        if (!v.isString() || !v.asString().matches("\\d{4}-\\d{2}")) {
            throw new IllegalArgumentException("job parameter " + name + " must be YYYY-MM");
        }
        try {
            return Optional.of(YearMonth.parse(v.asString()));
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("job parameter " + name + " must be YYYY-MM");
        }
    }

    /** SLA 경과 표시(6B 계획 §7): 보고서는 표시한 플래그 ID·유형·기한(개인정보 없음). */
    public static JobWork<FlagCommandService.SweepReport> flagSlaSweep(FlagCommandService flags, int limit) {
        return single(c -> flags.sweepSla(c, limit), r -> {
            ObjectNode o = JSON.createObjectNode().put("kind", JobKind.FLAG_SLA_SWEEP.name());
            ArrayNode marked = o.putArray("breached");
            r.breached().forEach(b -> marked.addObject().put("flagId", b.flagId().toString()).put("type", b.type()).put("dueAt", b.dueAt().toString()));
            return Canonicalizer.canonicalize(o);
        });
    }

    /** 검증: 보고서는 계약 스키마({@code verify-report.schema.json}) 그대로. */
    public static JobWork<VerifyReport> verifyTenant(TenantVerifier verifier, byte[] trustPemOrNull) {
        byte[] trust = trustPemOrNull == null ? null : trustPemOrNull.clone();
        return single(c -> verifier.run(c, trust), VerifyReport::canonical);
    }

    /**
     * 앵커(CLI 전용, 승인 Q7): 잡은 테넌트들로 한 번(한 트리). 테넌트 보고서에는 그 테넌트의 생성·변경 없음·재시도·실패와 배치 전체 실패(테넌트 없음), 배치
     * 루트만 싣는다(다른 테넌트 ID는 싣지 않는다).
     */
    public static JobWork<AnchorJob.Report> anchor(AnchorJob job, Optional<LocalDate> date, String operator) {
        return new JobWork<>() {
            @Override
            public AnchorJob.Report run(List<Caller> acquired) {
                List<TenantId> tenants = acquired.stream().map(Caller::tenant).toList();
                return date.map(d -> job.run(tenants, d, operator)).orElseGet(() -> job.run(tenants, operator));
            }

            @Override
            public byte[] report(AnchorJob.Report r, TenantId tenant) {
                ObjectNode o = JSON.createObjectNode().put("kind", JobKind.ANCHOR.name()).put("date", r.date().toString())
                        .put("created", r.created().contains(tenant)).put("unchanged", r.unchanged().contains(tenant))
                        .put("retries", r.retries().getOrDefault(tenant, 0)).put("receiptsInRun", r.receipts());
                ArrayNode batches = o.putArray("batches");
                r.batches().forEach(b -> batches.addObject().put("anchorDate", b.anchorDate().toString()).put("batchId", b.batchId().toString())
                        .put("root", b.root()).put("depth", b.depth()).put("second", b.second()));
                ArrayNode failures = o.putArray("failures");
                r.failures().stream().filter(f -> f.tenant() == null || f.tenant().equals(tenant)).forEach(f -> failures.addObject()
                        .put("stage", f.stage()).put("anchorDate", f.anchorDate().toString()).put("code", f.code()).put("batchWide", f.tenant() == null));
                return Canonicalizer.canonicalize(o);
            }
        };
    }

    // ------------------------------------------------------------------ 매개변수

    /** 허용 키만 있는가(모르는 키는 400). */
    public static ObjectNode only(ObjectNode params, Set<String> allowed) {
        for (String name : params.propertyNames()) {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException("unknown job parameter: " + name);
            }
        }
        return params;
    }

    public static Optional<Instant> instant(ObjectNode params, String name) {
        JsonNode v = params.get(name);
        if (v == null || v.isNull()) {
            return Optional.empty();
        }
        if (!v.isString()) {
            throw new IllegalArgumentException("job parameter " + name + " must be an ISO instant");
        }
        try {
            return Optional.of(Instant.parse(v.asString()));
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("job parameter " + name + " must be an ISO instant");
        }
    }

    public static int limit(ObjectNode params, int dflt, int max) {
        JsonNode v = params.get("limit");
        if (v == null || v.isNull()) {
            return dflt;
        }
        if (!v.isIntegralNumber() || !v.canConvertToInt() || v.asInt() < 1 || v.asInt() > max) {
            throw new IllegalArgumentException("job parameter limit must be 1.." + max);
        }
        return v.asInt();
    }
}
