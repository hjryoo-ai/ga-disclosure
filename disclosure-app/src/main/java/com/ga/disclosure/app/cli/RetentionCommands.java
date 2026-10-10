package com.ga.disclosure.app.cli;

import com.ga.disclosure.audit.verify.PackageVerifier;
import com.ga.disclosure.audit.verify.VerifyInputException;
import com.ga.disclosure.audit.verify.VerifyReport;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.anchor.AnchorJob;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.StandardJobs;
import com.ga.disclosure.workflow.retention.DestructionJob;
import com.ga.disclosure.workflow.retention.LegalHoldRejectedException;
import com.ga.disclosure.workflow.retention.LegalHoldService;
import com.ga.disclosure.workflow.verify.ReceiptExporter;
import com.ga.disclosure.workflow.verify.TenantVerifier;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Phase 5 CLI(5 계획 §8, 설계서 부록 B). 출력에는 개인정보가 없다 — ID·번호·코드·수·해시뿐. 보류 사유 텍스트는 파일로만 받는다(CLAUDE.md 규칙 6).
 *
 * <pre>
 * anchor run            [--date YYYY-MM-DD(KST, 기본 오늘)] [--tenants all|T1,T2] --operator &lt;id&gt;          (실패가 있으면 종료 2)
 * anchor receipt export --tenant T --id &lt;uuid&gt; --out &lt;receipt.json&gt; --operator &lt;id&gt;  (아직 덮이지 않으면 종료 2)
 * verify package        --package &lt;zip&gt; [--receipt &lt;json&gt;] [--tsa-trust &lt;pem&gt;] [--report &lt;path&gt;]   (0 일치, 2 불일치, 3 입력 오류)
 * verify tenant         [--tenants all|T1,T2] [--tsa-trust &lt;pem&gt;] [--report-dir &lt;dir&gt;] --operator &lt;id&gt;   (테넌트 중 최대 종료 코드)
 * retention destroy     [--tenants all|T1,T2] [--as-of &lt;instant&gt;] [--dry-run yes] [--limit 100] [--report-dir &lt;dir&gt;] --operator &lt;id&gt;
 * retention recompute   --rule-version &lt;GLOBAL id&gt; [--apply yes] [--tenants all|T1,T2] [--report-dir &lt;dir&gt;] --operator &lt;id&gt;
 *                       (6B — 기본 dry-run, 연장만, 쓸 수 없는 룰 버전·미완료 작업은 종료 2)
 * legal-hold place      --tenant T (--id &lt;uuid&gt; | --customer &lt;ref&gt;) --reason-code &lt;CODE&gt; [--reason-file &lt;path&gt;] [--if-absent yes] --operator &lt;id&gt;
 * legal-hold release    --tenant T --hold &lt;uuid&gt; --reason-code &lt;CODE&gt; --operator &lt;id&gt;
 * </pre>
 * {@code verify package}는 저장소·DB를 쓰지 않는다(검증기는 생산자·환경 독립 — 설계서 §6.7). 판정 시각은 이 프로세스의 시계다.
 */
final class RetentionCommands {

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private final AnchorJob anchors;
    private final ReceiptExporter receipts;
    private final TenantVerifier verifier;
    private final DestructionJob destruction;
    private final LegalHoldService holds;
    private final com.ga.disclosure.workflow.disclosure.RetentionRecomputeService recompute;
    private final JobCommands jobs;
    private final Function<String, List<TenantId>> tenants;
    private final Clock clock;
    private final PrintStream out;
    private final com.ga.disclosure.workflow.metrics.OperationalMetrics metrics;

    RetentionCommands(AnchorJob anchors, ReceiptExporter receipts, TenantVerifier verifier, DestructionJob destruction, LegalHoldService holds,
                      com.ga.disclosure.workflow.disclosure.RetentionRecomputeService recompute, JobCommands jobs,
                      Function<String, List<TenantId>> tenants, Clock clock, PrintStream out, com.ga.disclosure.workflow.metrics.OperationalMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.recompute = Objects.requireNonNull(recompute, "recompute");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.destruction = Objects.requireNonNull(destruction, "destruction");
        this.holds = Objects.requireNonNull(holds, "holds");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.out = Objects.requireNonNull(out, "out");
    }

    boolean handles(String command) {
        return command.startsWith("anchor ") || command.startsWith("verify ") || command.startsWith("retention ") || command.startsWith("legal-hold ");
    }

    void run(CliArguments args) {
        switch (args.command()) {
            case "anchor run" -> anchorRun(args);
            case "anchor receipt export" -> receiptExport(args);
            case "verify package" -> verifyPackage(args);
            case "verify tenant" -> verifyTenant(args);
            case "retention destroy" -> destroy(args);
            case "retention recompute" -> recompute(args);
            case "legal-hold place" -> place(args);
            case "legal-hold release" -> release(args);
            default -> throw new CliFailure("unknown command '" + args.command() + "' — see RetentionCommands javadoc");
        }
    }

    /** 운영자 CLI 호출자(감사 역할 OPERATOR, 6A 승인 Q9 — {@code --role} 폐기). */
    private static Caller caller(CliArguments args, TenantId tenant) {
        return Caller.cli(tenant, args.required("operator"));
    }

    private void anchorRun(CliArguments args) {
        List<TenantId> targets = tenants.apply(args.optional("tenants").orElse("all"));
        String operator = args.required("operator");
        Optional<LocalDate> date = args.optional("date").map(LocalDate::parse);
        ObjectNode params = JSON.createObjectNode();
        date.ifPresent(d -> params.put("date", d.toString()));
        // 6A 계획 §6.2: 테넌트마다 잠금·작업 행, 잡은 테넌트들로 앵커 배치 한 번(한 트리) — 잠긴 테넌트는 그 테넌트만 JOB_BUSY
        Optional<AnchorJob.Report> ran = jobs.group(targets.stream().map(t -> Caller.cli(t, operator)).toList(), JobKind.ANCHOR, params,
                StandardJobs.anchor(anchors, date, operator, metrics)).result();
        if (ran.isEmpty()) {
            jobs.failIfIncomplete();
            return;
        }
        AnchorJob.Report r = ran.get();
        out.println("ANCHOR_RUN date=" + r.date() + " created=" + r.created() + " unchanged=" + r.unchanged() + " retries=" + r.retries()
                + " batches=" + r.batches().size() + " second=" + r.secondBatches() + " receipts=" + r.receipts());
        r.batches().forEach(b -> out.println("  BATCH " + b.anchorDate() + " " + b.batchId() + " root=" + b.root() + " depth=" + b.depth() + " leaves="
                + b.leaves() + (b.second() ? " SECOND" : "") + " genTime=" + b.genTime()));
        r.failures().forEach(f -> out.println("  FAILURE " + f.stage() + " " + (f.tenant() == null ? "-" : f.tenant()) + " " + f.anchorDate() + " "
                + f.code()));
        if (!r.failures().isEmpty()) {
            throw new CliRejection("anchor run finished with " + r.failures().size() + " failure(s)");
        }
        jobs.failIfIncomplete();
    }

    private void receiptExport(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        DisclosureId id = DisclosureId.parse(args.required("id"));
        switch (receipts.export(caller(args, tenant), id)) {
            case ReceiptExporter.Result.Exported e -> {
                Path target = Path.of(args.required("out"));
                write(target, e.bytes());
                out.println("RECEIPT_EXPORT " + tenant + " " + id + " covering=" + e.export().covering().anchorSeq() + "@"
                        + e.export().covering().anchorDate() + (e.export().previous() == null ? "" : " previous=" + e.export().previous().anchorSeq()
                        + "@" + e.export().previous().anchorDate()) + " links=" + e.export().sealChain().size() + " -> " + target);
            }
            case ReceiptExporter.Result.NotAvailable n -> {
                out.println("RECEIPT_EXPORT " + tenant + " " + id + " NOT_AVAILABLE " + n.code());
                throw new CliRejection("receipt not available: " + n.code());
            }
        }
    }

    private void verifyPackage(CliArguments args) {
        VerifyReport report;
        try {
            report = PackageVerifier.verify(read(args.required("package")), args.optional("receipt").map(RetentionCommands::read).orElse(null),
                    args.optional("tsa-trust").map(RetentionCommands::read).orElse(null), clock.instant());
        } catch (VerifyInputException e) {
            out.println("VERIFY_PACKAGE INPUT_ERROR " + e.code());
            throw new CliExit(VerifyReport.EXIT_INPUT_ERROR, "verify package input error: " + e.code());
        }
        args.optional("report").ifPresent(p -> write(Path.of(p), report.canonical()));
        print("VERIFY_PACKAGE", report);
        if (report.exitCode() != VerifyReport.EXIT_MATCH) {
            throw new CliExit(report.exitCode(), "verify package: mismatch");
        }
    }

    private void verifyTenant(CliArguments args) {
        byte[] trust = args.optional("tsa-trust").map(RetentionCommands::read).orElse(null);
        int worst = VerifyReport.EXIT_MATCH;
        ObjectNode params = JSON.createObjectNode();
        if (trust != null) {
            params.put("tsaTrustSha256", Sha256.of(trust));
        }
        for (TenantId tenant : tenants.apply(args.optional("tenants").orElse("all"))) {
            Optional<VerifyReport> ran = jobs.one(caller(args, tenant), JobKind.VERIFY_TENANT, params, StandardJobs.verifyTenant(verifier, trust));
            if (ran.isEmpty()) {
                continue;
            }
            VerifyReport report = ran.get();
            args.optional("report-dir").ifPresent(d -> write(Path.of(d).resolve("verify-" + tenant + ".json"), report.canonical()));
            print("VERIFY_TENANT " + tenant, report);
            worst = Math.max(worst, report.exitCode());
        }
        if (worst != VerifyReport.EXIT_MATCH) {
            throw new CliExit(worst, "verify tenant: mismatch");
        }
        jobs.failIfIncomplete();
    }

    private void print(String head, VerifyReport report) {
        out.println(head + " " + (report.matches() ? "MATCH" : "MISMATCH") + " findings=" + report.findings().size() + " sha256=" + report.sha256());
        report.findings().forEach(f -> out.println("  " + f.code() + " " + f.where()));
        report.statements().forEach(s -> out.println("  STATEMENT " + s));
    }

    private void recompute(CliArguments args) {
        com.ga.disclosure.domain.vo.RuleVersionId version = com.ga.disclosure.domain.vo.RuleVersionId.of(args.required("rule-version"));
        boolean apply = args.optional("apply").map(v -> v.equals("yes")).orElse(false);
        ObjectNode params = JSON.createObjectNode().put("ruleVersionId", version.value()).put("apply", apply);
        boolean rejected = false;
        for (TenantId tenant : tenants.apply(args.optional("tenants").orElse("all"))) {
            Optional<com.ga.disclosure.workflow.disclosure.RetentionRecomputeService.Report> ran;
            try {
                ran = jobs.one(caller(args, tenant), JobKind.RETENTION_RECOMPUTE, params, StandardJobs.retentionRecompute(recompute, version, apply));
            } catch (com.ga.disclosure.workflow.disclosure.CommandRejectedException e) {
                out.println("RETENTION_RECOMPUTE " + tenant + " REJECTED " + e.code());
                rejected = true;
                continue;
            }
            if (ran.isEmpty()) {
                continue;
            }
            var r = ran.get();
            args.optional("report-dir").ifPresent(d -> write(Path.of(d).resolve("retention-recompute-" + tenant + ".json"),
                    Canonicalizer.canonicalize(r.toJson())));
            out.println("RETENTION_RECOMPUTE " + tenant + " rule=" + version + (apply ? " APPLY" : " DRY_RUN") + " extended="
                    + r.count(com.ga.disclosure.workflow.disclosure.RetentionRecomputeService.Outcome.EXTENDED) + " unchanged="
                    + r.count(com.ga.disclosure.workflow.disclosure.RetentionRecomputeService.Outcome.UNCHANGED) + " destroyedExcluded="
                    + r.destroyedExcluded() + " relockPending=" + r.relockPending());
            r.items().stream().filter(i -> i.outcome() == com.ga.disclosure.workflow.disclosure.RetentionRecomputeService.Outcome.EXTENDED)
                    .forEach(i -> out.println("  " + (apply ? "EXTENDED " : "WOULD_EXTEND ") + i.disclosureNo() + " " + i.before() + " -> " + i.candidate()));
        }
        if (rejected) {
            throw new CliRejection("retention recompute rejected: the rule version is not usable");
        }
        jobs.failIfIncomplete();
    }

    private void destroy(CliArguments args) {
        Instant asOf = args.optional("as-of").map(Instant::parse).orElseGet(clock::instant);
        boolean dryRun = args.optional("dry-run").map(v -> v.equals("yes")).orElse(false);
        int limit = Integer.parseInt(args.optional("limit").orElse("100"));
        boolean failed = false;
        ObjectNode params = JSON.createObjectNode().put("asOf", asOf.toString()).put("limit", limit);
        for (TenantId tenant : tenants.apply(args.optional("tenants").orElse("all"))) {
            Optional<DestructionJob.Report> ran = jobs.one(caller(args, tenant), dryRun ? JobKind.DESTROY_DRY_RUN : JobKind.DESTROY, params,
                    StandardJobs.destroy(destruction, asOf, dryRun, limit));
            if (ran.isEmpty()) {
                continue;
            }
            DestructionJob.Report r = ran.get();
            args.optional("report-dir").ifPresent(d -> write(Path.of(d).resolve("destruction-" + tenant + ".json"),
                    Canonicalizer.canonicalize(r.toJson())));
            out.println("RETENTION_DESTROY " + tenant + " asOf=" + asOf + (dryRun ? " DRY_RUN" : "") + " candidates=" + r.candidates() + " destroyed="
                    + r.destroyed().size() + " wouldDestroy=" + r.wouldDestroy().size() + " skipped=" + r.skippedByReason() + " failed="
                    + r.failed().size() + " anchorsWaived=" + r.anchorsWaived() + " holdAfterShred=" + r.holdAfterShred() + " customersDestroyed="
                    + r.customersDestroyed().size());
            r.destroyed().forEach(d -> out.println("  DESTROYED " + d.id() + " " + d.disclosureNo() + " anchorsWaived=" + d.anchorsWaived()));
            r.wouldDestroy().forEach(d -> out.println("  WOULD_DESTROY " + d.id() + " " + d.disclosureNo()));
            r.skipped().forEach(s -> out.println("  SKIPPED " + s.id() + " " + s.reason() + " " + s.reasons()));
            r.failed().forEach(f -> out.println("  FAILED " + f.id() + " " + f.stage() + " " + f.code()));
            failed |= !r.failed().isEmpty();
        }
        if (failed) {
            throw new CliRejection("retention destroy finished with failures");
        }
        jobs.failIfIncomplete();
    }

    private void place(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        LegalHoldService.Target target = args.optional("id").<LegalHoldService.Target>map(i -> new LegalHoldService.Target.Disclosure(DisclosureId.parse(i)))
                .orElseGet(() -> new LegalHoldService.Target.Customer(new CustomerRef(args.required("customer"))));
        String text = args.optional("reason-file").map(f -> new String(read(f), StandardCharsets.UTF_8).strip()).orElse(null);
        try {
            LegalHoldService.Outcome o = holds.place(caller(args, tenant), target, args.required("reason-code"), text);
            out.println("LEGAL_HOLD_PLACE " + tenant + " " + label(target) + " hold=" + o.holdId() + " " + storage(o.storage()));
        } catch (LegalHoldRejectedException e) {
            if (e.code().equals("ALREADY_HELD") && args.optional("if-absent").map(v -> v.equals("yes")).orElse(false)) {
                out.println("LEGAL_HOLD_PLACE " + tenant + " " + label(target) + " NOOP (an active hold exists)");
                return;
            }
            out.println("LEGAL_HOLD_PLACE " + tenant + " " + label(target) + " REJECTED " + e.code());
            throw new CliRejection("legal hold rejected: " + e.code());
        }
    }

    private void release(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        UUID hold = UUID.fromString(args.required("hold"));
        try {
            LegalHoldService.Outcome o = holds.release(caller(args, tenant), hold, args.required("reason-code"));
            out.println("LEGAL_HOLD_RELEASE " + tenant + " hold=" + hold + " " + storage(o.storage()));
        } catch (LegalHoldRejectedException e) {
            out.println("LEGAL_HOLD_RELEASE " + tenant + " hold=" + hold + " REJECTED " + e.code());
            throw new CliRejection("legal hold release rejected: " + e.code());
        }
    }

    private static String label(LegalHoldService.Target target) {
        return switch (target) {
            case LegalHoldService.Target.Disclosure d -> "disclosure=" + d.id();
            case LegalHoldService.Target.Customer c -> "customer=" + c.ref().value();
        };
    }

    private static String storage(LegalHoldService.StorageHold s) {
        return s.unsupported() ? "storageHold=UNSUPPORTED" : "storageHold=" + s.applied() + (s.failed() == 0 ? "" : " storageHoldFailed=" + s.failed());
    }

    private static byte[] read(String path) {
        try {
            return Files.readAllBytes(Path.of(path));
        } catch (IOException e) {
            throw new CliExit(VerifyReport.EXIT_INPUT_ERROR, "cannot read " + path);
        }
    }

    private static void write(Path target, byte[] bytes) {
        CliFiles.replace(target, bytes);
    }
}
