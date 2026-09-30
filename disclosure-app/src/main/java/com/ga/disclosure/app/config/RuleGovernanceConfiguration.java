package com.ga.disclosure.app.config;

import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.compliance.rules.ComplianceFlagPort;
import com.ga.disclosure.compliance.rules.FormTemplateStore;
import com.ga.disclosure.compliance.rules.RuleActivationJob;
import com.ga.disclosure.compliance.rules.RuleApprovalService;
import com.ga.disclosure.compliance.rules.RuleBundleReconciler;
import com.ga.disclosure.compliance.rules.RuleDistributionService;
import com.ga.disclosure.compliance.rules.RuleVersionStore;
import com.ga.disclosure.compliance.rules.TenantDirectory;
import com.ga.disclosure.compliance.rules.TenantTransactions;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.rules.template.FormTemplatePort;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.rules.version.RuleVersionPort;
import com.ga.platform.spring.jdbc.TenantDirectoryReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 룰·서식·룰 거버넌스 조립. 서비스는 Spring을 모르는 순수 클래스이고(rules·compliance), 포트 구현은 infra 저장소다.
 * 업무 날짜의 시간대는 Asia/Seoul이며 시계는 여기서만 만든다(CLAUDE.md: 주입된 Clock).
 */
@Configuration
public class RuleGovernanceConfiguration {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }

    @Bean
    public RuleResolver ruleResolver(RuleVersionPort port) {
        return new RuleResolver(port);
    }

    @Bean
    public TemplateResolver templateResolver(FormTemplatePort port) {
        return new TemplateResolver(port);
    }

    @Bean
    public RuleDistributionService ruleDistributionService(RuleVersionStore rules, FormTemplateStore templates, AuditPort audit,
                                                           TenantTransactions tx, Clock clock) {
        return new RuleDistributionService(rules, templates, audit, tx, clock);
    }

    @Bean
    public RuleApprovalService ruleApprovalService(RuleVersionStore rules, AuditPort audit, TenantTransactions tx, Clock clock) {
        return new RuleApprovalService(rules, audit, tx, clock);
    }

    @Bean
    public RuleActivationJob ruleActivationJob(RuleVersionStore rules, ComplianceFlagPort flags, AuditPort audit, TenantTransactions tx,
                                               Clock clock) {
        return new RuleActivationJob(rules, flags, audit, tx, clock);
    }

    @Bean
    public RuleBundleReconciler ruleBundleReconciler(RuleVersionStore rules, FormTemplateStore templates, ComplianceFlagPort flags,
                                                     AuditPort audit, TenantTransactions tx, Clock clock) {
        return new RuleBundleReconciler(rules, templates, flags, audit, tx, clock);
    }

    /** 테넌트 목록: 테넌트 디렉터리 전용 롤(disclosure_operator)로 별도 접속한다(설계서 §9). 데이터소스 빈을 만들지 않는다. */
    @Bean
    public TenantDirectory tenantDirectory(@Value("${ga.tenant-directory.url}") String url,
                                           @Value("${ga.tenant-directory.username}") String username,
                                           @Value("${ga.tenant-directory.password}") String password) {
        TenantDirectoryReader reader = new TenantDirectoryReader(url, username, password);
        return reader::allTenants;
    }
}
