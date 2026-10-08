package com.ga.disclosure.app.cli;

import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.sign.token.SignToken;
import com.ga.disclosure.sign.token.SignTokenRejected;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.workflow.disclosure.ExpireService;
import com.ga.disclosure.workflow.disclosure.NotificationDispatcher;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.disclosure.SignSessionService;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.StandardJobs;
import com.ga.disclosure.workflow.sign.DeviceInfo;
import com.ga.disclosure.workflow.sign.IdentityInputs;
import com.ga.disclosure.workflow.sign.PaperScan;
import com.ga.disclosure.workflow.sign.SignatureCapture;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * 서명 CLI(Phase 4, 4 계획 §7.6 — 6A부터 HTTP와 같은 유스케이스 진입점을 {@code Caller.cli}로 부른다). 본인확인 입력·스트로크·이미지·기기 정보는 <b>파일로만</b> 받는다(허구 데이터, CLAUDE.md 규칙 6
 * — 생년월일이 셸 기록·프로세스 목록에 남지 않게). 토큰은 개인정보가 아니라 인자로 받는다. 출력에는 입력값이 없고, TOUCH_PAD·PAPER_SCAN 세션의 토큰은
 * 발급 응답 한 줄에만 나온다(설계사 기기 역할). 업무 거부·토큰 거부는 종료 코드 2.
 *
 * <pre>
 * sign session     --tenant T --id &lt;uuid&gt; --channel TOUCH_PAD|REMOTE_LINK|PAPER_SCAN --operator &lt;agent&gt;
 * sign open        --token &lt;token&gt; [--out &lt;pdf path&gt;] [--view-file &lt;{"scrollComplete":true,"viewSeconds":42}&gt;]
 * sign verify      --token &lt;token&gt; [--inputs-file &lt;{"birthDate":"…"}&gt;] [--face-to-face --operator &lt;agent&gt;]
 * sign capture     --token &lt;token&gt; --strokes-file &lt;json&gt; --image-file &lt;png&gt; [--device-file &lt;json&gt;] [--ip &lt;addr&gt;]
 * sign scan        --token &lt;token&gt; --image-file &lt;png|jpg&gt; --entered-no &lt;no&gt; --entered-hash-prefix &lt;12 hex&gt; --operator &lt;agent&gt;
 * sign agent       --tenant T --id &lt;uuid&gt; --operator &lt;agent&gt; [--strokes-file &lt;json&gt; --image-file &lt;png&gt;]
 * sign manager     --tenant T --id &lt;uuid&gt; --operator &lt;manager&gt; --ack all|&lt;flag uuid,…&gt;
 * sign review-scan --tenant T --id &lt;uuid&gt; --operator &lt;exceptionApproval.role 연결 주체&gt;
 * disclosure complete --tenant T --id &lt;uuid&gt; --operator &lt;id&gt;
 * disclosure expire   [--tenants all|T1,T2] [--as-of &lt;instant&gt;|&lt;ISO duration from now, e.g. P30D&gt;] [--limit 500] --operator &lt;id&gt;
 * notify dispatch     [--tenants all|T1,T2] [--limit 100] --operator &lt;id&gt;   (6A — 원격 링크 통지 아웃박스 발송, 작업 NOTIFY)
 * </pre>
 * 원격 링크 세션은 발급 때 토큰이 없고 통지 아웃박스에 적재된다({@code queued=}) — 링크는 {@code notify dispatch}가 보낸다(6A 계획 §7).
 * {@code --face-to-face}는 값이 있는 옵션 형식({@code --face-to-face yes})이다.
 */
final class SignCommands {

    private final SignSessionService sessions;
    private final SignService signing;
    private final ExpireService expiry;
    private final NotificationDispatcher dispatcher;
    private final JobCommands jobs;
    private final DisclosureFlagPort flags;
    private final WorkflowTransactions transactions;
    private final Function<String, List<TenantId>> tenants;
    private final Clock clock;
    private final PrintStream out;

    SignCommands(SignSessionService sessions, SignService signing, ExpireService expiry, NotificationDispatcher dispatcher, JobCommands jobs,
                 DisclosureFlagPort flags,
                 WorkflowTransactions transactions,
                 Function<String, List<TenantId>> tenants, Clock clock, PrintStream out) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.signing = Objects.requireNonNull(signing, "signing");
        this.expiry = Objects.requireNonNull(expiry, "expiry");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.flags = Objects.requireNonNull(flags, "flags");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.out = Objects.requireNonNull(out, "out");
    }

    boolean handles(String command) {
        return command.startsWith("sign ") || command.equals("disclosure complete") || command.equals("disclosure expire")
                || command.equals("notify dispatch");
    }

    void run(CliArguments args) {
        try {
            switch (args.command()) {
                case "sign session" -> session(args);
                case "sign open" -> open(args);
                case "sign verify" -> verify(args);
                case "sign capture" -> capture(args);
                case "sign scan" -> scan(args);
                case "sign agent" -> agent(args);
                case "sign manager" -> manager(args);
                case "sign review-scan" -> reviewScan(args);
                case "disclosure complete" -> complete(args);
                case "disclosure expire" -> expire(args);
                case "notify dispatch" -> dispatch(args);
                default -> throw new CliFailure("unknown command '" + args.command() + "' — see SignCommands javadoc");
            }
        } catch (SignTokenRejected e) {
            out.println(args.command().toUpperCase(java.util.Locale.ROOT).replace(' ', '_') + " TOKEN_REJECTED");
            throw new CliRejection("sign token rejected");
        }
    }

    private void session(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        SignatureChannel channel = SignatureChannel.valueOf(args.required("channel"));
        SignSessionService.IssueOutcome o = sessions.issue(caller(args, tenant), id(args), channel);
        if (!o.issued()) {
            rejected("SIGN_SESSION " + tenant + " " + o.id(), o.rejections());
        }
        out.println("SIGN_SESSION " + tenant + " " + o.id() + " session=" + o.sessionId().orElseThrow() + " channel=" + channel + " expires="
                + o.expiresAt().orElseThrow() + (channel == SignatureChannel.REMOTE_LINK ? " queued=" + o.notificationId().orElseThrow()
                + " (sent by notify dispatch)" : " token=" + o.token().orElseThrow().reveal()));
    }

    private void open(CliArguments args) {
        String token = args.required("token");
        byte[] pdf = sessions.open(token);
        args.optional("out").ifPresent(path -> write(Path.of(path), pdf));
        args.optional("view-file").ifPresent(path -> {
            JsonNode view = json(Path.of(path));
            sessions.recordView(token, view.path("scrollComplete").asBoolean(false), view.path("viewSeconds").asInt(0));
        });
        out.println("SIGN_OPEN bytes=" + pdf.length + args.optional("out").map(p -> " -> " + p).orElse("")
                + (args.optional("view-file").isPresent() ? " view=RECORDED" : ""));
    }

    private void verify(CliArguments args) {
        String token = args.required("token");
        if (args.optional("face-to-face").isPresent()) {
            SignSessionService.IdentityOutcome o = sessions.confirmFaceToFace(caller(args, SignToken.parse(token).tenant()), token);
            if (!o.rejections().isEmpty()) {
                rejected("SIGN_VERIFY", o.rejections());
            }
            identity(o);
        }
        if (args.optional("inputs-file").isPresent()) {
            JsonNode inputs = json(Path.of(args.required("inputs-file")));
            JsonNode birth = inputs.get("birthDate");
            identity(sessions.verify(token, birth == null || birth.isNull() ? IdentityInputs.none() : IdentityInputs.birthDate(birth.asString())));
        }
    }

    private void identity(SignSessionService.IdentityOutcome o) {
        out.println("SIGN_VERIFY session=" + o.sessionId() + " results=" + o.results().stream().map(r -> r.method() + ":" + (r.passed() ? "PASS" : "FAIL"))
                .toList() + " missing=" + o.missing() + " failures=" + o.failures() + (o.revoked() ? " REVOKED" : ""));
    }

    private void capture(CliArguments args) {
        JsonNode device = args.optional("device-file").map(p -> json(Path.of(p))).orElse(null);
        SignatureCapture capture = new SignatureCapture(bytes(args.required("strokes-file")), bytes(args.required("image-file")),
                device == null ? null : DeviceInfo.fromJson(device), args.optional("ip").orElse(null));
        result("SIGN_CAPTURE", signing.capture(args.required("token"), capture));
    }

    private void scan(CliArguments args) {
        PaperScan scan = new PaperScan(bytes(args.required("image-file")), args.required("entered-no"), args.required("entered-hash-prefix"));
        result("SIGN_SCAN", signing.uploadPaperScan(caller(args, SignToken.parse(args.required("token")).tenant()), args.required("token"), scan));
    }

    private void agent(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        SignatureCapture drawn = args.optional("strokes-file").isEmpty() ? null
                : new SignatureCapture(bytes(args.required("strokes-file")), bytes(args.required("image-file")), null, null);
        result("SIGN_AGENT", signing.agentSign(caller(args, tenant), id(args), drawn));
    }

    private void manager(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        DisclosureId id = id(args);
        String ack = args.required("ack");
        Set<UUID> acknowledged = new LinkedHashSet<>();
        if (ack.equals("all")) {
            transactions.inTenant(tenant, () -> flags.allFor(id)).forEach(f -> acknowledged.add(f.flagId()));
            out.println("SIGN_MANAGER acknowledging " + acknowledged.size() + " flag(s): " + acknowledged);
        } else {
            Arrays.stream(ack.split(",")).map(String::strip).filter(s -> !s.isEmpty()).map(UUID::fromString).forEach(acknowledged::add);
        }
        result("SIGN_MANAGER", signing.managerConfirm(caller(args, tenant), id, acknowledged));
    }

    private void reviewScan(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        result("SIGN_REVIEW_SCAN", signing.reviewPaperScan(caller(args, tenant), id(args)));
    }

    private void complete(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        result("COMPLETE", signing.complete(caller(args, tenant), id(args)));
    }

    private void expire(CliArguments args) {
        Instant asOf = asOf(args.optional("as-of").orElse(null));
        int limit = Integer.parseInt(args.optional("limit").orElse("500"));
        ObjectNode params = JsonMapper.builder().build().createObjectNode().put("asOf", asOf.toString()).put("limit", limit);
        for (TenantId tenant : tenants.apply(args.optional("tenants").orElse("all"))) {
            Optional<ExpireService.Report> ran = jobs.one(caller(args, tenant), JobKind.EXPIRE, params, StandardJobs.expire(expiry, asOf, limit));
            if (ran.isEmpty()) {
                continue;
            }
            ExpireService.Report r = ran.get();
            out.println("EXPIRE " + tenant + " asOf=" + asOf + " expired=" + r.expired().size() + " stillOpen=" + r.stillOpen() + " sessionsExpired="
                    + r.sessionsExpired() + (r.expired().isEmpty() ? " NOOP" : " " + r.expired()));
        }
        jobs.failIfIncomplete();
    }

    /** 원격 링크 통지 발송(작업 NOTIFY). 링크는 통지 어댑터(콘솔 구현은 {@code SIGN LINK} 줄)로만 나가고 이 출력에는 통지 ID뿐이다. */
    private void dispatch(CliArguments args) {
        int limit = Integer.parseInt(args.optional("limit").orElse(String.valueOf(StandardJobs.DEFAULT_NOTIFY_LIMIT)));
        ObjectNode params = JsonMapper.builder().build().createObjectNode().put("limit", limit);
        for (TenantId tenant : tenants.apply(args.optional("tenants").orElse("all"))) {
            jobs.one(caller(args, tenant), JobKind.NOTIFY, params, StandardJobs.notify(dispatcher, limit)).ifPresent(r -> out.println("NOTIFY "
                    + tenant + " sent=" + r.sent().size() + " retried=" + r.retried().size() + " dead=" + r.dead().size() + " cancelled="
                    + r.cancelled().size() + " skipped=" + r.skipped()));
        }
        jobs.failIfIncomplete();
    }

    /** 판정 시각: 없으면 시계, ISO 순간이면 그대로, ISO 기간(P30D)이면 시계 + 기간(데모·점검용 — 운영 배치는 시계). */
    private Instant asOf(String spec) {
        if (spec == null) {
            return clock.instant();
        }
        if (spec.startsWith("P") || spec.startsWith("-P")) {
            return clock.instant().plus(Duration.parse(spec));
        }
        return Instant.parse(spec);
    }

    private void result(String label, SignService.Outcome o) {
        if (!o.accepted()) {
            rejected(label + " " + o.id(), o.rejections());
        }
        out.println(label + " " + o.id() + " " + o.status() + o.signatureId().map(s -> " signature=" + s).orElse("")
                + (o.retentionPending() ? " RETENTION_PENDING (run artifacts reconcile)" : ""));
    }

    private void rejected(String prefix, List<?> codes) {
        out.println(prefix + " REJECTED " + codes);
        throw new CliRejection("rejected: " + codes);
    }

    /**
     * 운영자 CLI 호출자: 감사 역할은 언제나 OPERATOR다(6A 승인 Q9 — {@code --role}은 폐기). 설계사·관리자 연결을 요구하는 업무 검사(담당 설계사,
     * 예외 승인 역할)는 {@code --operator}로 준 주체의 {@code identity_link}로 본다(대리 실행).
     */
    private static Caller caller(CliArguments args, TenantId tenant) {
        return Caller.cli(tenant, args.required("operator"));
    }

    private static DisclosureId id(CliArguments args) {
        return DisclosureId.parse(args.required("id"));
    }

    private static JsonNode json(Path path) {
        return Canonicalizer.parseStrict(new String(read(path), StandardCharsets.UTF_8));
    }

    private static byte[] bytes(String path) {
        return read(Path.of(path));
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path path, byte[] bytes) {
        try {
            Files.write(path, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
