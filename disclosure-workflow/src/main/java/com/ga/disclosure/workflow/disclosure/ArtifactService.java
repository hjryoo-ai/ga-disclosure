package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.enums.SignatureEvidenceKind;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.artifact.ArtifactMissingException;
import com.ga.disclosure.workflow.artifact.ArtifactRecord;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.ArtifactUnreadableException;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.disclosure.workflow.artifact.LockedObject;
import com.ga.disclosure.workflow.artifact.ObjectLockedException;
import com.ga.disclosure.workflow.artifact.SignatureEvidenceRecord;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.seal.renderer.PreviewWatermarker;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 봉인 산출물 유스케이스(3B 지시문 §5·§7): 열람(복호화 → 평문 해시 대조 → 감사), 잔여물 정리(gc — 커밋 실패로 남은 참조 없는 잠금 없는 객체),
 * Object Lock 재적용(reconcile — 커밋 후 잠금이 기록되지 않은 산출물). 열람 거부(키 파기·해시 불일치·객체 없음)도 감사가 커밋된 뒤 예외로 알린다.
 */
public final class ArtifactService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final java.time.ZoneId KST = java.time.ZoneId.of("Asia/Seoul");
    private static final java.time.format.DateTimeFormatter MINUTE = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 열람 결과: 평문 바이트 또는 거부 사유. */
    public sealed interface View {
        record Granted(LockedObject record, byte[] plaintext) implements View {
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

        /** 열람 거부 사유 — 모두 상태 충돌(설계서 {@code rejection-categories}의 {@code ArtifactView} 행). */
        enum Reason implements com.ga.disclosure.workflow.RejectionCategory.Categorized {
            /** 그 종류의 산출물이 없다(봉인 전 등). */
            NO_ARTIFACT,
            /** 문서 키가 파기됐다(crypto-shredding — 어떤 사본도 읽을 수 없다). */
            KEY_SHREDDED,
            /** 저장소에 객체가 없다. */
            OBJECT_MISSING,
            /** 기록된 문서 키·맥락으로 풀리지 않는다(변조·다른 객체·옮긴 바이트). */
            UNREADABLE,
            /** 복호화했지만 평문 해시가 기록과 다르다(키·암호문은 맞는데 내용이 다르다 — 기록 손상). */
            HASH_MISMATCH;

            @Override
            public com.ga.disclosure.workflow.RejectionCategory category() {
                return com.ga.disclosure.workflow.RejectionCategory.CONFLICT;
            }
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
    private final AuthorizationPort authz;
    private final RuleResolver rules;
    private final PreviewWatermarker watermarker;

    public ArtifactService(DocumentRecordStore records, DocumentCryptoPort crypto, ArtifactStore storage, AuditPort audit,
                           WorkflowTransactions transactions, Clock clock, Duration sealTransactionTimeout, AuthorizationPort authz,
                           RuleResolver rules, PreviewWatermarker watermarker) {
        this.records = Objects.requireNonNull(records, "records");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.runner = new CommandRunner(transactions, audit, clock);
        this.sealTransactionTimeout = Objects.requireNonNull(sealTransactionTimeout, "sealTransactionTimeout");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.watermarker = Objects.requireNonNull(watermarker, "watermarker");
    }

    // ------------------------------------------------------------------ 열람

    /** 복호화 후 평문 SHA-256이 기록과 같을 때만 내준다(감사 {@code ARTIFACT_VIEW}). 거부도 감사한다({@code ARTIFACT_VIEW_DENIED}). */
    @UseCaseEntry(Action.ARTIFACT_VIEW)
    public View view(Caller caller, DisclosureId id, ArtifactKind kind) {
        return runner.inTransaction(caller, "ARTIFACT_VIEW", id.toString(), attempt -> {
            Actor actor = attempt.granted(authz.require(caller, Action.ARTIFACT_VIEW, Target.disclosure(id)));
            View read = read(actor, caller.tenant(), id, kind, Optional.empty());
            if (read instanceof View.Granted g) {
                audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.ARTIFACT_VIEW, SealService.ARTIFACT_TARGET,
                        g.record().storageKey(), JSON.createObjectNode().put("disclosureId", id.toString()).put("kind", kind.name())
                                .put("sha256", ((ArtifactRecord) g.record()).sha256().hex())));
            }
            return read;
        });
    }

    /**
     * 화면 미리보기(Phase 7 계획 ⑥, 지시문 §4): 봉인 PDF(서명본이 있어도 봉인본 — 종류 {@code PDF})를 {@link #view}와 같은 순서로 읽고 검증한 뒤, 쪽마다
     * 룰 {@code preview.watermark}의 문구(열람자 역할 표기 · 열람 시각 KST)를 얹은 <b>새</b> 바이트를 돌려준다 — 저장하지 않는다(산출물·증거 패키지·공개 서명
     * PDF는 바이트 그대로). 감사 {@code ARTIFACT_VIEW}는 원본의 종류·해시에 목적 {@link ArtifactViewPurpose#PREVIEW}. 거부는 열람과 같다.
     * {@link View.Granted#plaintext()}가 워터마크를 얹은 바이트다.
     */
    @UseCaseEntry(Action.ARTIFACT_VIEW)
    public View preview(Caller caller, DisclosureId id) {
        return runner.inTransaction(caller, "ARTIFACT_VIEW", id.toString(), attempt -> {
            Actor actor = attempt.granted(authz.require(caller, Action.ARTIFACT_VIEW, Target.disclosure(id)));
            View read = read(actor, caller.tenant(), id, ArtifactKind.PDF, Optional.of(ArtifactViewPurpose.PREVIEW));
            if (!(read instanceof View.Granted g)) {
                return read;
            }
            ArtifactRecord original = (ArtifactRecord) g.record();
            java.time.ZonedDateTime at = clock.instant().atZone(KST);
            String text = rules.resolve(caller.tenant(), at.toLocalDate()).previewWatermark().render(actor.role(), MINUTE.format(at));
            audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.ARTIFACT_VIEW, SealService.ARTIFACT_TARGET,
                    original.storageKey(), JSON.createObjectNode().put("disclosureId", id.toString()).put("kind", ArtifactKind.PDF.name())
                            .put("sha256", original.sha256().hex()).put("reason", ArtifactViewPurpose.PREVIEW.name())));
            return new View.Granted(original, watermarker.watermark(g.plaintext(), text));
        });
    }

    /** 기록 → 살아 있는 문서 키 → 객체 → 복호화 → 평문 해시 대조. 거부는 감사하고 {@link View.Denied}, 허용은 감사 없이 평문(호출자가 목적에 맞게 감사한다). */
    private View read(Actor actor, TenantId tenant, DisclosureId id, ArtifactKind kind, Optional<ArtifactViewPurpose> purpose) {
        Optional<ArtifactRecord> record = records.artifacts(id).stream().filter(a -> a.kind() == kind).findFirst();
        if (record.isEmpty()) {
            return deny(actor, id, kind.name(), null, View.Reason.NO_ARTIFACT, purpose);
        }
        ArtifactRecord a = record.get();
        DocumentRecordStore.KeyLookup key = records.key(id);
        if (!(key instanceof DocumentRecordStore.KeyLookup.Live live)) {
            return deny(actor, id, kind.name(), a.storageKey(), View.Reason.KEY_SHREDDED, purpose);
        }
        byte[] cipher;
        try {
            cipher = storage.get(a.storageKey());
        } catch (ArtifactMissingException e) {
            return deny(actor, id, kind.name(), a.storageKey(), View.Reason.OBJECT_MISSING, purpose);
        }
        byte[] plaintext;
        try {
            plaintext = crypto.open(tenant, id, live.key(), kind, cipher);
        } catch (ArtifactUnreadableException e) {
            return deny(actor, id, kind.name(), a.storageKey(), View.Reason.UNREADABLE, purpose);
        }
        if (!com.ga.platform.canonical.Sha256.of(plaintext).equals(a.sha256().hex())) {
            return deny(actor, id, kind.name(), a.storageKey(), View.Reason.HASH_MISMATCH, purpose);
        }
        return new View.Granted(a, plaintext);
    }

    /**
     * 서명 증거 객체 열람(준법·분쟁 대응): 산출물 열람과 같은 순서 — 기록 → 살아 있는 문서 키 → 객체 → 복호화(AAD = 서명·종류) → 평문 해시 대조,
     * 허용·거부 모두 감사. 감사 상세에는 서명 ID·종류·해시만 남는다.
     */
    @UseCaseEntry(Action.ARTIFACT_VIEW)
    public View viewEvidence(Caller caller, DisclosureId id, UUID signatureId, SignatureEvidenceKind kind) {
        TenantId tenant = caller.tenant();
        return runner.inTransaction(caller, "ARTIFACT_VIEW", id.toString(), attempt -> {
            Actor actor = attempt.granted(authz.require(caller, Action.ARTIFACT_VIEW, Target.disclosure(id)));
            Optional<SignatureEvidenceRecord> record = records.evidence(id).stream()
                    .filter(e -> e.signatureId().equals(signatureId) && e.kind() == kind).findFirst();
            if (record.isEmpty()) {
                return deny(actor, id, kind.name(), null, View.Reason.NO_ARTIFACT);
            }
            SignatureEvidenceRecord e = record.get();
            DocumentRecordStore.KeyLookup key = records.key(id);
            if (!(key instanceof DocumentRecordStore.KeyLookup.Live live)) {
                return deny(actor, id, kind.name(), e.storageKey(), View.Reason.KEY_SHREDDED);
            }
            byte[] cipher;
            try {
                cipher = storage.get(e.storageKey());
            } catch (ArtifactMissingException missing) {
                return deny(actor, id, kind.name(), e.storageKey(), View.Reason.OBJECT_MISSING);
            }
            byte[] plaintext;
            try {
                plaintext = crypto.openEvidence(tenant, id, live.key(), signatureId, kind, cipher);
            } catch (ArtifactUnreadableException unreadable) {
                return deny(actor, id, kind.name(), e.storageKey(), View.Reason.UNREADABLE);
            }
            if (!com.ga.platform.canonical.Sha256.of(plaintext).equals(e.sha256().hex())) {
                return deny(actor, id, kind.name(), e.storageKey(), View.Reason.HASH_MISMATCH);
            }
            audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.ARTIFACT_VIEW, SealService.ARTIFACT_TARGET,
                    e.storageKey(), JSON.createObjectNode().put("disclosureId", id.toString()).put("signatureId", signatureId.toString())
                            .put("kind", kind.name()).put("sha256", e.sha256().hex())));
            return new View.Granted(e, plaintext);
        });
    }

    private View deny(Actor actor, DisclosureId id, String kind, String keyOrNull, View.Reason reason) {
        return deny(actor, id, kind, keyOrNull, reason, Optional.empty());
    }

    private View deny(Actor actor, DisclosureId id, String kind, String keyOrNull, View.Reason reason, Optional<ArtifactViewPurpose> purpose) {
        ObjectNode detail = JSON.createObjectNode().put("disclosureId", id.toString()).put("kind", kind).put("reason", reason.name());
        purpose.ifPresent(p -> detail.put("purpose", p.name()));
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
    @UseCaseEntry(Action.ARTIFACT_GC)
    public GcReport gc(Caller caller, Duration grace) {
        TenantId tenant = caller.tenant();
        if (grace.compareTo(sealTransactionTimeout) <= 0) {
            throw new IllegalArgumentException("gc grace " + grace + " must exceed the seal transaction timeout " + sealTransactionTimeout);
        }
        return runner.inTransaction(caller, "ARTIFACT_GC", tenant.value(), attempt -> {
            Actor actor = attempt.granted(authz.require(caller, Action.ARTIFACT_GC, Target.none()));
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

    /**
     * 커밋됐지만 지금 보존기한까지 Object Lock 적용이 기록되지 않은 객체(산출물·서명 증거 — 같은 코드 경로, 4 계획 승인 Q2)에 확인서의
     * {@code retention_until}로 다시 건다. 기한이 연장된 뒤(완료·계약 연결) 다시 거는 것도 이 경로다(적용 기한은 증가만).
     */
    @UseCaseEntry(Action.ARTIFACT_RECONCILE)
    public ReconcileReport reconcile(Caller caller, int limit) {
        TenantId tenant = caller.tenant();
        record Pending(Actor actor, List<DocumentRecordStore.Unretained> rows) {
        }
        Pending p = runner.inTransaction(caller, "ARTIFACT_RECONCILE", tenant.value(),
                attempt -> new Pending(attempt.granted(authz.require(caller, Action.ARTIFACT_RECONCILE, Target.none())), records.unretained(limit)));
        Actor actor = p.actor();
        List<DocumentRecordStore.Unretained> pending = p.rows();
        int applied = 0;
        int failed = 0;
        for (DocumentRecordStore.Unretained u : pending) {
            Instant until = SealService.retainUntilInstant(u.retentionUntil());
            boolean elapsed = !until.isAfter(clock.instant());              // 승인 Q6(b): 이미 끝난 보존 — 저장소를 부르지 않고 사유와 함께 기록
            if (!elapsed) {
                try {
                    storage.applyRetention(u.record().storageKey(), until);
                } catch (RuntimeException e) {
                    failed++;
                    continue;
                }
            }
            boolean marked = transactions.inTenant(tenant, () -> {
                boolean first = records.markRetentionApplied(u.record(), clock.instant(), u.retentionUntil());
                if (first) {
                    ObjectNode detail = JSON.createObjectNode().put("disclosureId", u.record().disclosureId().toString())
                            .put("kind", u.record().kindName()).put("retainUntil", until.toString()).put("reconciled", true);
                    if (u.record() instanceof SignatureEvidenceRecord e) {
                        detail.put("signatureId", e.signatureId().toString());
                    }
                    if (elapsed) {
                        detail.put("reason", RetentionLocks.ELAPSED);
                    }
                    audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.ARTIFACT_RETAIN,
                            SealService.ARTIFACT_TARGET, u.record().storageKey(), detail));
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
