package com.ga.disclosure.infra;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.infra.persistence.SealChainRepository;
import com.ga.disclosure.infra.retention.DestroyerGateway;
import com.ga.disclosure.infra.retention.ErasureRepository;
import com.ga.disclosure.infra.retention.LegalHoldRepository;
import com.ga.disclosure.infra.retention.RetentionRepository;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.retention.DestroyerPort;
import com.ga.disclosure.workflow.retention.DestructionJob;
import com.ga.disclosure.workflow.retention.LegalHoldService;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Phase 5 파기 통합 테스트 조립: 짧은 보존(0년 1일, 데모 번들과 같은 형태 — 5 계획 §8.7) 룰 변형 테넌트 위에 {@link SignSetup}. 시계는 과거(2026-09-23)라
 * 봉인·완료의 잠금은 저장소가 과거 기한으로 거부하고(재적용 대상으로 남는다), 보존이 끝난 뒤의 재적용이 {@code RETENTION_ALREADY_ELAPSED}로 기록한다 —
 * 데모 흐름(승인 Q6)과 같다. 이 경로를 일부러 쓰는 테넌트는 {@link #ELAPSED_TENANTS}에 올린다(B3 감사 스캔의 열거).
 */
final class RetentionSetup implements AutoCloseable {

    static final Actor OPERATOR = new Actor("ops-retention@test", "OPERATOR");
    /** 보류 해제자(4-eyes — 설정자와 다른 주체, V12 ck_legal_hold_four_eyes). */
    static final Actor RELEASER = new Actor("ops-retention-2@test", "OPERATOR");
    static final Actor SYSTEM = new Actor("system:destruction", "SYSTEM");
    /** B3: {@code RETENTION_ALREADY_ELAPSED}를 의도적으로 만드는 테넌트(감사 스캔이 이 밖의 발생을 실패로 본다). */
    static final Set<String> ELAPSED_TENANTS = ConcurrentHashMap.newKeySet();
    /** B3 대조군: 보존 종료 뒤 재적용을 실제로 부른 테넌트(스캔이 이 테넌트들의 발생을 반드시 봐야 한다). */
    static final Set<String> RECONCILED_TENANTS = ConcurrentHashMap.newKeySet();
    /** 보존이 끝난 뒤의 판정 시각(KST 2026-09-26 10:00). */
    static final Instant AFTER = Instant.parse("2026-09-26T01:00:00Z");

    final SignSetup x;
    final RetentionRepository store;
    final ErasureRepository erasure;
    final DestroyerGateway destroyer;
    final LegalHoldRepository holds;

    RetentionSetup() {
        this(body -> {
        });
    }

    RetentionSetup(Consumer<ObjectNode> edit) {
        this.x = new SignSetup(new SealSetup(WorkflowSetup.withRule(body -> {
            body.put("retentionYears", 0).put("retentionDays", 1);
            ((ObjectNode) body.get("retention")).put("contractLinkWaitDays", 0);
            ((ObjectNode) body.get("customerRef")).put("graceDaysAfterLastDestruction", 0);
            edit.accept(body);
        })));
        ELAPSED_TENANTS.add(x.w.tenant.value());
        this.store = new RetentionRepository(x.w.gateway);
        this.erasure = new ErasureRepository(x.w.gateway);
        this.destroyer = new DestroyerGateway(x.w.db.appDataSource());
        this.holds = new LegalHoldRepository(x.w.gateway);
    }

    /** 봉인 → 고객(터치) → 설계사 → 관리자 확인 → 완료. */
    DisclosureId completed() {
        DisclosureId id = x.sealed();
        if (!x.customerSignsOnTouchPad(id).accepted()) {
            throw new IllegalStateException("customer signature rejected");
        }
        x.clock.advance(Duration.ofMinutes(5));
        x.agentSigns(id);
        x.clock.advance(Duration.ofMinutes(5));
        if (!x.managerConfirms(id).completed()) {
            throw new IllegalStateException("not completed");
        }
        return id;
    }

    static Clock at(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    /** 보존이 끝난 뒤의 재적용: 저장소를 부르지 않고 적용 기한을 기록한다({@code RETENTION_ALREADY_ELAPSED}). */
    void reconcileAfterRetention() {
        reconcileAt(AFTER);
    }

    DestructionJob job() {
        return job(destroyer);
    }

    DestructionJob job(DestroyerPort destroyerPort) {
        return job(destroyerPort, AFTER);
    }

    DestructionJob job(DestroyerPort destroyerPort, Instant clock) {
        return new DestructionJob(store, erasure, destroyerPort, x.s.records, x.s.store, new RuleResolver(x.w.rules), x.w.audit, x.w.outbox, x.w.tx,
                at(clock));
    }

    /** 시계와 판정 시각이 같은 실행. */
    DestructionJob.Report destroyAt(Instant asOf) {
        return conforming(job(destroyer, asOf).run(x.w.tenant, asOf, false, SYSTEM, 100));
    }

    /** {@code asOf} 시계의 재적용(보존 종료 뒤면 {@code RETENTION_ALREADY_ELAPSED}). */
    void reconcileAt(Instant asOf) {
        x.s.artifactsAt(at(asOf), x.s.store).reconcile(x.w.tenant, OPERATOR, 1000);
        if (!asOf.isBefore(AFTER)) {
            RECONCILED_TENANTS.add(x.w.tenant.value());
        }
    }

    DestructionJob.Report destroy() {
        return conforming(job().run(x.w.tenant, AFTER, false, SYSTEM, 100));
    }

    /** 보고서는 계약 스키마({@code contracts/verify/v1/destruction-report.schema.json})를 따른다 — 이 조립의 모든 실행에서 확인한다. */
    static DestructionJob.Report conforming(DestructionJob.Report report) {
        java.util.List<String> errors = com.ga.disclosure.audit.verify.VerifySchemas.destructionReport(report.toJson());
        if (!errors.isEmpty()) {
            throw new AssertionError("destruction report violates its schema: " + errors);
        }
        return report;
    }

    LegalHoldService holdService(Clock clock) {
        return new LegalHoldService(holds, store, x.s.records, x.s.store, new RuleResolver(x.w.rules), x.w.audit, x.w.tx, clock, UUID::randomUUID);
    }

    SealChainRepository chain() {
        return new SealChainRepository(x.w.gateway);
    }

    long count(String sql, Object... params) {
        return x.s.count(sql, params);
    }

    String text(String sql, Object... params) {
        return x.s.text(sql, params);
    }

    @Override
    public void close() {
        x.close();
    }
}
