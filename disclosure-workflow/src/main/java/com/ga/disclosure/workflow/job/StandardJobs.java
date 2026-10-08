package com.ga.disclosure.workflow.job;

import com.ga.disclosure.audit.verify.VerifyReport;
import com.ga.disclosure.workflow.anchor.AnchorJob;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.disclosure.ExpireService;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 4·5 유스케이스를 작업 본체로 감싼다(6A 계획 §6.2) — CLI와 HTTP가 같은 본체·같은 보고서 형식을 쓴다. 보고서는 JCS 바이트이고 그 테넌트의 것만 싣는다.
 * 매개변수(작업 행 {@code params})도 여기서 읽는다: 모르는 키는 거부(400).
 */
public final class StandardJobs {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final int DEFAULT_EXPIRE_LIMIT = 500;
    public static final int DEFAULT_RECONCILE_LIMIT = 500;
    public static final int DEFAULT_DESTROY_LIMIT = 100;

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
