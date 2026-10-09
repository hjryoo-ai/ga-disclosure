package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.domain.pii.CustomerName;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.infra.crypto.LocalFileKeyProvider;
import com.ga.disclosure.infra.engine.EngineClientSettings;
import com.ga.disclosure.infra.engine.EngineGradeClient;
import com.ga.disclosure.infra.engine.HttpEngineTransport;
import com.ga.disclosure.infra.engine.stub.TableEngineStub;
import com.ga.disclosure.infra.outbox.OutboxRepository;
import com.ga.disclosure.infra.persistence.AuditLogRepository;
import com.ga.disclosure.infra.persistence.CatalogRepository;
import com.ga.disclosure.infra.persistence.ComplianceFlagRepository;
import com.ga.disclosure.infra.persistence.CustomerVaultRepository;
import com.ga.disclosure.infra.persistence.DisclosureRepository;
import com.ga.disclosure.infra.persistence.FormTemplateRepository;
import com.ga.disclosure.infra.persistence.IdentityLinkRepository;
import com.ga.disclosure.infra.persistence.ReviewRepository;
import com.ga.disclosure.infra.persistence.RuleVersionRepository;
import com.ga.disclosure.infra.persistence.TenantRepository;
import com.ga.disclosure.infra.testing.FakeEngine;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.infra.tx.TenantTransactionTemplate;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.rules.validation.standard.StandardValidations;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.catalog.CatalogImportService;
import com.ga.disclosure.workflow.customer.CustomerRefService;
import com.ga.disclosure.workflow.customer.NewCustomer;
import com.ga.disclosure.workflow.disclosure.DisclosureService;
import com.ga.disclosure.workflow.disclosure.ItemInput;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantSessionBinder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Phase 3A 통합 테스트 조립: 새 테넌트에 규제 번들(DISC-2026-07·DISC-2027-01·STANDARD-v1) 배포·활성화, 카탈로그 수입(서식 항목 코드를
 * 기본값 키로 쓰는 가상 상품), 가상 고객 1명 등록, {@link FakeEngine} 위의 실제 HTTP 엔진 클라이언트, 실제 저장소 어댑터로 만든
 * {@link DisclosureService}. 시계는 고정.
 */
final class WorkflowSetup implements AutoCloseable {

    static final String TOKEN = "fake-engine-token-for-tests";
    static final GroupCode GROUP = GroupCode.of("PG-HEALTH-SIMPLE-NR");
    static final LocalDate CONSULT = LocalDate.parse("2026-09-23");
    static final Actor AGENT = new Actor("agent-1@test", "AGENT");
    static final Actor MANAGER = new Actor("manager-1@test", "MANAGER");
    /** {@code identity_link}로 해석한 설계사·관리자 ID(Phase 4 — 확인서 {@code agent_id}는 subject가 아니라 이 값). */
    static final String AGENT_ID = "AGENT-1";
    static final String MANAGER_ID = "MGR-1";

    final PostgresHarness db = PostgresHarness.get();
    final TenantJdbcGateway gateway = new TenantJdbcGateway(db.appDataSource());
    final TenantTransactionTemplate tx = new TenantTransactionTemplate(new TenantSessionBinder(db.appDataSource()));
    final AuditLogRepository audit = new AuditLogRepository(gateway);
    final CatalogRepository catalog = new CatalogRepository(gateway);
    final OutboxRepository outbox = new OutboxRepository(gateway);
    final IdentityLinkRepository agents = new IdentityLinkRepository(gateway);
    final ComplianceFlagRepository flags = new ComplianceFlagRepository(gateway, outbox, new com.ga.disclosure.workflow.flag.FlagPolicyResolver(new com.ga.disclosure.rules.resolve.RuleResolver(new RuleVersionRepository(gateway))));
    final DisclosureRepository disclosures = new DisclosureRepository(gateway);
    final ReviewRepository reviews = new ReviewRepository(gateway);
    final RuleVersionRepository rules = new RuleVersionRepository(gateway);
    final FormTemplateRepository templates = new FormTemplateRepository(gateway);
    final Clock clock;
    final FakeEngine engine;
    final EngineClientSettings settings;
    final LocalFileKeyProvider keys;
    final CustomerVaultRepository vault;
    final DisclosureService service;
    final TenantId tenant;
    final CustomerRef customer;

    WorkflowSetup() {
        this("2026-09-23T01:00:00Z", new EngineClientSettings(Duration.ofSeconds(2), Duration.ofSeconds(3), 3));
    }

    WorkflowSetup(String instant, EngineClientSettings settings) {
        this(instant, settings, List.of(Bundles.DISC_2026_07, Bundles.DISC_2027_01, Bundles.STANDARD_V1));
    }

    /** 배포할 정본 번들을 고른다(3B: 2027 룰 없이 2026 룰을 소급 대체하는 시나리오). */
    WorkflowSetup(String instant, EngineClientSettings settings, List<String> bundles) {
        this(instant, settings, bundles.stream().map(Bundles::load).toArray(com.ga.disclosure.rules.bundle.Bundle[]::new));
    }

    /**
     * 룰 데이터 변형 테넌트(Phase 4 G2): DISC-2026-07 본문을 고친 GLOBAL 번들(번들 ID는 고친 본문의 해시로 다시 만든다)과 STANDARD-v1만 배포한다.
     */
    static WorkflowSetup withRule(java.util.function.Consumer<tools.jackson.databind.node.ObjectNode> edit) {
        tools.jackson.databind.node.ObjectNode bundle = (tools.jackson.databind.node.ObjectNode) com.ga.platform.canonical.Canonicalizer
                .parseStrict(Bundles.text(Bundles.DISC_2026_07));
        tools.jackson.databind.node.ObjectNode body = (tools.jackson.databind.node.ObjectNode) bundle.get("body");
        edit.accept(body);
        String hash = com.ga.platform.canonical.Sha256.of(com.ga.platform.canonical.Canonicalizer.canonicalize(body));
        bundle.put("bundleId", bundle.get("ruleVersionId").asString() + "@" + hash.substring(0, 12));
        com.ga.disclosure.rules.bundle.Bundle variant = com.ga.disclosure.rules.bundle.BundleLoader.parse("variant", bundle.toString());
        return new WorkflowSetup("2026-09-23T01:00:00Z", new EngineClientSettings(Duration.ofSeconds(2), Duration.ofSeconds(3), 3), variant,
                Bundles.load(Bundles.STANDARD_V1));
    }

    private WorkflowSetup(String instant, EngineClientSettings settings, com.ga.disclosure.rules.bundle.Bundle... bundles) {
        this.clock = Clock.fixed(Instant.parse(instant), Governance.SEOUL);
        this.settings = settings;
        this.engine = new FakeEngine(TableEngineStub.load(resource("/workflow/engine-table.json")), TOKEN, clock);
        this.keys = LocalFileKeyProvider.load(CatalogCustomerSetup.newKekFile());
        this.vault = new CustomerVaultRepository(gateway, keys);
        this.service = new DisclosureService(disclosures, reviews, flags, new TenantRepository(gateway),
                new EngineGradeClient(new HttpEngineTransport(settings, t -> Optional.of(TOKEN), t -> engine.baseUrl())),
                catalog, catalog, vault, new RuleResolver(rules), new TemplateResolver(templates), StandardValidations.registry(), audit, tx,
                clock, agents, outbox, Callers.authz(clock));
        this.tenant = freshTenant(bundles);
        this.customer = new CustomerRefService(vault, audit, tx, clock, Callers.authz(clock)).register(Callers.of(tenant, CatalogCustomerSetup.OPERATOR),
                new NewCustomer(CustomerName.of("가상고객"), null, null));
    }

    private TenantId freshTenant(com.ga.disclosure.rules.bundle.Bundle... bundles) {
        String t = SeedData.uniqueTenant("WF");
        db.seed(t, c -> {
            SeedData.tenant(c, t);
            SeedData.identityLink(c, t, AGENT.subject(), AGENT_ID, "AGENT");
            SeedData.identityLink(c, t, MANAGER.subject(), MANAGER_ID, "MANAGER");
            SeedData.roleLink(c, t, SealSetup.COMPLIANCE.subject(), "COMPLIANCE");
        });
        TenantId tenant = TenantId.of(t);
        Governance g = new Governance();
        for (com.ga.disclosure.rules.bundle.Bundle b : bundles) {
            g.distribution.distribute(b, tenant, Governance.OPERATOR);
        }
        for (String day : List.of("2026-09-23", "2027-01-01")) {
            new Governance(LocalDate.parse(day).atStartOfDay(Governance.SEOUL).toInstant().toString()).activation.run(tenant, Governance.OPERATOR);
        }
        CatalogImportService imports = new CatalogImportService(catalog, audit, tx, clock, Callers.authz(clock));
        imports.importFile(Callers.of(tenant, CatalogCustomerSetup.OPERATOR), "groups.json", CatalogFiles.groups("2026-09-01",
                CatalogFiles.group(GROUP.value(), "2026-07-01", null), CatalogFiles.group("PG-CANCER", "2026-07-01", null))
                .getBytes(StandardCharsets.UTF_8));
        imports.importFile(Callers.of(tenant, CatalogCustomerSetup.OPERATOR), "panel.json", CatalogFiles.panel("2026-09-01",
                CatalogFiles.insurer("INS-A", "2026-01-01", null), CatalogFiles.insurer("INS-B", "2026-01-01", null),
                CatalogFiles.insurer("INS-C", "2026-01-01", null), CatalogFiles.insurer("INS-D", "2026-01-01", null),
                CatalogFiles.insurer("INS-E", "2026-01-01", null)).getBytes(StandardCharsets.UTF_8));
        imports.importFile(Callers.of(tenant, CatalogCustomerSetup.OPERATOR), "products.json", CatalogFiles.products("2026-09-01",
                product("INS-A:PRD-1001", GROUP.value()), product("INS-B:PRD-2044", GROUP.value()), product("INS-C:PRD-3120", GROUP.value()),
                product("INS-D:PRD-4410", GROUP.value()), product("INS-E:PRD-5001", GROUP.value()), product("INS-A:PRD-1101", "PG-CANCER"))
                .getBytes(StandardCharsets.UTF_8));
        return tenant;
    }

    /** 서식 항목 코드를 키로 쓰는 카탈로그 기본값(카탈로그 파일 스키마: "서식 항목 기본값 {항목 코드: 값}"). */
    private static String product(String key, String group) {
        String insurer = key.substring(0, key.indexOf(':'));
        return "{\"productKey\":\"" + key + "\",\"insurerCode\":\"" + insurer + "\",\"groupCode\":\"" + group + "\",\"productName\":\"(가상) "
                + key + "\",\"saleFrom\":\"2026-07-01\",\"saleTo\":null,\"defaults\":{\"INSURER_NAME\":\"(가상) " + insurer
                + "\",\"PRODUCT_NAME\":\"(가상) " + key + "\",\"PREMIUM\":32100,\"SURRENDER_VALUE_EXAMPLE\":[{\"year\":10,\"refundWon\":0}]}}";
    }

    static String resource(String path) {
        try (InputStream in = WorkflowSetup.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ItemInput catalogItem(String key, boolean recommended) {
        return new ItemInput.Catalog(ProductKey.parse(key), recommended, false, Map.of());
    }

    static List<ItemInput> threeItems() {
        return List.of(catalogItem("INS-A:PRD-1001", true), catalogItem("INS-B:PRD-2044", false), catalogItem("INS-C:PRD-3120", true));
    }

    /** 같은 테넌트·어댑터에 다른 시계를 쓰는 작성 유스케이스(6B 방치 초안: 나중에 고친 초안). */
    DisclosureService serviceAt(Clock at) {
        return new DisclosureService(disclosures, reviews, flags, new TenantRepository(gateway),
                new EngineGradeClient(new HttpEngineTransport(settings, t -> Optional.of(TOKEN), t -> engine.baseUrl())),
                catalog, catalog, vault, new RuleResolver(rules), new TemplateResolver(templates), StandardValidations.registry(), audit, tx,
                at, agents, outbox, Callers.authz(at));
    }

    /** 봉인·정정·무효 유스케이스가 함께 쓰는 포트 묶음(같은 저장소 어댑터·시계). */
    com.ga.disclosure.workflow.disclosure.DisclosureServiceDeps deps(Clock at) {
        return new com.ga.disclosure.workflow.disclosure.DisclosureServiceDeps(disclosures, reviews, flags, new TenantRepository(gateway), catalog,
                catalog, vault, new RuleResolver(rules), new TemplateResolver(templates), StandardValidations.registry(), audit, tx, at, agents,
                outbox, Callers.authz(at));
    }

    /** 3사 비교 → 산출 → 추천사유(항목 1·3)까지 = 봉인 직전(REASONED). */
    DisclosureId reasoned() {
        return reasoned(customer);
    }

    /** 그 고객의 확인서로 봉인 직전(REASONED)까지(Phase 4: 연락처·생년월일이 있는 고객). */
    DisclosureId reasoned(CustomerRef who) {
        DisclosureId id = service.createDraft(Callers.of(tenant, AGENT), who, GROUP, CONSULT, TemplateType.STANDARD, java.util.Optional.empty());
        service.replaceItems(Callers.of(tenant, AGENT), id, threeItems());
        service.compare(Callers.of(tenant, AGENT), id);
        service.requestGrades(Callers.of(tenant, AGENT), id);
        service.setRecommendations(Callers.of(tenant, AGENT), id, List.of(
                new com.ga.disclosure.domain.disclosure.AgentReason(1, List.of(com.ga.disclosure.domain.vo.ReasonCode.of("PREMIUM")), null),
                new com.ga.disclosure.domain.disclosure.AgentReason(3, List.of(com.ga.disclosure.domain.vo.ReasonCode.of("COVERAGE")), null)));
        return id;
    }

    DisclosureId draft() {
        return service.createDraft(Callers.of(tenant, AGENT), customer, GROUP, CONSULT, TemplateType.STANDARD, java.util.Optional.empty());
    }

    /** 초안 → 항목 3건 → 비교까지. */
    DisclosureId compared() {
        DisclosureId id = draft();
        service.replaceItems(Callers.of(tenant, AGENT), id, threeItems());
        service.compare(Callers.of(tenant, AGENT), id);
        return id;
    }

    <T> T in(Supplier<T> work) {
        return tx.inTenant(tenant, work);
    }

    List<AuditRecord> auditLog() {
        return in(audit::readAll);
    }

    @Override
    public void close() {
        engine.close();
    }
}
