package com.ga.disclosure.app.config;

import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.infra.json.SensitiveGuardModule;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.catalog.CatalogImportService;
import com.ga.disclosure.workflow.catalog.CatalogStore;
import com.ga.disclosure.workflow.customer.CustomerRefService;
import com.ga.disclosure.workflow.customer.CustomerRekeyService;
import com.ga.disclosure.workflow.customer.CustomerVault;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Phase 2 조립: 카탈로그 수입, 고객 참조 암호화, 개인정보 직렬화 차단 모듈.
 * KEK 어댑터는 {@link SecretsConfiguration}(Phase 8 — 테넌트 KEK).
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

    /** 앱 JSON 매퍼(Boot 자동 구성)에 등록된다 — {@code Sensitive}를 직렬화하려 하면 예외. */
    @Bean
    public SensitiveGuardModule sensitiveGuardModule() {
        return new SensitiveGuardModule();
    }
}
