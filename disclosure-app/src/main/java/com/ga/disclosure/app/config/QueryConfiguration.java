package com.ga.disclosure.app.config;

import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.infra.crypto.CursorCodec;
import com.ga.disclosure.infra.crypto.RequestHashKey;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.disclosure.DisclosureLookup;
import com.ga.disclosure.workflow.disclosure.DisclosureQueryService;
import com.ga.disclosure.workflow.feed.EventFeed;
import com.ga.disclosure.workflow.feed.EventFeedStore;
import com.ga.disclosure.workflow.flag.FlagLookup;
import com.ga.disclosure.workflow.flag.FlagQueryService;
import com.ga.disclosure.workflow.idempotency.RequestHashPort;
import com.ga.disclosure.workflow.page.CursorPort;
import com.ga.disclosure.workflow.retention.LegalHoldQueryService;
import com.ga.disclosure.workflow.retention.LegalHoldStore;
import com.ga.disclosure.workflow.sign.PublicSignLimits;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

/**
 * 6A 조회 조립(계획 §4.1·§4.2): 확인서·보류·플래그 목록과 상세, 서명된 목록 커서, 이벤트 피드. 커서 키는 웹이면 {@code ga.api.cursor-key-file}(기본값 없음 — 저장소 밖, 없으면
 * 소유자 전용으로 만들고 권한이 넓으면 기동 실패), CLI면 프로세스마다 새 키(CLI는 커서를 받지 않는다).
 */
@Configuration
public class QueryConfiguration {

    @Bean
    @ConditionalOnWebApplication
    public CursorPort cursorCodec(@Value("${ga.api.cursor-key-file}") String keyFile) {
        return CursorCodec.fromKeyFile(Path.of(keyFile));
    }

    /** 멱등 요청 해시 키(6B 계획 §A-2) — 웹이면 {@code ga.api.request-hash-key-file}(기본값 없음, 커서 키와 같은 규약). CLI는 멱등 키를 받지 않는다. */
    @Bean
    @ConditionalOnWebApplication
    public RequestHashPort requestHashKey(@Value("${ga.api.request-hash-key-file}") String keyFile) {
        return RequestHashKey.fromKeyFile(Path.of(keyFile));
    }

    @Bean
    @ConditionalOnNotWebApplication
    public CursorPort ephemeralCursorCodec() {
        return CursorCodec.ephemeral();
    }

    @Bean
    public DisclosureQueryService disclosureQueryService(DisclosureLookup lookup, AuditPort audit, WorkflowTransactions tx, Clock clock,
                                                         AuthorizationPort authz, CursorPort cursors) {
        return new DisclosureQueryService(lookup, audit, tx, clock, authz, cursors);
    }

    /** 공개 서명 경로의 테넌트 분당 한도(룰 {@code publicSign.tenantRatePerMinute}, 6A 계획 §5.4). */
    @Bean
    public PublicSignLimits publicSignLimits(RuleResolver rules, WorkflowTransactions tx, Clock clock) {
        return new PublicSignLimits(rules, tx, clock);
    }

    /** 이벤트 피드(정수 afterSeq·nextSeq·headSeq — 승인 Q5)와 ack. */
    @Bean
    public EventFeed eventFeed(EventFeedStore store, AuditPort audit, WorkflowTransactions tx, Clock clock, AuthorizationPort authz) {
        return new EventFeed(store, audit, tx, clock, authz);
    }

    @Bean
    public LegalHoldQueryService legalHoldQueryService(LegalHoldStore holds, WorkflowTransactions tx, AuthorizationPort authz, CursorPort cursors) {
        return new LegalHoldQueryService(holds, tx, authz, cursors);
    }

    /** 준법 플래그 조회 — 관리자·준법 전용(6A 수용심사 §2 ①). */
    @Bean
    public FlagQueryService flagQueryService(FlagLookup flags, WorkflowTransactions tx, AuthorizationPort authz, CursorPort cursors) {
        return new FlagQueryService(flags, tx, authz, cursors);
    }

    /** 계약 연결 가져오기·미매칭 정리(6B 계획 §4) — HTTP·CLI 공용. */
    @Bean
    public com.ga.disclosure.workflow.contract.ContractLinkService contractLinkService(com.ga.disclosure.workflow.contract.ContractLinkStore store,
            com.ga.disclosure.rules.resolve.RuleResolver rules, com.ga.disclosure.audit.AuditPort audit, com.ga.disclosure.audit.outbox.OutboxPort outbox,
            WorkflowTransactions tx, AuthorizationPort authz, java.time.Clock clock) {
        return new com.ga.disclosure.workflow.contract.ContractLinkService(store, rules, audit, outbox, tx, authz, clock, java.util.UUID::randomUUID);
    }

    /** 징구율 스냅샷·조회(6B 계획 §5) — 내부 지표(규제 정의 없음), HTTP·CLI 공용. */
    @Bean
    public com.ga.disclosure.workflow.rate.CollectionRateService collectionRateService(com.ga.disclosure.workflow.rate.CollectionRateStore store,
            com.ga.disclosure.rules.resolve.RuleResolver rules, com.ga.disclosure.audit.AuditPort audit, WorkflowTransactions tx, AuthorizationPort authz,
            java.time.Clock clock) {
        return new com.ga.disclosure.workflow.rate.CollectionRateService(store, rules, audit, tx, authz, clock, java.util.UUID::randomUUID);
    }

    /** 보존 재계산(6B 계획 §8) — 준법·운영자, 기본 dry-run, 연장만. */
    @Bean
    public com.ga.disclosure.workflow.disclosure.RetentionRecomputeService retentionRecomputeService(
            com.ga.disclosure.workflow.disclosure.RetentionRecomputeStore store, com.ga.disclosure.rules.version.RuleVersionPort versions,
            com.ga.disclosure.workflow.artifact.DocumentRecordStore records, com.ga.disclosure.workflow.artifact.ArtifactStore storage,
            com.ga.disclosure.audit.AuditPort audit, WorkflowTransactions tx, AuthorizationPort authz, java.time.Clock clock) {
        return new com.ga.disclosure.workflow.disclosure.RetentionRecomputeService(store, versions, records, storage, audit, tx, authz, clock);
    }

    /** 청약 게이트(6B 계획 §6) — 게이트 서비스 주체 전용, 요청마다 감사. 한도 창은 인스턴스 메모리라 빈 하나. */
    @Bean
    public com.ga.disclosure.workflow.gate.GateService gateService(com.ga.disclosure.workflow.gate.GateLookup lookup,
            com.ga.disclosure.rules.resolve.RuleResolver rules, com.ga.disclosure.audit.AuditPort audit, WorkflowTransactions tx, AuthorizationPort authz,
            java.time.Clock clock) {
        return new com.ga.disclosure.workflow.gate.GateService(lookup, rules, audit, tx, authz, clock);
    }

    /** 준법 큐 명령(6B 계획 §7): 배정·수동 해소·SLA 경과 표시 — 해소 코드·근거 필요 여부는 해소 시점의 룰. */
    @Bean
    public com.ga.disclosure.workflow.flag.FlagCommandService flagCommandService(FlagLookup flags,
            com.ga.disclosure.workflow.flag.FlagCommandPort commands, com.ga.disclosure.workflow.flag.VerifyEvidencePort verify,
            com.ga.disclosure.workflow.identity.AgentDirectory directory, com.ga.disclosure.rules.resolve.RuleResolver rules,
            com.ga.disclosure.audit.AuditPort audit, WorkflowTransactions tx, AuthorizationPort authz, java.time.Clock clock) {
        return new com.ga.disclosure.workflow.flag.FlagCommandService(flags, commands, verify, directory, rules, audit, tx, authz, clock);
    }
}
