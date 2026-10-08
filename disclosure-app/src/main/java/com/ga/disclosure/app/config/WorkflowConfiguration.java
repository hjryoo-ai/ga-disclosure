package com.ga.disclosure.app.config;

import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.outbox.OutboxPort;
import com.ga.disclosure.infra.authz.IdentityLinkAuthorization;
import com.ga.disclosure.infra.engine.EngineClientSettings;
import com.ga.disclosure.infra.engine.EngineCredentialPort;
import com.ga.disclosure.infra.engine.EngineEndpoints;
import com.ga.disclosure.infra.engine.EngineGradeClient;
import com.ga.disclosure.infra.engine.EngineTransport;
import com.ga.disclosure.infra.engine.EnvironmentEngineCredentials;
import com.ga.disclosure.infra.engine.HttpEngineTransport;
import com.ga.disclosure.infra.engine.stub.StubEngineTransport;
import com.ga.disclosure.infra.engine.stub.TableEngineStub;
import com.ga.disclosure.infra.persistence.AuthzFactsRepository;
import com.ga.disclosure.infra.persistence.TenantRecord;
import com.ga.disclosure.infra.persistence.TenantRepository;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.rules.validation.ValidationRegistry;
import com.ga.disclosure.rules.validation.standard.StandardValidations;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.catalog.InsurerPanelPort;
import com.ga.disclosure.workflow.catalog.ProductCatalogPort;
import com.ga.disclosure.workflow.customer.CustomerVault;
import com.ga.disclosure.workflow.customer.RegisterCustomer;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.workflow.disclosure.DisclosureService;
import com.ga.disclosure.workflow.disclosure.DisclosureStore;
import com.ga.disclosure.workflow.disclosure.GradeSnapshotPort;
import com.ga.disclosure.workflow.disclosure.ReviewStore;
import com.ga.disclosure.workflow.disclosure.TenantProfilePort;
import com.ga.disclosure.workflow.identity.AgentDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * Phase 3A 조립: 확인서 유스케이스, 고객 등록, 엔진 클라이언트. 엔진 전송은 {@code ga.engine.mode}가 고른다 —
 * {@code http}(기본, 테넌트별 {@code engine_base_url} + 배포 설정의 서비스 토큰 {@code ga.engine.credentials.<TENANT>}) 또는
 * {@code stub}(데모: 고정표 {@code ga.engine.stub-table}을 프로세스 안에서 응답). 어느 쪽이든 응답은 계약 스키마·정합성 검증을 거친다.
 */
@Configuration
public class WorkflowConfiguration {

    @Bean
    public ValidationRegistry validationRegistry() {
        return StandardValidations.registry();
    }

    @Bean
    public EngineClientSettings engineClientSettings(@Value("${ga.engine.connect-timeout:PT2S}") Duration connect,
                                                     @Value("${ga.engine.request-timeout:PT5S}") Duration request,
                                                     @Value("${ga.engine.max-attempts:3}") int attempts) {
        return new EngineClientSettings(connect, request, attempts);
    }

    @Bean
    public EngineCredentialPort engineCredentials(Environment environment) {
        return new EnvironmentEngineCredentials(environment);
    }

    /** 테넌트 행의 엔진 주소(엔진 호출은 트랜잭션 밖이므로 짧은 읽기 트랜잭션으로 읽는다). */
    @Bean
    public EngineEndpoints engineEndpoints(TenantRepository tenants, WorkflowTransactions tx) {
        return tenant -> URI.create(tx.inTenant(tenant, () -> tenants.findCurrent().map(TenantRecord::engineBaseUrl)
                .orElseThrow(() -> new IllegalStateException("tenant " + tenant + " has no row"))));
    }

    @Bean
    public EngineTransport engineTransport(@Value("${ga.engine.mode:http}") String mode, @Value("${ga.engine.stub-table:}") String stubTable,
                                           EngineClientSettings settings, EngineCredentialPort credentials, EngineEndpoints endpoints,
                                           Clock clock) {
        return switch (mode) {
            case "http" -> new HttpEngineTransport(settings, credentials, endpoints);
            case "stub" -> {
                if (stubTable.isBlank()) {
                    throw new IllegalStateException("ga.engine.mode=stub needs ga.engine.stub-table");
                }
                try {
                    yield new StubEngineTransport(TableEngineStub.load(Files.readString(Path.of(stubTable))), clock);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            default -> throw new IllegalStateException("ga.engine.mode must be http or stub");
        };
    }

    @Bean
    public GradeSnapshotPort gradeSnapshotPort(EngineTransport transport) {
        return new EngineGradeClient(transport);
    }

    @Bean
    public DisclosureService disclosureService(DisclosureStore store, ReviewStore reviews, DisclosureFlagPort flags, TenantProfilePort tenants,
                                               GradeSnapshotPort engine, ProductCatalogPort catalog, InsurerPanelPort panel,
                                               CustomerVault customers, RuleResolver rules, TemplateResolver templates,
                                               ValidationRegistry registry, AuditPort audit, WorkflowTransactions tx, Clock clock,
                                               AgentDirectory agents, OutboxPort outbox, AuthorizationPort authz) {
        return new DisclosureService(store, reviews, flags, tenants, engine, catalog, panel, customers, rules, templates, registry, audit, tx,
                clock, agents, outbox, authz);
    }

    /** 인가 어댑터(6A 계획 §3): identity_link·대상 사실은 RLS 아래에서, 거부 감사는 별도 트랜잭션. */
    @Bean
    public IdentityLinkAuthorization authorizationPort(AgentDirectory agents, AuthzFactsRepository facts, AuditPort audit, WorkflowTransactions tx,
                                               Clock clock) {
        return new IdentityLinkAuthorization(agents, facts, audit, tx, clock);
    }

    @Bean
    public RegisterCustomer registerCustomer(CustomerVault vault, AuditPort audit, WorkflowTransactions tx, Clock clock,
                                             AuthorizationPort authz) {
        return new RegisterCustomer(vault, audit, tx, clock, authz);
    }
}
