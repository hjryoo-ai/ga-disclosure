package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.artifact.ArtifactMissingException;
import com.ga.disclosure.workflow.artifact.ArtifactRecord;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.ArtifactUnreadableException;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.disclosure.workflow.artifact.ObjectLockedException;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 봉인 산출물 유스케이스(3B 지시문 §5·§7): 열람(복호화 → 평문 해시 대조 → 감사), 잔여물 정리(gc — 커밋 실패로 남은 참조 없는 잠금 없는 객체),
 * Object Lock 재적용(reconcile — 커밋 후 잠금이 기록되지 않은 산출물). 열람 거부(키 파기·해시 불일치·객체 없음)도 감사가 커밋된 뒤 예외로 알린다.
 */
public final class ArtifactService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 열람 결과: 평문 바이트 또는 거부 사유. */
    public sealed interface View {
        record Granted(ArtifactRecord record, byte[] plaintext) implements View {
            public Granted {
                plaintext = plaintext.clone();
            }

            @Override
            public byte[] plaintext() {
                return plaintext.clone();
            }
        }

        record Denied(Reason reason) implements View {
        }

        enum Reason {
            /** 그 종류의 산출물이 없다(봉인 전 등). */
            NO_ARTIFACT,
            /** 문서 키가 파기됐다(crypto-shredding — 어떤 사본도 읽을 수 없다). */
            KEY_SHREDDED,
            /** 저장소에 객체가 없다. */
            OBJECT_MISSING,
            /** 기록된 문서 키·맥락으로 풀리지 않는다(변조·다른 객체·옮긴 바이트). */
            UNREADABLE,
            /** 복호화했지만 평문 해시가 기록과 다르다(키·암호문은 맞는데 내용이 다르다 — 기록 손상). */
            HASH_MISMATCH
        }
    }

    /** 잔여물 정리 결과(키는 테넌트·확인서 ID·종류·암호문 해시뿐이라 개인정보가 없다). */
    public record GcReport(int scanned, List<String> deleted, int referenced, int young, int locked) {
        public GcReport {
            deleted = List.copyOf(deleted);
        }
    }

    /** 재적용 결과. */
    public record ReconcileReport(int applied, int failed) {
    }

    private final DocumentRecordStore records;
    private final DocumentCryptoPort crypto;
    private final ArtifactStore storage;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;
    private final CommandRunner runner;
    private final Duration sealTransactionTimeout;

    public ArtifactService(DocumentRecordStore records, DocumentCryptoPort crypto, ArtifactStore storage, AuditPort audit,
                           WorkflowTransactions transactions, Clock clock, Duration sealTransactionTimeout) {
        this.records = Objects.requireNonNull(records, "records");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runner = new CommandRunner(transactions, audit, clock);
        this.sealTransactionTimeout = Objects.requireNonNull(sealTransactionTimeout, "sealTransactionTimeout");
    }

    // ------------------------------------------------------------------ 열람

    /** 복호화 후 평문 SHA-256이 기록과 같을 때만 내준다(감사 {@code ARTIFACT_VIEW}). 거부도 감사한다({@code ARTIFACT_VIEW_DENIED}). */
    public View view(TenantId tenant, Actor actor, DisclosureId id, ArtifactKind kind) {
        return runner.inTransaction(tenant, actor, "ARTIFACT_VIEW", id.toString(), () -> {
            Optional<ArtifactRecord> record = records.artifacts(id).stream().filter(a -> a.kind() == kind).findFirst();
            if (record.isEmpty()) {
                return deny(actor, id, kind, null, View.Reason.NO_ARTIFACT);
            }
            ArtifactRecord a = record.get();
            DocumentRecordStore.KeyLookup key = records.key(id);
            if (!(key instanceof DocumentRecordStore.KeyLookup.Live live)) {
                return deny(actor, id, kind, a.storageKey(), View.Reason.KEY_SHREDDED);
            }
            byte[] cipher;
            try {
                cipher = storage.get(a.storageKey());
            } catch (ArtifactMissingException e) {
                return deny(actor, id, kind, a.storageKey(), View.Reason.OBJECT_MISSING);
            }
            byte[] plaintext;
            try {
                plaintext = crypto.open(tenant, id, live.key(), kind, cipher);
            } catch (ArtifactUnreadableException e) {
                return deny(actor, id, kind, a.storageKey(), View.Reason.UNREADABLE);
            }
            if (!com.ga.platform.canonical.Sha256.of(plaintext).equals(a.sha256().hex())) {
                return deny(actor, id, kind, a.storageKey(), View.Reason.HASH_MISMATCH);
            }
            audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.ARTIFACT_VIEW, SealService.ARTIFACT_TARGET,
                    a.storageKey(), JSON.createObjectNode().put("disclosureId", id.toString()).put("kind", kind.name()).put("sha256", a.sha256().hex())));
            return new View.Granted(a, plaintext);
        });
    }

    private View deny(Actor actor, DisclosureId id, ArtifactKind kind, String keyOrNull, View.Reason reason) {
        ObjectNode detail = JSON.createObjectNode().put("disclosureId", id.toString()).put("kind", kind.name()).put("reason", reason.name());
        audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.ARTIFACT_VIEW_DENIED, SealService.ARTIFACT_TARGET,
                keyOrNull == null ? id.toString() : keyOrNull, detail));
        return new View.Denied(reason);
    }

    // ------------------------------------------------------------------ 잔여물 정리

    /**
     * 테넌트 접두 아래에서 <b>참조 행이 없고 마지막 수정이 {@code clock − grace}보다 오래된</b> 객체만 지운다(감사 {@code ARTIFACT_GC} 1건씩).
     * {@code grace}는 봉인 트랜잭션 제한보다 커야 한다 — 진행 중인 봉인이 올렸지만 아직 커밋하지 않은 객체를 지우지 않기 위해서다(그 객체는 언제나 제한보다
     * 젊다). 잠긴 객체는 저장소가 거부한다(세어 두고 넘어간다).
     */
    public GcReport gc(TenantId tenant, Actor actor, Duration grace) {
        if (grace.compareTo(sealTransactionTimeout) <= 0) {
            throw new IllegalArgumentException("gc grace " + grace + " must exceed the seal transaction timeout " + sealTransactionTimeout);
        }
        return runner.inTransaction(tenant, actor, "ARTIFACT_GC", tenant.value(), () -> {
            Instant cutoff = clock.instant().minus(grace);
            List<ArtifactStore.StoredObject> objects = storage.list(tenant.value() + "/");
            List<String> deleted = new java.util.ArrayList<>();
            int referenced = 0;
            int young = 0;
            int locked = 0;
            for (ArtifactStore.StoredObject o : objects) {
                if (records.referenced(o.key())) {
                    referenced++;
                    continue;
                }
                if (!o.lastModified().isBefore(cutoff)) {
                    young++;
                    continue;
                }
                try {
                    storage.delete(o.key());
                } catch (ObjectLockedException e) {
                    locked++;
                    continue;
                }
                deleted.add(o.key());
                audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.ARTIFACT_GC, SealService.ARTIFACT_TARGET,
                        o.key(), JSON.createObjectNode().put("lastModified", o.lastModified().toString()).put("size", o.size())
                                .put("grace", grace.toString())));
            }
            return new GcReport(objects.size(), deleted, referenced, young, locked);
        });
    }

    // ------------------------------------------------------------------ 재적용

    /** 커밋됐지만 Object Lock 적용이 기록되지 않은 산출물에 보존기한(봉인 때 정한 {@code retention_until})으로 다시 건다. */
    public ReconcileReport reconcile(TenantId tenant, Actor actor, int limit) {
        List<DocumentRecordStore.Unretained> pending = runner.inTransaction(tenant, actor, "ARTIFACT_RECONCILE", tenant.value(),
                () -> records.unretained(limit));
        int applied = 0;
        int failed = 0;
        for (DocumentRecordStore.Unretained u : pending) {
            Instant until = SealService.retainUntilInstant(u.retentionUntil());
            try {
                storage.applyRetention(u.record().storageKey(), until);
            } catch (RuntimeException e) {
                failed++;
                continue;
            }
            boolean marked = transactions.inTenant(tenant, () -> {
                boolean first = records.markRetentionApplied(u.record().disclosureId(), u.record().kind(), clock.instant(),
                        u.retentionUntil());
                if (first) {
                    audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.ARTIFACT_RETAIN,
                            SealService.ARTIFACT_TARGET, u.record().storageKey(), JSON.createObjectNode()
                            .put("disclosureId", u.record().disclosureId().toString()).put("kind", u.record().kind().name())
                            .put("retainUntil", until.toString()).put("reconciled", true)));
                }
                return first;
            });
            if (marked) {
                applied++;
            }
        }
        return new ReconcileReport(applied, failed);
    }
}
