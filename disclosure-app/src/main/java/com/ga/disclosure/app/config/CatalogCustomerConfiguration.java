package com.ga.disclosure.app.config;

import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.infra.crypto.LocalFileKeyProvider;
import com.ga.disclosure.infra.json.SensitiveGuardModule;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.catalog.CatalogImportService;
import com.ga.disclosure.workflow.catalog.CatalogStore;
import com.ga.disclosure.workflow.customer.CustomerRefService;
import com.ga.disclosure.workflow.customer.CustomerRekeyService;
import com.ga.disclosure.workflow.customer.CustomerVault;
import com.ga.disclosure.workflow.customer.KeyProviderPort;
import com.ga.platform.core.tenant.TenantId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 2 조립: 카탈로그 수입, 고객 참조 암호화, 개인정보 직렬화 차단 모듈.
 * KEK 어댑터는 개발·테스트용 로컬 키 파일({@code ga.crypto.local-kek-file})이고 운영은 KMS 어댑터로 교체한다(설계서 §9).
 * 키 파일은 처음 쓰일 때 읽는다 — 설정이 없으면 고객 정보를 다루는 순간 명시적으로 실패한다(카탈로그·룰 명령은 키가 필요 없다).
 */
@Configuration
public class CatalogCustomerConfiguration {

    @Bean
    public CatalogImportService catalogImportService(CatalogStore store, AuditPort audit, WorkflowTransactions tx, Clock clock,
                                                     AuthorizationPort authz) {
        return new CatalogImportService(store, audit, tx, clock, authz);
    }

    @Bean
    public CustomerRefService customerRefService(CustomerVault vault, AuditPort audit, WorkflowTransactions tx, Clock clock,
                                                 AuthorizationPort authz) {
        return new CustomerRefService(vault, audit, tx, clock, authz);
    }

    @Bean
    public CustomerRekeyService customerRekeyService(CustomerVault vault, AuditPort audit, WorkflowTransactions tx, Clock clock,
                                                     AuthorizationPort authz) {
        return new CustomerRekeyService(vault, audit, tx, clock, authz);
    }

    @Bean
    public KeyProviderPort keyProvider(@Value("${ga.crypto.local-kek-file:}") String localKekFile) {
        return new LazyLocalKeyProvider(localKekFile);
    }

    /** 앱 JSON 매퍼(Boot 자동 구성)에 등록된다 — {@code Sensitive}를 직렬화하려 하면 예외. */
    @Bean
    public SensitiveGuardModule sensitiveGuardModule() {
        return new SensitiveGuardModule();
    }

    private static final class LazyLocalKeyProvider implements KeyProviderPort {

        private final String file;
        private final AtomicReference<LocalFileKeyProvider> loaded = new AtomicReference<>();

        private LazyLocalKeyProvider(String file) {
            this.file = file;
        }

        private KeyProviderPort delegate() {
            LocalFileKeyProvider provider = loaded.get();
            if (provider == null) {
                if (file == null || file.isBlank()) {
                    throw new IllegalStateException("no key provider configured: set ga.crypto.local-kek-file (development) "
                            + "or wire a KMS adapter (production)");
                }
                provider = LocalFileKeyProvider.load(Path.of(file));
                loaded.compareAndSet(null, provider);
            }
            return loaded.get();
        }

        @Override
        public String currentKekId() {
            return delegate().currentKekId();
        }

        @Override
        public byte[] wrap(TenantId tenant, String keyId, String kekId, byte[] dataKey) {
            return delegate().wrap(tenant, keyId, kekId, dataKey);
        }

        @Override
        public byte[] unwrap(TenantId tenant, String keyId, String kekId, byte[] wrapped) {
            return delegate().unwrap(tenant, keyId, kekId, wrapped);
        }
    }
}
