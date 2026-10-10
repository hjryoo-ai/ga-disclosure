package com.ga.disclosure.app.config;

import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.infra.crypto.TenantKeyProvider;
import com.ga.disclosure.infra.secret.EnvSecretSource;
import com.ga.disclosure.infra.secret.FileSecretSource;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.customer.KeyProviderPort;
import com.ga.disclosure.workflow.kek.KekRewrapStore;
import com.ga.disclosure.workflow.kek.TenantKekService;
import com.ga.disclosure.workflow.kek.TenantKekStore;
import com.ga.disclosure.workflow.secret.SecretName;
import com.ga.disclosure.workflow.secret.SecretSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Phase 8 비밀 출처와 테넌트 KEK 조립(8 계획 ④·승인 Q2). 출처는 {@code ga.secrets.dir}(파일 — 쿠버네티스 Secret 마운트·로컬 디렉터리,
 * {@code ga.secrets.allow-group-read}는 마운트용) 또는 {@code ga.secrets.source=env}(개발 전용). 처음 쓰일 때 연다 — 설정이 없으면 비밀이 필요한 순간
 * 명시적으로 실패한다(룰·카탈로그 명령은 비밀이 필요 없다).
 */
@Configuration
public class SecretsConfiguration {

    @Bean
    public SecretSource secretSource(@Value("${ga.secrets.dir:}") String dir, @Value("${ga.secrets.allow-group-read:false}") boolean allowGroupRead,
                                     @Value("${ga.secrets.source:file}") String source) {
        return new LazySecretSource(() -> switch (source) {
            case "file" -> {
                if (dir == null || dir.isBlank()) {
                    throw new IllegalStateException("no secret source configured: set ga.secrets.dir");
                }
                yield new FileSecretSource(Path.of(dir), allowGroupRead);
            }
            case "env" -> new EnvSecretSource(System.getenv());
            default -> throw new IllegalStateException("unknown ga.secrets.source");
        });
    }

    @Bean
    public KeyProviderPort keyProvider(SecretSource secrets, TenantKekStore registry, WorkflowTransactions tx) {
        return new TenantKeyProvider(secrets, tenant -> tx.inTenant(tenant, registry::current));
    }

    @Bean
    public TenantKekService tenantKekService(TenantKekStore registry, KekRewrapStore rows, KeyProviderPort keys, AuditPort audit, WorkflowTransactions tx,
                                             AuthorizationPort authz, Clock clock) {
        return new TenantKekService(registry, rows, keys, audit, tx, authz, clock);
    }

    /** 처음 읽을 때 출처를 연다. */
    private static final class LazySecretSource implements SecretSource {

        private final Supplier<SecretSource> open;
        private final AtomicReference<SecretSource> opened = new AtomicReference<>();

        private LazySecretSource(Supplier<SecretSource> open) {
            this.open = open;
        }

        private SecretSource delegate() {
            SecretSource s = opened.get();
            if (s == null) {
                opened.compareAndSet(null, open.get());
            }
            return opened.get();
        }

        @Override
        public byte[] read(SecretName name) {
            return delegate().read(name);
        }

        @Override
        public boolean exists(SecretName name) {
            return delegate().exists(name);
        }
    }
}
