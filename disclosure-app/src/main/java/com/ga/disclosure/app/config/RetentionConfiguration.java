package com.ga.disclosure.app.config;

import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.outbox.OutboxPort;
import com.ga.disclosure.audit.tsa.NonceSource;
import com.ga.disclosure.audit.tsa.TimestampAuthorityPort;
import com.ga.disclosure.audit.tsa.TimestampClient;
import com.ga.disclosure.audit.tsa.TimestampFailure;
import com.ga.disclosure.audit.tsa.TrustAnchors;
import com.ga.disclosure.audit.tsa.http.HttpTimestampAuthority;
import com.ga.disclosure.audit.tsa.stub.LocalStubTsa;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.anchor.AnchorJob;
import com.ga.disclosure.workflow.anchor.AnchorStore;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.disclosure.workflow.retention.DestroyerPort;
import com.ga.disclosure.workflow.retention.DestructionJob;
import com.ga.disclosure.workflow.retention.ErasureReader;
import com.ga.disclosure.workflow.retention.LegalHoldService;
import com.ga.disclosure.workflow.retention.LegalHoldStore;
import com.ga.disclosure.workflow.retention.RetentionStore;
import com.ga.disclosure.workflow.verify.ReceiptExporter;
import com.ga.disclosure.workflow.verify.SealChainReader;
import com.ga.disclosure.workflow.verify.TenantVerifier;
import com.ga.disclosure.workflow.secret.SecretName;
import com.ga.disclosure.workflow.secret.SecretSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Phase 5 조립: 일일 앵커·TSA·영수증 내보내기·{@code verify tenant}·파기·법적 보류(설계서 §6.7·§9).
 * <ul>
 *   <li>TSA {@code ga.tsa.mode}: {@code http}(기본 — {@code ga.tsa.url}과 신뢰 앵커 PEM — Phase 8부터 비밀 {@code tsa/trust-anchors.pem}) 또는 {@code stub}(데모·개발 —
 *       키는 저장소 밖 {@code ga.tsa.stub.key-store}에 처음 쓸 때 만들고, 신뢰 앵커 인증서만 {@code ga.tsa.trust-pem}(기본 gitignore된
 *       {@code build/demo/tsa-trust.pem})으로 내보낸다 — 5 계획 승인 Q11). http인데 주소가 없으면 앵커 배치의 B단계가 {@code TSA_UNAVAILABLE}로
 *       남는다(앵커는 남고 다음 실행이 잇는다) — TSA 설정이 없다는 이유로 다른 명령의 기동을 막지 않는다.</li>
 * </ul>
 */
@Configuration
public class RetentionConfiguration {

    /** TSA 신뢰 앵커 비밀(Phase 8 — 바꿔치기가 곧 위조라 비밀 출처로 받는다). 앵커 집합(여러 인증서 PEM) — 회전은 추가 후 제거. */
    public static final SecretName TSA_TRUST = SecretName.of("tsa/trust-anchors.pem");

    /**
     * 신뢰 앵커 PEM의 출처: 비밀 {@code tsa/trust-anchors.pem}(앵커 집합)이 있으면 어느 모드든 그것이다. 없으면 {@code http}는 null(영수증 토큰은
     * TSA_UNTRUSTED), {@code stub}은 스텁이 내보낸 파일 {@code ga.tsa.trust-pem}(로컬 데모·개발 — 지금 스텁 키 하나). (11단계) 스텁만 쓰던 첫 판은 kind의
     * 스텁 키 회전 뒤 앱 안 검증(화면의 VERIFY_TENANT)이 옛 앵커를 TSA_UNTRUSTED로 냈다 — 스텁 모드도 집합이 있으면 운영과 같이 집합을 믿는다.
     */
    @Bean
    public TsaTrust tsaTrust(@Value("${ga.tsa.mode:http}") String mode, @Value("${ga.tsa.trust-pem:build/demo/tsa-trust.pem}") String stubTrustPem,
                             SecretSource secrets) {
        return () -> secrets.exists(TSA_TRUST) ? secrets.read(TSA_TRUST) : mode.equals("stub") ? readIfPresent(Path.of(stubTrustPem)) : null;
    }

    /** 신뢰 앵커 PEM(없으면 null). */
    @FunctionalInterface
    public interface TsaTrust {
        byte[] pemOrNull();
    }

    static byte[] readIfPresent(Path pem) {
        if (!Files.isRegularFile(pem)) {
            return null;
        }
        try {
            return Files.readAllBytes(pem);
        } catch (IOException e) {
            throw new UncheckedIOException("ga.tsa.trust-pem is not readable: " + pem, e);
        }
    }

    @Bean
    public TimestampClient timestampClient(@Value("${ga.tsa.mode:http}") String mode, @Value("${ga.tsa.url:}") String url,
                                           @Value("${ga.tsa.timeout:PT10S}") Duration timeout,
                                           @Value("${ga.tsa.trust-pem:build/demo/tsa-trust.pem}") String trustPem,
                                           @Value("${ga.tsa.stub.key-store:${user.home}/.ga-disclosure/tsa-stub.p12}") String keyStore,
                                           TsaTrust trust, Clock clock) {
        return switch (mode) {
            case "stub" -> {
                LocalStubTsa stub = LocalStubTsa.loadOrCreate(Path.of(keyStore), Path.of(trustPem), clock);
                yield new TimestampClient(stub, NonceSource.secure(), stub.trustAnchors());
            }
            case "http" -> {
                if (url.isBlank()) {
                    TimestampAuthorityPort unconfigured = request -> {
                        throw new TimestampFailure(TimestampFailure.Kind.UNAVAILABLE, "TSA_NOT_CONFIGURED");
                    };
                    yield new TimestampClient(unconfigured, NonceSource.secure(), TrustAnchors.none());
                }
                byte[] pem = trust.pemOrNull();
                yield new TimestampClient(new HttpTimestampAuthority(URI.create(url), timeout), NonceSource.secure(),
                        pem == null ? TrustAnchors.none() : TrustAnchors.fromPem(pem));
            }
            default -> throw new IllegalStateException("ga.tsa.mode must be http or stub");
        };
    }

    @Bean
    public AnchorJob anchorJob(AnchorStore anchors, AuditPort audit, WorkflowTransactions tx, RuleResolver rules, TimestampClient tsa, Clock clock,
                               AuthorizationPort authz) {
        return new AnchorJob(anchors, audit, tx, rules, tsa, clock, UUID::randomUUID, authz);
    }

    @Bean
    public ReceiptExporter receiptExporter(SealChainReader chain, AnchorStore anchors, ArtifactService artifacts, AuditPort audit,
                                           WorkflowTransactions tx, Clock clock, AuthorizationPort authz) {
        return new ReceiptExporter(chain, anchors, artifacts, audit, tx, clock, authz);
    }

    @Bean
    public TenantVerifier tenantVerifier(AuditPort audit, SealChainReader chain, AnchorStore anchors, DocumentRecordStore records,
                                         DocumentCryptoPort crypto, ArtifactStore storage, RuleResolver rules, DisclosureFlagPort flags,
                                         WorkflowTransactions tx, Clock clock, AuthorizationPort authz,
                                         com.ga.disclosure.workflow.kek.KekRewrapStore keks,
                                         com.ga.disclosure.workflow.customer.KeyProviderPort keyProvider) {
        return new TenantVerifier(audit, chain, anchors, records, crypto, storage, rules, flags, tx, clock, authz, keks, keyProvider);
    }

    @Bean
    public DestructionJob destructionJob(RetentionStore store, ErasureReader erasure, DestroyerPort destroyer, DocumentRecordStore records,
                                         ArtifactStore storage, RuleResolver rules, AuditPort audit, OutboxPort outbox, WorkflowTransactions tx,
                                         Clock clock, AuthorizationPort authz) {
        return new DestructionJob(store, erasure, destroyer, records, storage, rules, audit, outbox, tx, clock, authz);
    }

    @Bean
    public LegalHoldService legalHoldService(LegalHoldStore holds, RetentionStore retention, DocumentRecordStore records, ArtifactStore storage,
                                             RuleResolver rules, AuditPort audit, WorkflowTransactions tx, Clock clock, AuthorizationPort authz) {
        return new LegalHoldService(holds, retention, records, storage, rules, audit, tx, clock, UUID::randomUUID, authz);
    }
}
