package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.enums.SignatureEvidenceKind;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.artifact.ArtifactRecord;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.disclosure.workflow.artifact.SignatureEvidenceRecord;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;

import java.util.Objects;
import java.util.UUID;

/**
 * 업무 경로 안의 저장 객체 읽기(서명 대상 PDF 제공, 완료 때 원본·서명 이미지): 기록 → 살아 있는 문서 키 → 객체 → 복호화 → 평문 해시 대조. 열람
 * 유스케이스({@link ArtifactService#view})와 같은 순서지만 거부를 결과로 돌려주지 않는다 — 여기서 읽지 못하면 그 명령을 계속할 수 없으므로 명령 오류다
 * (복호화 실패 {@code ArtifactUnreadableException}, 객체 없음 {@code ArtifactMissingException}, 평문 해시 불일치).
 * 감사는 호출자가 목적에 맞게 남긴다.
 */
final class StoredArtifacts {

    private final DocumentRecordStore records;
    private final DocumentCryptoPort crypto;
    private final ArtifactStore storage;

    StoredArtifacts(DocumentRecordStore records, DocumentCryptoPort crypto, ArtifactStore storage) {
        this.records = Objects.requireNonNull(records, "records");
        this.crypto = Objects.requireNonNull(crypto, "crypto");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    DocumentCryptoPort.StoredKey liveKey(DisclosureId id) {
        return records.liveKey(id).orElseThrow(() -> new IllegalStateException("document key of " + id + " is not live"));
    }

    /** 산출물 기록과 평문. */
    Read<ArtifactRecord> artifact(TenantId tenant, DisclosureId id, ArtifactKind kind) {
        ArtifactRecord a = records.artifacts(id).stream().filter(r -> r.kind() == kind).findFirst()
                .orElseThrow(() -> new IllegalStateException("disclosure " + id + " has no " + kind));
        byte[] plain = crypto.open(tenant, id, liveKey(id), kind, storage.get(a.storageKey()));
        return new Read<>(a, verified(plain, a.sha256().hex(), a.storageKey()));
    }

    /** 서명 증거 객체 평문. */
    byte[] evidence(TenantId tenant, SignatureEvidenceRecord e) {
        byte[] plain = crypto.openEvidence(tenant, e.disclosureId(), liveKey(e.disclosureId()), e.signatureId(), e.kind(), storage.get(e.storageKey()));
        return verified(plain, e.sha256().hex(), e.storageKey());
    }

    /** 서명의 그 종류 증거 객체(없으면 빈 값). */
    java.util.Optional<SignatureEvidenceRecord> evidenceRecord(DisclosureId id, UUID signatureId, SignatureEvidenceKind kind) {
        return records.evidence(id).stream().filter(e -> e.signatureId().equals(signatureId) && e.kind() == kind).findFirst();
    }

    private static byte[] verified(byte[] plain, String expected, String key) {
        if (!Sha256.of(plain).equals(expected)) {
            throw new IllegalStateException("plaintext hash of " + key + " differs from its record");
        }
        return plain;
    }

    record Read<T>(T record, byte[] plaintext) {
    }
}
