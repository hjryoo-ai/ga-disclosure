package com.ga.disclosure.app.cli;

import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.customer.CustomerVault;
import com.ga.disclosure.workflow.customer.RegistrationKey;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.workflow.disclosure.DisclosureLookup;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.disclosure.SignSessionService;
import com.ga.disclosure.workflow.sign.DeviceInfo;
import com.ga.disclosure.workflow.sign.PaperScan;
import com.ga.disclosure.workflow.sign.SignatureCapture;
import com.ga.disclosure.workflow.sign.SignatureStore;
import com.ga.disclosure.workflow.sign.StoredSignature;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 데모 서명(Phase 4, 4 계획 §7.6): 데모 확인서(봉인된 것)에 사례별 흐름을 돌린다 — {@code TOUCH_PAD}(고객 터치 → 설계사 → 관리자, 같은 프로세스에서
 * 완료), {@code PAPER_SCAN}(종이 스캔 → 설계사 → 관리자 확인이 검토를 해소하며 완료), {@code REMOTE_LINK}(원격 링크 발송까지 — 콘솔 통지가 찍은 토큰으로
 * 스크립트가 고객 본인확인·서명, 설계사·관리자 서명을 CLI로 잇는다). 흐름 이름은 데모 파일의 어휘이고 운영 규칙이 아니다. 이미 받은 서명은 건너뛰고
 * 완료된 확인서는 NOOP이다(두 번째 실행 NOOP). 스트로크·이미지·스캔·기기 정보는 파일(허구)이다.
 */
final class DemoSignatureSeeder {

    private final WorkflowTransactions transactions;
    private final DisclosureLookup lookup;
    private final CustomerVault customers;
    private final SignSessionService sessions;
    private final SignService signing;
    private final SignatureStore signatures;
    private final DisclosureFlagPort flags;
    private final PrintStream out;

    DemoSignatureSeeder(WorkflowTransactions transactions, DisclosureLookup lookup, CustomerVault customers, SignSessionService sessions,
                        SignService signing, SignatureStore signatures, DisclosureFlagPort flags, PrintStream out) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.customers = Objects.requireNonNull(customers, "customers");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.signing = Objects.requireNonNull(signing, "signing");
        this.signatures = Objects.requireNonNull(signatures, "signatures");
        this.flags = Objects.requireNonNull(flags, "flags");
        this.out = Objects.requireNonNull(out, "out");
    }

    private record DemoInputs(byte[] strokes, byte[] image, byte[] scan, DeviceInfo device) {
    }

    void seed(TenantId tenant, Actor agent, Actor manager, String json, Path baseDir) {
        JsonNode root = Canonicalizer.parseStrict(json);
        if (root.path("schemaVersion").asInt(-1) != 1) {
            throw new CliFailure("demo signatures file needs schemaVersion 1");
        }
        JsonNode files = root.path("files");
        JsonNode device = Canonicalizer.parseStrict(new String(read(baseDir.resolve(files.path("device").asString())),
                java.nio.charset.StandardCharsets.UTF_8));
        DemoInputs f = new DemoInputs(read(baseDir.resolve(files.path("strokes").asString())), read(baseDir.resolve(files.path("image").asString())),
                read(baseDir.resolve(files.path("scan").asString())), DeviceInfo.fromJson(device));
        String prefix = root.path("customerKeyPrefix").asString();
        for (JsonNode c : root.path("cases")) {
            runCase(tenant, agent, manager, prefix, c, f);
        }
    }

    private void runCase(TenantId tenant, Actor agent, Actor manager, String prefix, JsonNode c, DemoInputs f) {
        String name = c.get("id").asString();
        RegistrationKey key = new RegistrationKey(prefix + c.get("customer").asString());
        CustomerRef customer = transactions.inTenant(tenant, () -> customers.findByRegistrationKey(key))
                .orElseThrow(() -> new CliFailure("case " + name + ": customer " + key + " is not registered — run customer import first"));
        GroupCode group = GroupCode.of(c.get("groupCode").asString());
        LocalDate consult = LocalDate.parse(c.get("consultDate").asString());
        DisclosureLookup.Summary target = transactions.inTenant(tenant, () -> lookup.summariesFor(customer, consult, group)).stream()
                .filter(s -> s.status() == DisclosureStatus.SEALED || s.status() == DisclosureStatus.PARTIALLY_SIGNED
                        || s.status() == DisclosureStatus.COMPLETED)
                .max(Comparator.comparingInt(DisclosureLookup.Summary::version))
                .orElseThrow(() -> new CliFailure("case " + name + " has no sealed disclosure — run demo disclosures first"));
        DisclosureId id = target.id();
        String head = "DEMO_SIGN " + tenant + " " + name + " id=" + id;
        if (target.status() == DisclosureStatus.COMPLETED) {
            out.println(head + " NOOP (COMPLETED)");
            return;
        }
        String flow = c.get("flow").asString();
        Set<SignerRole> signed = signed(tenant, id);
        if (!signed.contains(SignerRole.CUSTOMER)) {
            switch (flow) {
                case "TOUCH_PAD" -> {
                    String token = issue(tenant, agent, id, SignatureChannel.TOUCH_PAD);
                    sessions.recordView(token, true, 60);
                    sessions.confirmFaceToFace(Caller.cli(tenant, agent.subject()), token);
                    accepted(name, "customer", signing.capture(token, new SignatureCapture(f.strokes(), f.image(), f.device(), null)));
                }
                case "PAPER_SCAN" -> {
                    String token = issue(tenant, agent, id, SignatureChannel.PAPER_SCAN);
                    sessions.confirmFaceToFace(Caller.cli(tenant, agent.subject()), token);
                    DisclosureLookup.Footnote footnote = transactions.inTenant(tenant, () -> lookup.footnote(id)).orElseThrow();
                    accepted(name, "scan", signing.uploadPaperScan(Caller.cli(tenant, agent.subject()), token, new PaperScan(f.scan(), footnote.disclosureNo(),
                            footnote.canonicalHash().substring(0, PaperScan.HASH_PREFIX_LENGTH))));
                }
                case "REMOTE_LINK" -> {
                    SignSessionService.IssueOutcome o = sessions.issue(Caller.cli(tenant, agent.subject()), id, SignatureChannel.REMOTE_LINK);
                    if (!o.issued()) {
                        throw new CliFailure("case " + name + " session rejected: " + o.rejections());
                    }
                    out.println(head + " REMOTE_LINK sent=" + o.sent() + " — continue with: sign verify / sign capture (token from the SIGN LINK line),"
                            + " then sign agent / sign manager");
                    return;
                }
                default -> throw new CliFailure("case " + name + ": unknown demo flow " + flow);
            }
        } else if (flow.equals("REMOTE_LINK") && !signed.contains(SignerRole.AGENT)) {
            out.println(head + " REMOTE_LINK customer signed — continue with sign agent / sign manager");
            return;
        }
        if (!signed(tenant, id).contains(SignerRole.AGENT)) {
            accepted(name, "agent", signing.agentSign(Caller.cli(tenant, agent.subject()), id, new SignatureCapture(f.strokes(), f.image(), null, null)));
        }
        SignService.Outcome last = null;
        if (!signed(tenant, id).contains(SignerRole.MANAGER)) {
            Set<UUID> all = transactions.inTenant(tenant, () -> flags.allFor(id)).stream().map(DisclosureFlagPort.FlagSummary::flagId)
                    .collect(Collectors.toSet());
            last = accepted(name, "manager", signing.managerConfirm(Caller.cli(tenant, manager.subject()), id, all));
        }
        out.println(head + " " + flow + " -> " + (last == null ? "PARTIALLY_SIGNED" : last.status()));
    }

    private String issue(TenantId tenant, Actor agent, DisclosureId id, SignatureChannel channel) {
        SignSessionService.IssueOutcome o = sessions.issue(Caller.cli(tenant, agent.subject()), id, channel);
        if (!o.issued()) {
            throw new CliFailure("session for " + id + " rejected: " + o.rejections());
        }
        return o.token().orElseThrow().reveal();
    }

    private Set<SignerRole> signed(TenantId tenant, DisclosureId id) {
        Set<SignerRole> roles = EnumSet.noneOf(SignerRole.class);
        List<StoredSignature> all = transactions.inTenant(tenant, () -> signatures.signatures(id));
        all.forEach(s -> roles.add(s.role()));
        return roles;
    }

    private static SignService.Outcome accepted(String name, String step, SignService.Outcome o) {
        if (!o.accepted()) {
            throw new CliFailure("case " + name + " " + step + " rejected: " + o.rejections());
        }
        return o;
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
