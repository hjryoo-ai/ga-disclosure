package com.ga.disclosure.app.cli;

import com.ga.disclosure.app.demo.DemoOidcIssuer;
import com.ga.disclosure.compliance.rules.ActivationReport;
import com.ga.disclosure.compliance.rules.DistributionOutcome;
import com.ga.disclosure.compliance.rules.GovernanceRejectedException;
import com.ga.disclosure.compliance.rules.Operator;
import com.ga.disclosure.compliance.rules.ReconcileReport;
import com.ga.disclosure.compliance.rules.RuleActivationJob;
import com.ga.disclosure.compliance.rules.RuleApprovalService;
import com.ga.disclosure.compliance.rules.RuleBundleReconciler;
import com.ga.disclosure.compliance.rules.RuleDistributionService;
import com.ga.disclosure.compliance.rules.RuleVersionStore;
import com.ga.disclosure.compliance.rules.TenantDirectory;
import com.ga.disclosure.compliance.rules.TenantTransactions;
import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.infra.crypto.LocalFileKeyProvider;
import com.ga.disclosure.infra.persistence.IdentityLinkRepository;
import com.ga.disclosure.infra.persistence.TenantRepository;
import com.ga.disclosure.rules.bundle.Bundle;
import com.ga.disclosure.rules.bundle.BundleLoader;
import com.ga.disclosure.rules.version.RuleVersion;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.anchor.AnchorJob;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.catalog.CatalogImportOutcome;
import com.ga.disclosure.workflow.catalog.CatalogImportRejectedException;
import com.ga.disclosure.workflow.catalog.CatalogImportService;
import com.ga.disclosure.workflow.catalog.InvalidCatalogFileException;
import com.ga.disclosure.workflow.customer.CustomerFileParser;
import com.ga.disclosure.workflow.customer.CustomerRekeyService;
import com.ga.disclosure.workflow.customer.CustomerVault;
import com.ga.disclosure.workflow.customer.RegisterCustomer;
import com.ga.disclosure.workflow.customer.RekeyReport;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.workflow.disclosure.DisclosureLookup;
import com.ga.disclosure.workflow.disclosure.DisclosureService;
import com.ga.disclosure.workflow.disclosure.ExpireService;
import com.ga.disclosure.workflow.disclosure.LifecycleReason;
import com.ga.disclosure.workflow.disclosure.LifecycleService;
import com.ga.disclosure.workflow.disclosure.NotificationDispatcher;
import com.ga.disclosure.workflow.disclosure.SealService;
import com.ga.disclosure.workflow.disclosure.SignService;
import com.ga.disclosure.workflow.disclosure.SignSessionService;
import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.JobQueryService;
import com.ga.disclosure.workflow.job.JobRunner;
import com.ga.disclosure.workflow.job.StandardJobs;
import com.ga.disclosure.workflow.retention.DestructionJob;
import com.ga.disclosure.workflow.retention.LegalHoldService;
import com.ga.disclosure.workflow.sign.SignatureStore;
import com.ga.disclosure.workflow.verify.ReceiptExporter;
import com.ga.disclosure.workflow.verify.TenantVerifier;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 운영자 CLI(프로파일 {@code cli}, 웹 서버 없이 실행 후 종료). 호출자는 {@code Caller.cli(테넌트, --operator)} — 감사 역할은 언제나
 * OPERATOR이고 범위 검사가 없다(6A 승인 Q9, {@code --role}은 거부). 업무 역할을 요구하는 명령(정정·스캔 검토·설계사 서명 등)은
 * {@code --operator}가 그 역할로 {@code identity_link}에 연결된 주체여야 한다. 실패(인자 오류·거부)는 예외로 전파되어 종료 코드가 0이 아니다.
 *
 * <pre>
 * rules distribute --bundle &lt;path&gt; --tenants all|T1,T2 --operator &lt;id&gt; [--bundles-dir contracts/rules/bundles]
 * rules approve    --tenant T1 --rule &lt;id&gt; --operator &lt;id&gt;
 * rules activate   [--as-of 2027-01-01] [--tenants all|T1,T2] --operator &lt;id&gt;
 * rules reconcile  [--tenants all|T1,T2] [--bundles-dir contracts/rules/bundles[,dir2]] --operator &lt;id&gt;
 * demo seed        --file &lt;seed.json&gt; --operator &lt;id&gt;
 * demo token       --tenant T1 --subject &lt;sub&gt; [--ttl PT15M] (데모 프로파일만 — JWT를 표준 출력으로, 역할 클레임 없음)
 * catalog import   --tenant T1 --file &lt;catalog.json&gt; --operator &lt;id&gt;
 * customer rekey   --tenant T1 --operator &lt;id&gt; [--batch 500]
 * customer import  --tenant T1 --file &lt;customers.json&gt; --operator &lt;id&gt;
 * demo disclosures --tenant T1 --file &lt;disclosures.json&gt; --operator &lt;id&gt; [--agent demo-agent]
 * crypto init-kek  --file &lt;path outside the repo&gt; [--kek-id KEK-LOCAL-1]
 * disclosure seal       --tenant T1 --id &lt;uuid&gt; --operator &lt;id&gt; (거부면 종료 코드 2와 거부 코드 목록)
 * disclosure void       --tenant T1 --id &lt;uuid&gt; --reason-code &lt;CODE&gt; [--reason-file &lt;path&gt;] --operator &lt;id&gt;
 * disclosure supersede  --tenant T1 --id &lt;uuid&gt; --reason-code &lt;CODE&gt; [--reason-file &lt;path&gt;] --operator &lt;id&gt;
 * disclosure rebase     --tenant T1 --id &lt;uuid&gt; --operator &lt;id&gt;
 * artifacts get         --tenant T1 --id &lt;uuid&gt; --kind PDF|CANONICAL_JSON|SIGNED_PDF|EVIDENCE_ZIP --out &lt;path&gt; --operator &lt;id&gt;
 * artifacts gc          --tenants all|T1,T2 [--grace PT24H] --operator &lt;id&gt;
 * artifacts reconcile   --tenants all|T1,T2 [--limit 500] --operator &lt;id&gt;
 * demo signatures  --tenant T1 --file &lt;signatures.json&gt; [--agent demo-agent] [--manager demo-manager]
 * sign …·disclosure complete|expire — {@link SignCommands}(Phase 4)
 * anchor run|receipt export·verify package|tenant·retention destroy·legal-hold place|release — {@link RetentionCommands}(Phase 5)
 * jobs list|show|report — {@link JobCommands}(6A — 배치 명령은 전부 작업 실행기를 지난다)
 * contract-links import|purge-unmatched — {@link ContractLinkCommands}(6B) · drafts abandon|abandon-idle — {@link DraftCommands}(6B)
 * collection-rates snapshot|list — {@link CollectionRateCommands}(6B, 내부 지표 — 규제 정의 없음)
 * </pre>
 * 무효·정정 사유는 파일로만 받는다 — 자유 텍스트에 개인정보가 섞일 수 있다(CLAUDE.md 규칙 6). 출력에는 사유를 싣지 않는다.
 * 고객 개인정보는 CLI 인자·환경변수로 받지 않는다(셸 기록·프로세스 목록에 남는다) — 파일(허구 데이터) 또는 API로만(CLAUDE.md 규칙 6).
 * {@code customer import}의 출력에는 고객 참조·등록 결과만 있고 이름·연락처는 없다.
 */
@Component
@Profile("cli")
public class OperatorCli implements ApplicationRunner {

    static final String DEFAULT_BUNDLES_DIR = "contracts/rules/bundles";

    private final RuleDistributionService distribution;
    private final RuleApprovalService approval;
    private final RuleActivationJob activation;
    private final RuleBundleReconciler reconciler;
    private final TenantDirectory directory;
    private final TenantTransactions transactions;
    private final TenantRepository tenants;
    private final RuleVersionStore rules;
    private final CatalogImportService catalog;
    private final CustomerRekeyService rekey;
    private final RegisterCustomer registerCustomer;
    private final DisclosureService disclosures;
    private final DisclosureLookup lookup;
    private final CustomerVault customers;
    private final SealService seal;
    private final LifecycleService lifecycle;
    private final ArtifactService artifacts;
    private final IdentityLinkRepository identityLinks;
    private final SignCommands sign;
    private final DemoSignatureSeeder demoSignatures;
    private final ContractLinkCommands contractLinks;
    private final DraftCommands drafts;
    private final CollectionRateCommands collectionRates;
    private final RetentionCommands retention;
    private final JobCommands jobs;
    private final NotificationDispatcher dispatcher;
    private final ObjectProvider<DemoOidcIssuer> demoOidc;
    private final PrintStream out = System.out;

    public OperatorCli(RuleDistributionService distribution, RuleApprovalService approval, RuleActivationJob activation,
                       RuleBundleReconciler reconciler, TenantDirectory directory, TenantTransactions transactions,
                       TenantRepository tenants, RuleVersionStore rules, CatalogImportService catalog, CustomerRekeyService rekey,
                       RegisterCustomer registerCustomer, DisclosureService disclosures, DisclosureLookup lookup, CustomerVault customers,
                       SealService seal, LifecycleService lifecycle, ArtifactService artifacts, IdentityLinkRepository identityLinks,
                       SignSessionService signSessions, SignService signing, ExpireService expiry, DisclosureFlagPort flags,
                       WorkflowTransactions workflowTransactions, SignatureStore signatures, Clock clock, AnchorJob anchorJob,
                       ReceiptExporter receiptExporter, TenantVerifier tenantVerifier, DestructionJob destructionJob, LegalHoldService legalHolds,
                       JobRunner jobRunner, JobQueryService jobQueries, NotificationDispatcher notifications,
                       ObjectProvider<DemoOidcIssuer> demoOidc, com.ga.disclosure.workflow.contract.ContractLinkService contractLinks,
                       com.ga.disclosure.workflow.disclosure.DraftAbandonService draftAbandon,
                       com.ga.disclosure.workflow.rate.CollectionRateService collectionRates) {
        this.distribution = distribution;
        this.approval = approval;
        this.activation = activation;
        this.reconciler = reconciler;
        this.directory = directory;
        this.transactions = transactions;
        this.tenants = tenants;
        this.rules = rules;
        this.catalog = catalog;
        this.rekey = rekey;
        this.registerCustomer = registerCustomer;
        this.disclosures = disclosures;
        this.lookup = lookup;
        this.customers = customers;
        this.seal = seal;
        this.lifecycle = lifecycle;
        this.artifacts = artifacts;
        this.identityLinks = identityLinks;
        this.jobs = new JobCommands(jobRunner, jobQueries, out);
        this.dispatcher = notifications;
        this.demoOidc = demoOidc;
        this.sign = new SignCommands(signSessions, signing, expiry, notifications, jobs, flags, workflowTransactions, this::tenants, clock, out);
        this.demoSignatures = new DemoSignatureSeeder(workflowTransactions, lookup, customers, signSessions, signing, signatures, flags, out);
        this.retention = new RetentionCommands(anchorJob, receiptExporter, tenantVerifier, destructionJob, legalHolds, jobs, this::tenants, clock,
                out);
        this.contractLinks = new ContractLinkCommands(contractLinks, jobs, out);
        this.drafts = new DraftCommands(draftAbandon, jobs, out);
        this.collectionRates = new CollectionRateCommands(collectionRates, jobs, out);
    }

    @Override
    public void run(ApplicationArguments arguments) {
        CliArguments args = CliArguments.parse(arguments.getSourceArgs());
        if (sign.handles(args.command())) {
            sign.run(args);
            return;
        }
        if (retention.handles(args.command())) {
            retention.run(args);
            return;
        }
        if (jobs.handles(args.command())) {
            jobs.run(args);
            return;
        }
        if (drafts.handles(args.command())) {
            drafts.run(args);
            return;
        }
        if (collectionRates.handles(args.command())) {
            collectionRates.run(args);
            return;
        }
        if (contractLinks.handles(args.command())) {
            contractLinks.run(args);
            return;
        }
        switch (args.command()) {
            case "demo signatures" -> demoSignatures(args);
            case "rules distribute" -> distribute(args);
            case "rules approve" -> approve(args);
            case "rules activate" -> activate(args);
            case "rules reconcile" -> reconcile(args);
            case "demo seed" -> seed(args);
            case "demo token" -> demoToken(args);
            case "catalog import" -> importCatalog(args);
            case "customer rekey" -> rekey(args);
            case "customer import" -> importCustomers(args);
            case "demo disclosures" -> demoDisclosures(args);
            case "crypto init-kek" -> initKek(args);
            case "disclosure seal" -> sealDisclosure(args);
            case "disclosure void" -> voidDisclosure(args);
            case "disclosure supersede" -> supersede(args);
            case "disclosure rebase" -> rebase(args);
            case "artifacts get" -> artifactGet(args);
            case "artifacts gc" -> artifactGc(args);
            case "artifacts reconcile" -> artifactReconcile(args);
            default -> throw new CliFailure("unknown command '" + args.command() + "' — see OperatorCli javadoc");
        }
    }

    private void distribute(CliArguments args) {
        Operator operator = new Operator(args.required("operator"));
        Bundle bundle = loadBundle(args.required("bundle"), bundlesDir(args));
        List<DistributionOutcome> outcomes = distribution.distribute(bundle, tenants(args.required("tenants")), operator);
        outcomes.forEach(o -> out.println("DISTRIBUTE " + o.tenant() + " " + o.bundleId() + " " + o.result() + " " + o.message()));
        if (outcomes.stream().anyMatch(o -> o.result() == DistributionOutcome.Result.REJECTED)) {
            throw new CliFailure("distribution rejected for "
                    + outcomes.stream().filter(o -> o.result() == DistributionOutcome.Result.REJECTED).map(DistributionOutcome::tenant).toList());
        }
    }

    private void approve(CliArguments args) {
        Operator operator = new Operator(args.required("operator"));
        TenantId tenant = TenantId.of(args.required("tenant"));
        RuleVersionId rule = RuleVersionId.of(args.required("rule"));
        RuleStatus status;
        try {
            status = approval.approve(tenant, rule, operator);
        } catch (GovernanceRejectedException e) {
            throw new CliFailure("approval rejected: " + e.getMessage());
        }
        out.println("APPROVE " + tenant + " " + rule + (status == RuleStatus.APPROVED ? " APPROVED" : " ALREADY " + status));
    }

    private void activate(CliArguments args) {
        Operator operator = new Operator(args.required("operator"));
        for (TenantId tenant : tenants(args.optional("tenants").orElse("all"))) {
            ActivationReport r = args.optional("as-of")
                    .map(d -> activation.runAsOf(tenant, LocalDate.parse(d), operator))
                    .orElseGet(() -> activation.run(tenant, operator));
            out.println("ACTIVATE " + tenant + " asOf=" + r.asOf() + " retired=" + r.retired() + " activated=" + r.activated());
            r.missed().forEach(m -> out.println("  RULE_ACTIVATION_MISSED " + m.rule() + " flag=" + m.flagId()
                    + (m.flagCreated() ? " RAISED" : " ALREADY_OPEN")));
        }
    }

    private void reconcile(CliArguments args) {
        Operator operator = new Operator(args.required("operator"));
        List<Bundle> known = new ArrayList<>();
        bundlesDirs(args).forEach(dir -> known.addAll(allBundles(dir)));
        int drift = 0;
        for (TenantId tenant : tenants(args.optional("tenants").orElse("all"))) {
            ReconcileReport r = reconciler.reconcile(tenant, known, operator);
            drift += r.drifts().size();
            out.println("RECONCILE " + tenant + " checked=" + r.checked() + " drift=" + r.drifts().size());
            r.drifts().forEach(d -> out.println("  RULE_DRIFT " + d.targetKind() + " " + d.targetId() + " flag=" + d.flagId() + " " + d.problems()));
        }
        out.println("RECONCILE total drift=" + drift);
    }

    /** 데모 OIDC 토큰(6A 계획 §10): 데모 프로파일만. 출력은 JWT 한 줄뿐이다. */
    private void demoToken(CliArguments args) {
        DemoOidcIssuer issuer = demoOidc.getIfAvailable();
        if (issuer == null) {
            throw new CliFailure("demo token needs the demo profile (--spring.profiles.active=cli,demo)");
        }
        Duration ttl;
        try {
            ttl = Duration.parse(args.optional("ttl").orElse("PT15M"));
        } catch (java.time.format.DateTimeParseException e) {
            throw new CliFailure("--ttl is an ISO-8601 duration (e.g. PT15M)");
        }
        out.println(issuer.token(TenantId.of(args.required("tenant")), args.required("subject"), ttl));
    }

    /** 데모·시드: 테넌트 행과 DRAFT 사규를 넣는다(이미 있으면 건너뛴다). 사규 승인·활성화는 별도 명령으로. */
    private void seed(CliArguments args) {
        new Operator(args.required("operator"));
        JsonNode seed = Canonicalizer.parseStrict(read(Path.of(args.required("file"))));
        for (JsonNode t : seed.path("tenants")) {
            TenantId tenant = TenantId.of(t.get("tenantId").asString());
            int inserted = transactions.inTenant(tenant, () -> tenants.insertCurrentIfAbsent(t.get("name").asString(),
                    t.get("engineBaseUrl").asString(), t.get("largeGa").asBoolean()));
            out.println("SEED TENANT " + tenant + (inserted == 1 ? " CREATED" : " EXISTS"));
            for (JsonNode link : t.path("identityLinks")) {
                java.util.List<String> roles = new java.util.ArrayList<>();
                link.get("roles").forEach(r -> roles.add(r.asString()));
                // 설계사가 아닌 주체(COMPLIANCE·SCHEDULER·FEED_CONSUMER)는 agentId가 없고 서비스 주체는 조직도 없다(V12)
                String agentId = link.hasNonNull("agentId") ? link.get("agentId").asString() : null;
                String orgPath = link.hasNonNull("orgPath") ? link.get("orgPath").asString() : null;
                int linked = transactions.inTenant(tenant, () -> identityLinks.linkIfAbsent(link.get("subject").asString(), agentId, roles,
                        orgPath));
                out.println("SEED IDENTITY_LINK " + tenant + " " + (agentId != null ? agentId : link.get("subject").asString())
                        + (linked == 1 ? " CREATED" : " EXISTS"));
            }
        }
        for (JsonNode r : seed.path("tenantRules")) {
            TenantId tenant = TenantId.of(r.get("tenantId").asString());
            RuleVersionId id = RuleVersionId.of(r.get("ruleVersionId").asString());
            boolean created = transactions.inTenant(tenant, () -> {
                if (rules.find(id).isPresent()) {
                    return false;
                }
                rules.insert(new RuleVersion(id, RuleScope.TENANT, LocalDate.parse(r.get("applyFrom").asString()), null, RuleStatus.DRAFT,
                        null, null, r.get("body"), null, null));
                return true;
            });
            out.println("SEED TENANT_RULE " + tenant + " " + id + (created ? " DRAFT" : " EXISTS"));
        }
    }

    private void importCatalog(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        Path file = Path.of(args.required("file"));
        CatalogImportOutcome o;
        try {
            o = catalog.importFile(caller(args, tenant), file.getFileName().toString(), Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InvalidCatalogFileException | CatalogImportRejectedException e) {
            throw new CliFailure("catalog import rejected: " + e.getMessage());
        }
        out.println("CATALOG_IMPORT " + tenant + " " + o.kind() + " " + o.result() + " import=" + o.importId() + " inserted="
                + o.counts().inserted() + " updated=" + o.counts().updated() + " closed=" + o.counts().closed() + " unchanged="
                + o.counts().unchanged());
    }

    private void rekey(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        int batch = Integer.parseInt(args.optional("batch").orElse("500"));
        RekeyReport r = rekey.rekey(caller(args, tenant), batch);
        out.println("REKEY " + tenant + " retired=" + r.retiredKeyId().orElse("-") + " active=" + r.activeKeyId() + " reencrypted="
                + r.reencrypted() + " destroyed=" + r.destroyedKeyIds());
    }

    /** 고객 파일 등록(등록 멱등 키 — 두 번 돌려도 한 명). 개인정보는 파일에서만 읽고 출력하지 않는다. */
    private void importCustomers(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        Path file = Path.of(args.required("file"));
        List<CustomerFileParser.Row> rows;
        try {
            rows = CustomerFileParser.parse(file.getFileName().toString(), Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (IllegalArgumentException e) {
            throw new CliFailure(e.getMessage());
        }
        for (CustomerFileParser.Row row : rows) {
            RegisterCustomer.Registration r = registerCustomer.execute(caller(args, tenant), row.key(), row.customer());
            out.println("CUSTOMER_IMPORT " + tenant + " " + row.id() + " " + (r.created() ? "CREATED" : "NOOP") + " ref=" + r.ref());
        }
    }

    private void demoDisclosures(CliArguments args) {
        Actor agent = new Actor(args.optional("agent").orElse("demo-agent"), "AGENT");
        new Operator(args.required("operator"));
        TenantId tenant = TenantId.of(args.required("tenant"));
        Actor manager = new Actor(args.optional("manager").orElse("demo-manager"), "MANAGER");
        new DemoDisclosureSeeder(disclosures, lookup, customers, transactions, seal, lifecycle, out)
                .seed(tenant, agent, manager, read(Path.of(args.required("file"))));
    }

    private void initKek(CliArguments args) {
        Path file = Path.of(args.required("file"));
        String kekId = args.optional("kek-id").orElse("KEK-LOCAL-1");
        try {
            LocalFileKeyProvider.initialize(file, kekId);
        } catch (IllegalStateException e) {
            throw new CliFailure(e.getMessage());
        }
        out.println("KEK_INIT " + kekId + " " + file.toAbsolutePath() + " (owner read/write only; keep it outside the repository)");
    }

    /**
     * Phase 4 데모 서명(3자 터치·종이 스캔 완료, 원격 링크 발급 — 고객 경로는 스크립트가 콘솔 토큰으로 잇는다). 파일 경로는 서명 파일 기준. 6A: 원격 링크는
     * 아웃박스에 적재되므로 끝에 그 테넌트의 통지 발송(작업 NOTIFY)을 한 번 돌린다 — 콘솔 통지가 {@code SIGN LINK} 줄을 찍는다.
     */
    private void demoSignatures(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        Path file = Path.of(args.required("file"));
        demoSignatures.seed(tenant, new Actor(args.optional("agent").orElse("demo-agent"), "AGENT"),
                new Actor(args.optional("manager").orElse("demo-manager"), "MANAGER"), read(file), file.toAbsolutePath().getParent());
        int limit = StandardJobs.DEFAULT_NOTIFY_LIMIT;
        jobs.one(Caller.cli(tenant, args.optional("operator").orElse("demo-seeder")), JobKind.NOTIFY,
                tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode().put("limit", limit), StandardJobs.notify(dispatcher, limit))
                .ifPresent(r -> out.println("NOTIFY " + tenant + " sent=" + r.sent().size() + " dead=" + r.dead().size()));
        jobs.failIfIncomplete();
    }

    // ------------------------------------------------------------------ 3B: 봉인·정정·무효·재기준·산출물

    /** 운영자 CLI 호출자(감사 역할 OPERATOR, 6A 승인 Q9 — {@code --role} 폐기). */
    private static Caller caller(CliArguments args, TenantId tenant) {
        return Caller.cli(tenant, args.required("operator"));
    }

    private static DisclosureId disclosureId(CliArguments args) {
        return DisclosureId.parse(args.required("id"));
    }

    /** 사유 = 코드(인자) + 선택 텍스트(파일, UTF-8 — 자유 텍스트는 인자로 받지 않는다). 텍스트는 출력하지 않는다. */
    private static LifecycleReason reason(CliArguments args) {
        String text = args.optional("reason-file").map(f -> read(Path.of(f)).strip()).orElse(null);
        if (text != null && text.isEmpty()) {
            throw new CliFailure("reason file is empty");
        }
        return new LifecycleReason(args.required("reason-code"), text);
    }

    private void sealDisclosure(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        SealService.Outcome o = seal.seal(caller(args, tenant), disclosureId(args));
        if (!o.sealed()) {
            out.println("SEAL " + tenant + " " + o.id() + " REJECTED " + o.rejections());
            throw new CliRejection("seal rejected: " + o.rejections());
        }
        out.println("SEAL " + tenant + " " + o.id() + " " + o.status() + " no=" + o.number().orElseThrow()
                + (o.retentionPending() ? " RETENTION_PENDING (run artifacts reconcile)" : " LOCKED"));
    }

    private void voidDisclosure(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        LifecycleService.Outcome o = lifecycle.voidDisclosure(caller(args, tenant), disclosureId(args), reason(args));
        lifecycleResult("VOID", tenant, o);
    }

    private void supersede(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        LifecycleService.Outcome o = lifecycle.supersede(caller(args, tenant), disclosureId(args), reason(args));
        lifecycleResult("SUPERSEDE", tenant, o);
    }

    private void rebase(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        lifecycleResult("REBASE", tenant, lifecycle.rebase(caller(args, tenant), disclosureId(args)));
    }

    private void lifecycleResult(String command, TenantId tenant, LifecycleService.Outcome o) {
        if (!o.applied()) {
            out.println(command + " " + tenant + " " + o.id() + " REJECTED " + o.rejection().orElseThrow());
            throw new CliRejection(command.toLowerCase(java.util.Locale.ROOT) + " rejected: " + o.rejection().orElseThrow());
        }
        out.println(command + " " + tenant + " " + o.id() + " " + o.status() + o.newVersion().map(v -> " next=" + v).orElse(""));
    }

    private void artifactGet(CliArguments args) {
        TenantId tenant = TenantId.of(args.required("tenant"));
        ArtifactKind kind = ArtifactKind.valueOf(args.required("kind"));
        ArtifactService.View view = artifacts.view(caller(args, tenant), disclosureId(args), kind);
        switch (view) {
            case ArtifactService.View.Granted g -> {
                Path target = Path.of(args.required("out"));
                try {
                    Files.write(target, g.plaintext());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                out.println("ARTIFACT_GET " + tenant + " " + g.record().disclosureId() + " " + kind + " sha256=" + g.record().sha256() + " bytes="
                        + g.record().bytes() + " -> " + target);
            }
            case ArtifactService.View.Denied d -> {
                out.println("ARTIFACT_GET " + tenant + " " + args.required("id") + " " + kind + " DENIED " + d.reason());
                throw new CliRejection("artifact view denied: " + d.reason());
            }
        }
    }

    private void artifactGc(CliArguments args) {
        java.time.Duration grace = java.time.Duration.parse(args.optional("grace").orElse("PT24H"));
        for (TenantId tenant : tenants(args.optional("tenants").orElse("all"))) {
            ArtifactService.GcReport r;
            try {
                r = artifacts.gc(caller(args, tenant), grace);
            } catch (IllegalArgumentException e) {
                throw new CliFailure(e.getMessage());
            }
            out.println("ARTIFACT_GC " + tenant + " scanned=" + r.scanned() + " deleted=" + r.deleted().size() + " referenced=" + r.referenced()
                    + " young=" + r.young() + " locked=" + r.locked());
        }
    }

    private void artifactReconcile(CliArguments args) {
        int limit = Integer.parseInt(args.optional("limit").orElse("500"));
        tools.jackson.databind.node.ObjectNode params = tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode().put("limit", limit);
        for (TenantId tenant : tenants(args.optional("tenants").orElse("all"))) {
            jobs.one(caller(args, tenant), JobKind.RECONCILE, params, StandardJobs.reconcile(artifacts, limit))
                    .ifPresent(r -> out.println("ARTIFACT_RECONCILE " + tenant + " applied=" + r.applied() + " failed=" + r.failed()));
        }
        jobs.failIfIncomplete();
    }

    // ------------------------------------------------------------------

    private List<TenantId> tenants(String spec) {
        if (spec.equals("all")) {
            return directory.allTenants();
        }
        return Arrays.stream(spec.split(",")).map(String::strip).filter(s -> !s.isEmpty()).map(TenantId::of).toList();
    }

    private static Path bundlesDir(CliArguments args) {
        return bundlesDirs(args).getFirst();
    }

    /** {@code --bundles-dir a,b}: 대사는 모든 디렉터리의 번들을 정본으로 본다(데모 전용 번들은 contracts 밖 — 5 계획 §8.7). 배포 검색은 첫 디렉터리. */
    private static List<Path> bundlesDirs(CliArguments args) {
        return Arrays.stream(args.optional("bundles-dir").orElse(DEFAULT_BUNDLES_DIR).split(",")).map(String::strip).filter(p -> !p.isEmpty())
                .map(Path::of).toList();
    }

    /** 경로 그대로 → 번들 디렉터리 기준 → 클래스패스(ga-contracts/rules/bundles) 순으로 찾는다. */
    static Bundle loadBundle(String path, Path bundlesDir) {
        Path direct = Path.of(path);
        if (Files.isRegularFile(direct)) {
            return BundleLoader.parse(path, read(direct));
        }
        Path underDir = bundlesDir.resolve(path);
        if (Files.isRegularFile(underDir)) {
            return BundleLoader.parse(underDir.toString(), read(underDir));
        }
        try (InputStream in = OperatorCli.class.getResourceAsStream("/ga-contracts/rules/bundles/" + path)) {
            if (in == null) {
                throw new CliFailure("bundle not found: " + path + " (also looked in " + bundlesDir + " and the classpath)");
            }
            return BundleLoader.parse("classpath:" + path, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<Bundle> allBundles(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new CliFailure("bundles directory not found: " + dir);
        }
        List<Bundle> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".bundle.json")).sorted().toList()) {
                out.add(BundleLoader.parse(f.toString(), read(f)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
