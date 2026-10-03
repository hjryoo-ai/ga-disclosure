package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.disclosure.workflow.artifact.LockedObject;
import com.ga.disclosure.workflow.artifact.SignatureEvidenceRecord;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 커밋 후 Object Lock 적용(3B 봉인 순서, 4 계획 §7.1 9항·§7.3 6항): 산출물·서명 증거 객체(같은 코드 경로, 승인 Q2)마다 보존기한 당일 끝까지 잠금을
 * 걸고 적용을 기록한다(첫 적용 시각 1회, 적용 기한 증가만). 실패해도 업무는 유효하다 — 적용 기록이 남지 않아 재적용({@code artifacts reconcile}) 대상이
 * 되고, 사실을 감사한다({@code ARTIFACT_RETAIN_DEFERRED}).
 */
final class RetentionLocks {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 감사 {@code ARTIFACT_RETAIN.detail.reason}: 보존기한이 이미 끝나 저장소 잠금을 걸지 않았다(승인 Q6(b)). */
    static final String ELAPSED = "RETENTION_ALREADY_ELAPSED";

    private final DocumentRecordStore records;
    private final ArtifactStore storage;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;

    RetentionLocks(DocumentRecordStore records, ArtifactStore storage, AuditPort audit, WorkflowTransactions transactions, Clock clock) {
        this.records = Objects.requireNonNull(records, "records");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 객체마다 잠금을 건다. 하나라도 실패하면 true(재적용 대상이 남았다). */
    boolean apply(TenantId tenant, Actor actor, List<? extends LockedObject> objects, LocalDate retentionUntil) {
        boolean pending = false;
        Instant until = SealService.retainUntilInstant(retentionUntil);
        for (LockedObject o : objects) {
            if (!until.isAfter(clock.instant())) {
                // 5 계획 승인 Q6(b): 보존기한 당일이 이미 끝났다 — 저장소는 과거 기한을 거부하므로(400) 부르지 않고, 적용을 기록하되 사유를 남긴다.
                // 봉인·완료 직후에는 보존 합계 ≥ 1일이라 생기지 않는다(B3 감사 스캔이 운영 경로 0건을 확인한다).
                transactions.inTenant(tenant, () -> {
                    if (records.markRetentionApplied(o, clock.instant(), retentionUntil)) {
                        audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.ARTIFACT_RETAIN,
                                SealService.ARTIFACT_TARGET, o.storageKey(), detail(o).put("retainUntil", until.toString())
                                        .put("reason", ELAPSED)));
                    }
                    return null;
                });
                continue;
            }
            try {
                storage.applyRetention(o.storageKey(), until);
                transactions.inTenant(tenant, () -> {
                    if (records.markRetentionApplied(o, clock.instant(), retentionUntil)) {
                        audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.ARTIFACT_RETAIN,
                                SealService.ARTIFACT_TARGET, o.storageKey(), detail(o).put("retainUntil", until.toString())));
                    }
                    return null;
                });
            } catch (RuntimeException e) {
                pending = true;
                try {
                    transactions.inTenant(tenant, () -> audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(),
                            AuditAction.ARTIFACT_RETAIN_DEFERRED, SealService.ARTIFACT_TARGET, o.storageKey(),
                            detail(o).put("exception", e.getClass().getSimpleName()))));
                } catch (RuntimeException auditFailure) {
                    e.addSuppressed(auditFailure);
                }
            }
        }
        return pending;
    }

    private static ObjectNode detail(LockedObject o) {
        ObjectNode detail = JSON.createObjectNode().put("disclosureId", o.disclosureId().toString()).put("kind", o.kindName());
        if (o instanceof SignatureEvidenceRecord e) {
            detail.put("signatureId", e.signatureId().toString());
        }
        return detail;
    }
}
