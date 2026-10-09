package com.ga.disclosure.app.cli;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.disclosure.DraftAbandonService;
import com.ga.disclosure.workflow.disclosure.LifecycleService;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.StandardJobs;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.util.Objects;

/**
 * 초안 폐기 CLI(6B 지시문 §6·§9). 운영자 대리 실행이다(행위자 = {@code --operator}).
 * <pre>
 * drafts abandon      --tenant T --id &lt;uuid&gt; --reason-code &lt;CODE&gt; --operator &lt;id&gt;   (고정 룰의 draft.abandonReasons — 텍스트 없음)
 * drafts abandon-idle --tenant T [--limit 200] --operator &lt;id&gt;                      (작업 ABANDON_DRAFTS — 룰 draft.abandonAfterDays가 null이면 0건)
 * </pre>
 * 업무 거부는 종료 2({@link CliRejection}).
 */
final class DraftCommands {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DraftAbandonService drafts;
    private final JobCommands jobs;
    private final PrintStream out;

    DraftCommands(DraftAbandonService drafts, JobCommands jobs, PrintStream out) {
        this.drafts = Objects.requireNonNull(drafts, "drafts");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.out = Objects.requireNonNull(out, "out");
    }

    boolean handles(String command) {
        return command.startsWith("drafts ");
    }

    void run(CliArguments args) {
        switch (args.command()) {
            case "drafts abandon" -> abandon(args);
            case "drafts abandon-idle" -> abandonIdle(args);
            default -> throw new CliFailure("unknown command '" + args.command() + "' — see DraftCommands javadoc");
        }
    }

    private void abandon(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        LifecycleService.Outcome o = drafts.abandon(Caller.cli(tenant, args.required("operator")), DisclosureId.parse(args.required("id")),
                args.required("reason-code"));
        if (!o.applied()) {
            out.println("ABANDON " + tenant + " " + o.id() + " REJECTED " + o.rejection().orElseThrow());
            throw new CliRejection("abandon rejected: " + o.rejection().orElseThrow());
        }
        out.println("ABANDON " + tenant + " " + o.id() + " " + o.status());
    }

    private void abandonIdle(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        int limit = Integer.parseInt(args.optional("limit").orElse(Integer.toString(StandardJobs.DEFAULT_ABANDON_LIMIT)));
        jobs.one(Caller.cli(tenant, args.required("operator")), JobKind.ABANDON_DRAFTS, JSON.createObjectNode().put("limit", limit),
                        StandardJobs.abandonDrafts(drafts, limit))
                .ifPresent(r -> out.println("ABANDON_DRAFTS " + tenant + " abandoned=" + r.abandoned().size() + " skipped=" + r.skipped()
                        + " abandonAfterDays=" + (r.abandonAfterDays().isPresent() ? Integer.toString(r.abandonAfterDays().getAsInt()) : "-")));
        jobs.failIfIncomplete();
    }
}
