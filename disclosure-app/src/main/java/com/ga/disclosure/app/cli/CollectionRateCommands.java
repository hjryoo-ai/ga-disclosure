package com.ga.disclosure.app.cli;

import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.metric.CollectionRates;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.disclosure.CommandRejectedException;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.StandardJobs;
import com.ga.disclosure.workflow.rate.CollectionRateService;
import com.ga.disclosure.workflow.rate.CollectionRateStore;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.time.YearMonth;
import java.util.Objects;

/**
 * 징구율 CLI(6B 계획 §5 — 내부 지표, 규제 정의 없음). 운영자 대리 실행이다(행위자 = {@code --operator}).
 * <pre>
 * collection-rates snapshot --tenant T [--period YYYY-MM] --operator &lt;id&gt;     (작업 COLLECTION_RATE_SNAPSHOT — 끝난 달만, 없으면 KST 전월)
 * collection-rates list     --tenant T --from YYYY-MM --to YYYY-MM [--org-path /HQ] [--rule-version ID] --operator &lt;id&gt;
 * </pre>
 * 같은 (달, 룰 버전)의 재계산은 거부 {@code SNAPSHOT_EXISTS} — 종료 2({@link CliRejection}). 출력은 수치·해시뿐이다.
 */
final class CollectionRateCommands {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CollectionRateService rates;
    private final JobCommands jobs;
    private final PrintStream out;

    CollectionRateCommands(CollectionRateService rates, JobCommands jobs, PrintStream out) {
        this.rates = Objects.requireNonNull(rates, "rates");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.out = Objects.requireNonNull(out, "out");
    }

    boolean handles(String command) {
        return command.startsWith("collection-rates ");
    }

    void run(CliArguments args) {
        switch (args.command()) {
            case "collection-rates snapshot" -> snapshot(args);
            case "collection-rates list" -> list(args);
            default -> throw new CliFailure("unknown command '" + args.command() + "' — see CollectionRateCommands javadoc");
        }
    }

    private void snapshot(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        YearMonth period = args.optional("period").map(YearMonth::parse).orElseGet(rates::previousMonth);
        rates.requireFinished(period);
        try {
            jobs.one(Caller.cli(tenant, args.required("operator")), JobKind.COLLECTION_RATE_SNAPSHOT,
                            JSON.createObjectNode().put("periodMonth", period.toString()), StandardJobs.collectionRateSnapshot(rates, period))
                    .ifPresent(r -> {
                        CollectionRates.Group all = r.result().tenantWide();
                        out.println("COLLECTION_RATE_SNAPSHOT " + tenant + " " + r.period() + " rule=" + r.ruleVersionId() + " formula=" + r.result().formula()
                                + " rows=" + r.result().groups().size() + " tenant=" + all.numerator() + "/" + all.denominator() + " rateBp="
                                + (all.rateBp().isPresent() ? Integer.toString(all.rateBp().getAsInt()) : "-") + " " + CollectionRates.DEFINITION);
                    });
        } catch (CommandRejectedException e) {
            out.println("COLLECTION_RATE_SNAPSHOT " + tenant + " " + period + " REJECTED " + e.code());
            throw new CliRejection("collection-rate snapshot rejected: " + e.code());
        }
        jobs.failIfIncomplete();
    }

    private void list(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        out.println("# " + CollectionRates.DEFINITION + " (" + CollectionRates.DEFINITION_TEXT + ")");
        for (CollectionRateStore.Row r : rates.read(Caller.cli(tenant, args.required("operator")), YearMonth.parse(args.required("from")),
                YearMonth.parse(args.required("to")), args.optional("org-path"), args.optional("rule-version").map(RuleVersionId::of))) {
            out.println("COLLECTION_RATE " + tenant + " " + YearMonth.from(r.periodMonth()) + " " + r.orgPath() + " rule=" + r.ruleVersionId() + " formula="
                    + r.formula() + " " + r.numerator() + "/" + r.denominator() + " rateBp=" + (r.rateBp().isPresent() ? Integer.toString(r.rateBp().getAsInt()) : "-")
                    + " inputs=" + r.inputsHash());
        }
    }
}
