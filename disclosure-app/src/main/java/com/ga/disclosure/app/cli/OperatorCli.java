package com.ga.disclosure.app.cli;

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
import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.infra.crypto.LocalFileKeyProvider;
import com.ga.disclosure.infra.persistence.TenantRepository;
import com.ga.disclosure.rules.bundle.Bundle;
import com.ga.disclosure.rules.bundle.BundleLoader;
import com.ga.disclosure.rules.version.RuleVersion;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.catalog.CatalogImportOutcome;
import com.ga.disclosure.workflow.catalog.CatalogImportRejectedException;
import com.ga.disclosure.workflow.catalog.CatalogImportService;
import com.ga.disclosure.workflow.catalog.InvalidCatalogFileException;
import com.ga.disclosure.workflow.customer.CustomerRekeyService;
import com.ga.disclosure.workflow.customer.RekeyReport;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * 운영자 CLI(프로파일 {@code cli}, 웹 서버 없이 실행 후 종료). 인가는 Phase 6이므로 지금은 운영자 CLI만 있고, 모든 행위는
 * {@code audit_log}에 {@code actor_role=OPERATOR}로 남는다. 실패(인자 오류·거부)는 예외로 전파되어 종료 코드가 0이 아니다.
 *
 * <pre>
 * rules distribute --bundle &lt;path&gt; --tenants all|T1,T2 --operator &lt;id&gt; [--bundles-dir contracts/rules/bundles]
 * rules approve    --tenant T1 --rule &lt;id&gt; --operator &lt;id&gt;
 * rules activate   [--as-of 2027-01-01] [--tenants all|T1,T2] --operator &lt;id&gt;
 * rules reconcile  [--tenants all|T1,T2] [--bundles-dir contracts/rules/bundles] --operator &lt;id&gt;
 * demo seed        --file &lt;seed.json&gt; --operator &lt;id&gt;
 * catalog import   --tenant T1 --file &lt;catalog.json&gt; --operator &lt;id&gt;
 * customer rekey   --tenant T1 --operator &lt;id&gt; [--batch 500]
 * crypto init-kek  --file &lt;path outside the repo&gt; [--kek-id KEK-LOCAL-1]
 * </pre>
 * 고객 개인정보는 CLI 인자로 받지 않는다(셸 기록·프로세스 목록에 남는다).
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
    private final PrintStream out = System.out;

    public OperatorCli(RuleDistributionService distribution, RuleApprovalService approval, RuleActivationJob activation,
                       RuleBundleReconciler reconciler, TenantDirectory directory, TenantTransactions transactions,
                       TenantRepository tenants, RuleVersionStore rules, CatalogImportService catalog, CustomerRekeyService rekey) {
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
    }

    @Override
    public void run(ApplicationArguments arguments) {
        CliArguments args = CliArguments.parse(arguments.getSourceArgs());
        switch (args.command()) {
            case "rules distribute" -> distribute(args);
            case "rules approve" -> approve(args);
            case "rules activate" -> activate(args);
            case "rules reconcile" -> reconcile(args);
            case "demo seed" -> seed(args);
            case "catalog import" -> importCatalog(args);
            case "customer rekey" -> rekey(args);
            case "crypto init-kek" -> initKek(args);
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
        List<Bundle> known = allBundles(bundlesDir(args));
        int drift = 0;
        for (TenantId tenant : tenants(args.optional("tenants").orElse("all"))) {
            ReconcileReport r = reconciler.reconcile(tenant, known, operator);
            drift += r.drifts().size();
            out.println("RECONCILE " + tenant + " checked=" + r.checked() + " drift=" + r.drifts().size());
            r.drifts().forEach(d -> out.println("  RULE_DRIFT " + d.targetKind() + " " + d.targetId() + " flag=" + d.flagId() + " " + d.problems()));
        }
        out.println("RECONCILE total drift=" + drift);
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
        Actor actor = new Actor(args.required("operator"), Operator.ROLE);
        TenantId tenant = TenantId.of(args.required("tenant"));
        Path file = Path.of(args.required("file"));
        CatalogImportOutcome o;
        try {
            o = catalog.importFile(tenant, actor, file.getFileName().toString(), Files.readAllBytes(file));
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
        Actor actor = new Actor(args.required("operator"), Operator.ROLE);
        TenantId tenant = TenantId.of(args.required("tenant"));
        int batch = Integer.parseInt(args.optional("batch").orElse("500"));
        RekeyReport r = rekey.rekey(tenant, actor, batch);
        out.println("REKEY " + tenant + " retired=" + r.retiredKeyId().orElse("-") + " active=" + r.activeKeyId() + " reencrypted="
                + r.reencrypted() + " destroyed=" + r.destroyedKeyIds());
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

    // ------------------------------------------------------------------

    private List<TenantId> tenants(String spec) {
        if (spec.equals("all")) {
            return directory.allTenants();
        }
        return Arrays.stream(spec.split(",")).map(String::strip).filter(s -> !s.isEmpty()).map(TenantId::of).toList();
    }

    private static Path bundlesDir(CliArguments args) {
        return Path.of(args.optional("bundles-dir").orElse(DEFAULT_BUNDLES_DIR));
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
