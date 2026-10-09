package com.ga.disclosure.app.cli;

import com.ga.disclosure.api.dto.GateRequest;
import com.ga.disclosure.api.error.MalformedRequestException;
import com.ga.disclosure.api.mapper.GateMapper;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.gate.GateDecision;
import com.ga.disclosure.workflow.gate.GateService;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 청약 게이트 CLI(6B 지시문 §9). 운영자 대리 실행(행위자 = {@code --operator}) — 판정·감사는 HTTP와 같은 유스케이스.
 * <pre>
 * gate check --tenant T --file &lt;request.json&gt; --operator &lt;id&gt;   ({@code {applicationNo|policyNo, customerRef}} — 번호는 인자가 아니라 파일로)
 * </pre>
 * 출력은 판정·사유·확인서 번호·대기 역할·룰 버전뿐(요청 번호를 되풀이하지 않는다). BLOCKED도 답이므로 종료 0, 형식 오류는 종료 1.
 */
final class GateCommands {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final GateService gate;
    private final PrintStream out;

    GateCommands(GateService gate, PrintStream out) {
        this.gate = Objects.requireNonNull(gate, "gate");
        this.out = Objects.requireNonNull(out, "out");
    }

    boolean handles(String command) {
        return command.startsWith("gate ");
    }

    void run(CliArguments args) {
        if (!args.command().equals("gate check")) {
            throw new CliFailure("unknown command '" + args.command() + "' — see GateCommands javadoc");
        }
        TenantId tenant = TenantId.of(args.required("tenant"));
        GateRequest request;
        try {
            request = JSON.readValue(Files.readAllBytes(Path.of(args.required("file"))), GateRequest.class);
        } catch (IOException | RuntimeException e) {
            throw new CliFailure("gate request file is not a JSON object {applicationNo|policyNo, customerRef}");
        }
        GateDecision d;
        try {
            d = gate.check(Caller.cli(tenant, args.required("operator")), () -> GateMapper.query(request));
        } catch (MalformedRequestException e) {
            throw new CliFailure("gate request field is malformed: " + e.field());
        }
        out.println("GATE " + tenant + " " + d.decision() + " " + d.reason() + " disclosureNo=" + d.disclosureNo().orElse("-") + " pending="
                + d.pendingRoles().stream().map(SignerRole::name).collect(Collectors.joining(",", "[", "]")) + " rule="
                + d.ruleVersionId().map(RuleVersionId::value).orElse("-"));
    }
}
