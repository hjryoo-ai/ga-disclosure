package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.infra.crypto.DocumentCipher;
import com.ga.disclosure.infra.persistence.DocumentRecordRepository;
import com.ga.disclosure.infra.persistence.SealLedgerRepository;
import com.ga.disclosure.infra.storage.S3ArtifactStore;
import com.ga.disclosure.infra.testing.SeaweedHarness;
import com.ga.disclosure.seal.renderer.DisclosurePdfRenderer;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.disclosure.LifecycleService;
import com.ga.disclosure.workflow.disclosure.SealService;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Phase 3B 통합 테스트 조립: {@link WorkflowSetup}(새 테넌트·번들·카탈로그·고객·가짜 엔진) 위에 실제 어댑터로 봉인·정정·무효·산출물 유스케이스를
 * 만든다 — 채번·체인({@link SealLedgerRepository}), 문서 키·산출물 기록({@link DocumentRecordRepository}), 문서 암호화({@link DocumentCipher},
 * 같은 로컬 KEK), SeaweedFS 버킷({@link SeaweedHarness}, 조립마다 새 버킷), 결정론 렌더러. 실패 주입은 저장소·기록 포트를 감싸서 한다.
 */
final class SealSetup implements AutoCloseable {

    static final Actor MANAGER = WorkflowSetup.MANAGER;
    static final Actor COMPLIANCE = new Actor("compliance-1@test", "COMPLIANCE");

    final WorkflowSetup w;
    final SeaweedHarness s3 = SeaweedHarness.get();
    final S3ArtifactStore bucket;
    final SealLedgerRepository ledger;
    final DocumentRecordRepository records;
    final DocumentCipher cipher;
    /** 실패 주입 지점(기본은 그대로 통과): 커밋 후 잠금 실패, 업로드 뒤·커밋 전 실패. */
    final FailingPorts.Store store;
    final FailingPorts.Records recordPort;
    final SealService seal;
    final LifecycleService lifecycle;
    final ArtifactService artifacts;

    SealSetup() {
        this(new WorkflowSetup());
    }

    SealSetup(WorkflowSetup w) {
        this.w = w;
        this.bucket = s3.freshStore();
        this.ledger = new SealLedgerRepository(w.gateway);
        this.records = new DocumentRecordRepository(w.gateway);
        this.cipher = new DocumentCipher(w.keys);
        this.store = new FailingPorts.Store(bucket);
        this.recordPort = new FailingPorts.Records(records, bucket);
        this.seal = new SealService(w.deps(w.clock), ledger, cipher, recordPort, store, new DisclosurePdfRenderer(), carry(w));
        this.lifecycle = new LifecycleService(w.deps(w.clock), new com.ga.disclosure.infra.persistence.SignSessionRepository(w.gateway));
        this.artifacts = artifactsAt(w.clock, store);
    }

    ArtifactService artifactsAt(Clock clock, ArtifactStore store) {
        return new ArtifactService(records, cipher, store, w.audit, w.tx, clock, SealService.DEFAULT_TRANSACTION_TIMEOUT, Callers.authz(clock),
                new com.ga.disclosure.rules.resolve.RuleResolver(new com.ga.disclosure.infra.persistence.RuleVersionRepository(w.gateway)),
                new com.ga.disclosure.seal.renderer.PreviewWatermarker());
    }

    SealService sealAt(Clock clock, ArtifactStore store, DocumentRecordStore recordStore) {
        return new SealService(w.deps(clock), ledger, cipher, recordStore, store, new DisclosurePdfRenderer(), carry(w));
    }

    SealService.Outcome sealReasoned() {
        DisclosureId id = w.reasoned();
        return seal.seal(Callers.of(w.tenant, WorkflowSetup.AGENT), id);
    }

    /** 감사 행 중 대상이 이 확인서인 것의 동작들(순서대로). */
    List<AuditAction> actionsFor(DisclosureId id) {
        return w.auditLog().stream().filter(r -> id.toString().equals(r.entry().targetId())).map(r -> r.entry().action()).toList();
    }

    List<AuditRecord> audit() {
        return w.auditLog();
    }

    long count(String sql, Object... params) {
        return w.db.asApp(w.tenant.value(), c -> com.ga.disclosure.infra.testing.SeedData.longValue(c, sql, params));
    }

    String text(String sql, Object... params) {
        return w.db.asApp(w.tenant.value(), c -> {
            try (var ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    List<com.ga.disclosure.workflow.artifact.ArtifactRecord> artifactsOf(DisclosureId id) {
        return w.in(() -> records.artifacts(id));
    }

    /** 테넌트 버킷의 객체 수. */
    int objects() {
        return bucket.list(w.tenant.value() + "/").size();
    }

    static Duration grace() {
        return Duration.ofHours(24);
    }

    @Override
    public void close() {
        bucket.close();
        w.close();
    }

    /** 정정 새 버전 봉인 때 계약 연결 이월(6B 중간 회신 ③) — 운영 구성과 같은 구현. */
    static com.ga.disclosure.workflow.disclosure.LinkCarry carry(WorkflowSetup w) {
        return new com.ga.disclosure.workflow.contract.ContractLinkCarrier(
                new com.ga.disclosure.infra.persistence.ContractLinkRepository(w.gateway, w.disclosures), w.audit, w.outbox, java.util.UUID::randomUUID);
    }
}
