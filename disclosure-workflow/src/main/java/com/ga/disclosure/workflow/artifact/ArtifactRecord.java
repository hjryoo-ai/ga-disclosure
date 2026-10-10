package com.ga.disclosure.workflow.artifact;

import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.Sha256;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 산출물 기록(V7 {@code document_artifact}): 평문 해시·길이(체인·열람 대조), 암호문 해시·길이(저장 바이트), 문서 키 ID, 객체 키, Object Lock 적용 시각.
 *
 * @param storageKey      {@code {tenant}/{disclosure}/{kind}/{cipher_sha256}}(승인 Q4)
 * @param rendererVersion 그 문서의 렌더러 판(Phase 8 V23 — 봉인 때 정해지고 문서의 모든 산출물이 같은 값, write-once)
 */
public record ArtifactRecord(DisclosureId disclosureId, ArtifactKind kind, String storageKey, Sha256 sha256, long bytes, Sha256 cipherSha256,
                             long cipherBytes, String keyId, Instant createdAt, Instant retentionAppliedAtOrNull,
                             com.ga.disclosure.seal.renderer.RendererVersion rendererVersion) implements LockedObject {

    public ArtifactRecord {
        Objects.requireNonNull(disclosureId, "disclosureId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(cipherSha256, "cipherSha256");
        Objects.requireNonNull(keyId, "keyId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(rendererVersion, "rendererVersion");
    }

    public static String storageKey(String tenantId, DisclosureId disclosure, ArtifactKind kind, Sha256 cipherSha256) {
        return tenantId + "/" + disclosure.value() + "/" + kind.name() + "/" + cipherSha256.hex();
    }

    @Override
    public String kindName() {
        return kind.name();
    }

    public Optional<Instant> retentionAppliedAt() {
        return Optional.ofNullable(retentionAppliedAtOrNull);
    }
}
