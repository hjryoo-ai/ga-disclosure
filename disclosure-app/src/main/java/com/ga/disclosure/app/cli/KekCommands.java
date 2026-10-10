package com.ga.disclosure.app.cli;

import com.ga.disclosure.infra.crypto.TenantKeyProvider;
import com.ga.disclosure.infra.secret.FileSecretSource;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.StandardJobs;
import com.ga.disclosure.workflow.kek.TenantKekService;
import com.ga.disclosure.workflow.secret.SecretName;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintStream;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 테넌트 KEK CLI(Phase 8, 8 계획 승인 Q2 — 런북 "KEK 회전"). 운영자 대리 실행이다.
 * <pre>
 * crypto kek init     --tenant T --kek-id T-KEK-1 --secrets-dir &lt;dir&gt; [--if-absent yes]  (로컬·kind 전용: 비밀 파일 kek/T/T-KEK-1을 소유자 전용으로 —
 *                     덮어쓰지 않는다. --if-absent yes면 이미 있을 때 그대로 둔다. DB가 필요 없어 {@link OfflineCli}가 앱 컨텍스트 없이 실행한다)
 * crypto kek register --tenant T --kek-id T-KEK-1 --operator &lt;id&gt;       (키가 비밀 출처에서 감싸기·풀기 되는지 확인 → CURRENT 교체, 감사.
 *                     이미 CURRENT면 NOOP)
 * crypto kek rewrap   --tenants all|T1,T2 [--apply yes] --operator &lt;id&gt;     (작업 KEK_REWRAP — 기본 dry-run, 행마다 감사)
 * </pre>
 * 키 바이트는 출력하지 않는다.
 */
final class KekCommands {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantKekService keks;
    private final JobCommands jobs;
    private final Function<String, List<TenantId>> tenants;
    private final PrintStream out;

    KekCommands(TenantKekService keks, JobCommands jobs, Function<String, List<TenantId>> tenants, PrintStream out) {
        this.keks = Objects.requireNonNull(keks, "keks");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.out = Objects.requireNonNull(out, "out");
    }

    boolean handles(String command) {
        return command.startsWith("crypto kek ");
    }

    void run(CliArguments args) {
        switch (args.command()) {
            case "crypto kek register" -> register(args);
            case "crypto kek rewrap" -> rewrap(args);
            default -> throw new CliFailure("unknown command '" + args.command() + "' — see KekCommands javadoc");
        }
    }

    /** {@link OfflineCli}가 부른다(앱 컨텍스트·DB 없음). */
    static void init(CliArguments args, PrintStream out) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        String kekId = args.required("kek-id");
        SecretName name = TenantKeyProvider.secretName(tenant, kekId)
                .orElseThrow(() -> new CliFailure("KEK id must be " + tenant + "-KEK-<n>"));
        Path dir = Path.of(args.required("secrets-dir"));
        if (args.optional("if-absent").map("yes"::equals).orElse(false) && java.nio.file.Files.exists(dir.resolve(name.value()))) {
            out.println("KEK_INIT " + tenant + " " + kekId + " EXISTS");
            return;
        }
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        try {
            FileSecretSource.create(dir, name, Base64.getEncoder().encode(key));
        } catch (IllegalStateException e) {
            throw new CliFailure(e.getMessage());
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        out.println("KEK_INIT " + tenant + " " + kekId + " (owner read/write only; keep it outside the repository)");
    }

    private void register(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        String kekId = args.required("kek-id");
        boolean changed = keks.register(Caller.cli(tenant, args.required("operator")), kekId);
        out.println("KEK_REGISTER " + tenant + " " + kekId + (changed ? " CURRENT" : " NOOP"));
    }

    private void rewrap(CliArguments args) {
        boolean apply = args.optional("apply").map(v -> v.equals("yes")).orElse(false);
        String operator = args.required("operator");
        for (TenantId tenant : tenants.apply(args.required("tenants"))) {
            jobs.one(Caller.cli(tenant, operator), JobKind.KEK_REWRAP, JSON.createObjectNode().put("apply", apply), StandardJobs.kekRewrap(keks, apply))
                    .ifPresent(r -> out.println("KEK_REWRAP " + tenant + " to=" + r.toKekId() + (apply ? " APPLY" : " DRY_RUN")
                            + " rewrapped=" + r.count(TenantKekService.Outcome.REWRAPPED) + " pending=" + r.count(TenantKekService.Outcome.PENDING)
                            + " skipped=" + r.count(TenantKekService.Outcome.SKIPPED) + " failed=" + r.count(TenantKekService.Outcome.FAILED)));
        }
        jobs.failIfIncomplete();
    }
}
