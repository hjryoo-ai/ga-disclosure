package com.ga.disclosure.workflow.artifact;

import com.ga.disclosure.domain.enums.SignatureEvidenceKind;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.Sha256;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 서명 증거 객체 기록(V8 {@code signature_evidence}, 승인 Q2): 1객체 1행. 확인서 문서 키로 암호화된다(3B 수용심사 §3-4 — AAD = 테넌트·확인서·서명·종류).
 *
 * @param storageKey {@code {tenant}/{disclosure}/SIG/{signature}/{kind}/{cipher_sha256}}(V8 CHECK)
 */
public record SignatureEvidenceRecord(DisclosureId disclosureId, UUID signatureId, SignatureEvidenceKind kind, String storageKey, Sha256 sha256,
                                      long bytes, Sha256 cipherSha256, long cipherBytes, String keyId, Instant createdAt,
                                      Instant retentionAppliedAtOrNull) implements LockedObject {

    public SignatureEvidenceRecord {
        Objects.requireNonNull(disclosureId, "disclosureId");
        Objects.requireNonNull(signatureId, "signatureId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(cipherSha256, "cipherSha256");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public static String storageKey(String tenantId, DisclosureId disclosure, UUID signatureId, SignatureEvidenceKind kind, Sha256 cipherSha256) {
        return tenantId + "/" + disclosure.value() + "/SIG/" + signatureId + "/" + kind.name() + "/" + cipherSha256.hex();
    }

    @Override
    public String kindName() {
        return kind.name();
    }

    public Optional<Instant> retentionAppliedAt() {
        return Optional.ofNullable(retentionAppliedAtOrNull);
    }
}
