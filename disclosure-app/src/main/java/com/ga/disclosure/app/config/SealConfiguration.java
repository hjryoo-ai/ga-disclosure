package com.ga.disclosure.app.config;

import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.outbox.OutboxPort;
import com.ga.disclosure.infra.crypto.DocumentCipher;
import com.ga.disclosure.infra.storage.S3StorageSettings;
import com.ga.disclosure.infra.storage.VerifiedArtifactStore;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.rules.validation.ValidationRegistry;
import com.ga.disclosure.seal.renderer.DisclosurePdfRenderer;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.catalog.InsurerPanelPort;
import com.ga.disclosure.workflow.catalog.ProductCatalogPort;
import com.ga.disclosure.workflow.customer.CustomerVault;
import com.ga.disclosure.workflow.customer.KeyProviderPort;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.workflow.disclosure.DisclosureServiceDeps;
import com.ga.disclosure.workflow.disclosure.DisclosureStore;
import com.ga.disclosure.workflow.disclosure.LifecycleService;
import com.ga.disclosure.workflow.disclosure.ReviewStore;
import com.ga.disclosure.workflow.disclosure.SealLedgerPort;
import com.ga.disclosure.workflow.disclosure.SealService;
import com.ga.disclosure.workflow.disclosure.TenantProfilePort;
import com.ga.disclosure.workflow.identity.AgentDirectory;
import com.ga.disclosure.workflow.sign.SignSessionStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

/**
 * Phase 3B 조립: 봉인·정정·무효·재기준·산출물 유스케이스, 문서 키 암호화, S3 호환 객체 저장소(설계서 §6.4·§6.6·§9). 저장소는 표준 S3 API만 쓰고
 * ({@code ga.storage.s3.*}), 버킷은 첫 사용 직전에 확인한다({@link VerifiedArtifactStore}). 자격 증명은 배포 설정·환경에서 받고 저장소에 커밋하지
 * 않는다 — 기본값은 로컬 compose의 허구 키다(로컬 DB 비밀번호와 같은 취급).
 */
@Configuration
public class SealConfiguration {

    @Bean
    public S3StorageSettings s3StorageSettings(@Value("${ga.storage.s3.endpoint}") String endpoint,
                                               @Value("${ga.storage.s3.region:us-east-1}") String region,
                                               @Value("${ga.storage.s3.bucket}") String bucket,
                                               @Value("${ga.storage.s3.access-key-id}") String accessKeyId,
                                               @Value("${ga.storage.s3.secret-access-key}") String secretAccessKey,
                                               @Value("${ga.storage.s3.path-style:true}") boolean pathStyle) {
        return new S3StorageSettings(URI.create(endpoint), region, bucket, accessKeyId, secretAccessKey, pathStyle);
    }

    @Bean
    public ArtifactStore artifactStore(S3StorageSettings settings, @Value("${ga.storage.s3.create-bucket:false}") boolean createBucket) {
        return VerifiedArtifactStore.of(settings, createBucket);
    }

    @Bean
    public DocumentCryptoPort documentCrypto(KeyProviderPort keys) {
        return new DocumentCipher(keys);
    }

    @Bean
    public DisclosurePdfRenderer disclosurePdfRenderer() {
        return new DisclosurePdfRenderer();
    }

    @Bean
    public DisclosureServiceDeps disclosureServiceDeps(DisclosureStore store, ReviewStore reviews, DisclosureFlagPort flags,
                                                       TenantProfilePort tenants, ProductCatalogPort catalog, InsurerPanelPort panel,
                                                       CustomerVault customers, RuleResolver rules, TemplateResolver templates,
                                                       ValidationRegistry registry, AuditPort audit, WorkflowTransactions tx, Clock clock,
                                                       AgentDirectory agents, OutboxPort outbox, AuthorizationPort authz) {
        return new DisclosureServiceDeps(store, reviews, flags, tenants, catalog, panel, customers, rules, templates, registry, audit, tx, clock,
                agents, outbox, authz);
    }

    @Bean
    public SealService sealService(DisclosureServiceDeps deps, SealLedgerPort ledger, DocumentCryptoPort crypto, DocumentRecordStore records,
                                   ArtifactStore storage, DisclosurePdfRenderer renderer,
                                   @Value("${ga.seal.transaction-timeout:PT60S}") Duration timeout) {
        return new SealService(deps, ledger, crypto, records, storage, renderer, timeout);
    }

    @Bean
    public LifecycleService lifecycleService(DisclosureServiceDeps deps, SignSessionStore sessions) {
        return new LifecycleService(deps, sessions);
    }

    @Bean
    public ArtifactService artifactService(DocumentRecordStore records, DocumentCryptoPort crypto, ArtifactStore storage, AuditPort audit,
                                           WorkflowTransactions tx, Clock clock, SealService seal, AuthorizationPort authz) {
        return new ArtifactService(records, crypto, storage, audit, tx, clock, seal.transactionTimeout(), authz);
    }
}
