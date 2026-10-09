package com.ga.disclosure.app.cli;

import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.flag.FlagCommandService;
import com.ga.disclosure.workflow.flag.FlagLookup;
import com.ga.disclosure.workflow.flag.FlagQueryService;
import com.ga.disclosure.workflow.flag.FlagRejectedException;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 준법 큐 CLI(6B 지시문 §9 — {@code flags list/resolve}). 운영자 대리 실행(행위자 = {@code --operator}) — 판정·감사는 HTTP와 같은 유스케이스.
 * <pre>
 * flags list    --tenant T [--status OPEN|RESOLVED] [--type TYPE] [--limit 50] --operator &lt;id&gt;
 * flags resolve --tenant T --id &lt;uuid&gt; --code &lt;CODE&gt; [--verify-job &lt;uuid&gt;] --operator &lt;id&gt;
 *               ({@code CHAIN_BROKEN}의 근거는 {@code --verify-job} — {@code {"verifyRunJobId": …}}만, 텍스트 없음)
 * </pre>
 * 출력은 플래그 ID·유형·상태·열린 시각·확인서 번호뿐(개인정보 없음). 업무 거부는 거부 코드를 출력하고 종료 2({@link CliRejection}).
 */
final class FlagCommands {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final FlagQueryService queries;
    private final FlagCommandService commands;
    private final PrintStream out;

    FlagCommands(FlagQueryService queries, FlagCommandService commands, PrintStream out) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.out = Objects.requireNonNull(out, "out");
    }

    boolean handles(String command) {
        return command.startsWith("flags ");
    }

    void run(CliArguments args) {
        switch (args.command()) {
            case "flags list" -> list(args);
            case "flags resolve" -> resolve(args);
            default -> throw new CliFailure("unknown command '" + args.command() + "' — see FlagCommands javadoc");
        }
    }

    private void list(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        int limit = Integer.parseInt(args.optional("limit").orElse("50"));
        FlagLookup.Filter filter = new FlagLookup.Filter(args.optional("status").map(FlagLookup.FlagStatus::valueOf), args.optional("type"), Optional.empty(),
                Optional.empty());
        var page = queries.list(Caller.cli(tenant, args.required("operator")), limit, Optional.empty(), filter);
        page.items().forEach(f -> out.println("FLAG " + tenant + " " + f.flagId() + " " + f.type() + " " + f.status() + " raisedAt=" + f.raisedAt()
                + f.disclosureNo().map(n -> " disclosureNo=" + n).orElse("")));
        out.println("FLAGS " + tenant + " " + page.items().size() + (page.next().isPresent() ? " MORE" : ""));
    }

    private void resolve(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        UUID id = UUID.fromString(args.required("id"));
        Optional<tools.jackson.databind.JsonNode> evidence = args.optional("verify-job")
                .map(job -> JSON.createObjectNode().put("verifyRunJobId", UUID.fromString(job).toString()));
        try {
            FlagCommandService.Resolved r = commands.resolve(Caller.cli(tenant, args.required("operator")), id, args.required("code"), evidence);
            out.println("FLAG_RESOLVE " + tenant + " " + r.flagId() + " " + r.type() + " RESOLVED " + r.resolutionCode());
        } catch (FlagRejectedException e) {
            out.println("FLAG_RESOLVE " + tenant + " " + id + " REJECTED " + e.rejection());
            throw new CliRejection("flag resolution rejected: " + e.rejection());
        }
    }
}
