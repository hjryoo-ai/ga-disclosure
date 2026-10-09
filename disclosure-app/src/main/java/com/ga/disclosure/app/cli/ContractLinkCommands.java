package com.ga.disclosure.app.cli;

import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.contract.ContractLinkBatch;
import com.ga.disclosure.workflow.contract.ContractLinkBatchParser;
import com.ga.disclosure.workflow.contract.ContractLinkCsv;
import com.ga.disclosure.workflow.contract.ContractLinkService;
import com.ga.disclosure.workflow.contract.InvalidContractLinkBatchException;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.StandardJobs;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 계약 연결 CLI(6B 계획 §4). 입력은 파일로만 — 증권·청약 번호를 인자로 받지 않고(절대 규칙 6) 출력하지도 않는다(결과별 수와 작업 줄뿐, 항목 결과는
 * {@code jobs report}).
 * <pre>
 * contract-links import          --tenant T --file &lt;path&gt; --format json|csv [--source S --batch-id B (csv만)] --operator &lt;id&gt;
 * contract-links purge-unmatched --tenant T [--limit 10000] --operator &lt;id&gt;   (룰 contractLink.unmatchedRetentionDays가 null이면 0건)
 * </pre>
 * 형식 위반은 종료 3(입력 오류)이고 위치·규칙 이름만 출력한다.
 */
final class ContractLinkCommands {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ContractLinkService links;
    private final JobCommands jobs;
    private final PrintStream out;

    ContractLinkCommands(ContractLinkService links, JobCommands jobs, PrintStream out) {
        this.links = Objects.requireNonNull(links, "links");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.out = Objects.requireNonNull(out, "out");
    }

    boolean handles(String command) {
        return command.startsWith("contract-links ");
    }

    void run(CliArguments args) {
        switch (args.command()) {
            case "contract-links import" -> importFile(args);
            case "contract-links purge-unmatched" -> purge(args);
            default -> throw new CliFailure("unknown command '" + args.command() + "' — see ContractLinkCommands javadoc");
        }
    }

    private void importFile(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        byte[] content;
        try {
            content = Files.readAllBytes(Path.of(args.required("file")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ContractLinkBatch batch;
        try {
            batch = switch (args.required("format")) {
                case "json" -> ContractLinkBatchParser.parse(content);
                case "csv" -> ContractLinkCsv.parse(args.required("source"), args.required("batch-id"), content);
                default -> throw new CliFailure("--format is json or csv");
            };
        } catch (InvalidContractLinkBatchException e) {
            out.println("CONTRACT_LINKS_REJECTED " + tenant + " " + String.join("; ", e.problems()));
            throw new CliExit(3, "contract-link batch rejected");
        }
        Caller caller = Caller.cli(tenant, args.required("operator"));
        jobs.one(caller, JobKind.CONTRACT_LINK_IMPORT, StandardJobs.contractLinkSummary(batch), StandardJobs.contractLinkImport(links, batch))
                .ifPresent(r -> out.println("CONTRACT_LINKS " + tenant + " source=" + r.source() + " batch=" + r.batchId() + " items=" + r.items().size()
                        + " " + r.counts().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(" "))));
        jobs.failIfIncomplete();
    }

    private void purge(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        int limit = Integer.parseInt(args.optional("limit").orElse(Integer.toString(ContractLinkService.MAX_PURGE)));
        jobs.one(Caller.cli(tenant, args.required("operator")), JobKind.CONTRACT_LINK_UNMATCHED_PURGE,
                        JSON.createObjectNode().put("limit", limit), StandardJobs.contractLinkUnmatchedPurge(links, limit))
                .ifPresent(r -> out.println("CONTRACT_LINKS_PURGE " + tenant + " purged=" + r.purged()
                        + " retentionDays=" + (r.retentionDays().isPresent() ? Integer.toString(r.retentionDays().getAsInt()) : "-")));
        jobs.failIfIncomplete();
    }
}
