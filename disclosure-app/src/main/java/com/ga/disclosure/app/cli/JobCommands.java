package com.ga.disclosure.app.cli;

import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.JobQueryService;
import com.ga.disclosure.workflow.job.JobRecord;
import com.ga.disclosure.workflow.job.JobRunner;
import com.ga.disclosure.workflow.job.JobWork;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 작업 CLI(6A 계획 §6.2): 기존 배치 명령({@code anchor run}·{@code retention destroy}·{@code verify tenant}·{@code disclosure expire}·
 * {@code artifacts reconcile})이 {@link JobRunner}를 지나게 하는 도우미와 조회 명령. 기존 출력 줄은 그대로 두고 테넌트마다 {@code JOB <id> <status>} 줄을
 * 더한다. 같은 종류가 이미 도는 테넌트는 그 테넌트만 {@code JOB_BUSY}다. 바쁜 테넌트나 FAILED로 끝난 작업(보고서 저장 실패·잠금 상실 — 본체 예외는 그대로
 * 던진다)이 하나라도 있으면 다른 테넌트를 다 돈 뒤 종료 코드 2.
 * <pre>
 * jobs list   --tenant T [--limit 20] --operator &lt;id&gt;
 * jobs show   --tenant T --id &lt;uuid&gt; --operator &lt;id&gt;
 * jobs report --tenant T --id &lt;uuid&gt; --out &lt;path&gt; --operator &lt;id&gt;   (보고서 평문 JCS — 열람 감사 JOB_REPORT_VIEW)
 * </pre>
 */
final class JobCommands {

    private final JobRunner runner;
    private final JobQueryService queries;
    private final PrintStream out;
    private final List<String> problems = new ArrayList<>();

    JobCommands(JobRunner runner, JobQueryService queries, PrintStream out) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.out = Objects.requireNonNull(out, "out");
    }

    boolean handles(String command) {
        return command.startsWith("jobs ");
    }

    void run(CliArguments args) {
        switch (args.command()) {
            case "jobs list" -> list(args);
            case "jobs show" -> show(args);
            case "jobs report" -> report(args);
            default -> throw new CliFailure("unknown command '" + args.command() + "' — see JobCommands javadoc");
        }
    }

    /** 단일 테넌트 작업 하나. 그 테넌트가 바쁘면 빈 값(명령 끝의 {@link #failIfIncomplete}가 종료 코드 2로 끝낸다). */
    <R> Optional<R> one(Caller caller, JobKind kind, ObjectNode params, JobWork<R> work) {
        return group(List.of(caller), kind, params, work).result();
    }

    /** 여러 테넌트를 한 번에 묶는 작업(앵커). */
    <R> JobRunner.Run<R> group(List<Caller> callers, JobKind kind, ObjectNode params, JobWork<R> work) {
        JobRunner.Run<R> run = runner.run(callers, kind, params, work);
        for (JobRunner.Outcome o : run.outcomes()) {
            switch (o) {
                case JobRunner.Outcome.Busy b -> {
                    problems.add(b.tenant() + " busy");
                    out.println("JOB_BUSY " + b.tenant() + " " + kind.lockKind());
                }
                case JobRunner.Outcome.Finished f -> {
                    if (f.status() != JobRecord.Status.SUCCEEDED) {
                        problems.add(f.tenant() + " " + f.errorCode().orElse(f.status().name()));
                    }
                    out.println("JOB " + f.jobId() + " " + f.status() + f.errorCode().map(c -> " " + c).orElse("")
                            + f.reportSha256().map(s -> " report=" + s).orElse(""));
                }
            }
        }
        return run;
    }

    /** 바쁜 테넌트·FAILED 작업이 있었으면 종료 코드 2(명령의 다른 테넌트는 이미 돌았다). */
    void failIfIncomplete() {
        if (!problems.isEmpty()) {
            List<String> all = List.copyOf(problems);
            problems.clear();
            throw new CliRejection("jobs not completed (a busy tenant means a job of the same kind is already running): " + all);
        }
    }

    // ------------------------------------------------------------------ 조회

    private static Caller caller(CliArguments args) {
        return Caller.cli(TenantId.of(args.required("tenant")), args.required("operator"));
    }

    private void list(CliArguments args) {
        int limit = Integer.parseInt(args.optional("limit").orElse("20"));
        queries.list(caller(args), limit, Optional.empty()).items().forEach(this::print);
    }

    private void show(CliArguments args) {
        JobRecord job = queries.show(caller(args), UUID.fromString(args.required("id")));
        print(job);
        out.println("  params " + job.params());
    }

    private void report(CliArguments args) {
        UUID id = UUID.fromString(args.required("id"));
        byte[] report;
        try {
            report = queries.report(caller(args), id);
        } catch (JobQueryService.ReportNotAvailableException e) {
            out.println("JOB_REPORT " + id + " NOT_AVAILABLE " + e.status());
            throw new CliRejection("job report not available: " + e.status());
        }
        Path target = Path.of(args.required("out"));
        try {
            Files.write(target, report);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.println("JOB_REPORT " + id + " sha256=" + com.ga.platform.canonical.Sha256.of(report) + " -> " + target);
    }

    private void print(JobRecord j) {
        out.println("JOB " + j.jobId() + " " + j.kind() + " " + j.status() + " requested=" + j.requestedAt() + " by=" + j.requestedBy() + " via="
                + j.channel() + j.errorCode().map(c -> " error=" + c).orElse("") + j.reportSha256().map(s -> " report=" + s).orElse(""));
    }
}
