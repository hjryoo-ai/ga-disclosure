package com.ga.disclosure.workflow.artifact;

import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.enums.SignatureEvidenceKind;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.platform.core.tenant.TenantId;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 문서 데이터 키 암호화 포트(3A 수용심사 §3-5): 확인서마다 새 DEK(AES-256)를 만들고 테넌트 KEK로 감싸며, 산출물을 클라이언트 측 AES-256-GCM으로
 * 암호화한다(AAD = 테넌트·확인서·종류). DEK 평문은 어댑터 밖으로 나오지 않는다 — 생성·암호화·감싸기를 한 호출에서 끝내고 지운다.
 * 파기(감싼 키 NULL) 뒤에는 어떤 사본도 복호화되지 않는다(crypto-shredding).
 */
public interface DocumentCryptoPort {

    /** 새 문서 키로 산출물들을 암호화한다. 키 ID는 {@code DOC-{32 hex}}. */
    Sealed seal(TenantId tenant, DisclosureId disclosure, Map<ArtifactKind, byte[]> plaintexts);

    /** 저장된 문서 키(감싼 형태)로 산출물 1건을 복호화한다. AAD가 다르거나(다른 확인서·종류로 옮긴 바이트) 변조됐으면 {@link ArtifactUnreadableException}. */
    byte[] open(TenantId tenant, DisclosureId disclosure, StoredKey key, ArtifactKind kind, byte[] ciphertext);

    /**
     * 이미 있는 문서 키로 산출물 1건을 암호화한다(완료 때 서명본·증거 패키지 — 봉인 때 만든 키를 다시 쓴다, AAD = 테넌트·확인서·종류).
     * 키가 파기됐으면 감싼 키가 없으므로 호출 전에 확인한다.
     */
    byte[] encrypt(TenantId tenant, DisclosureId disclosure, StoredKey key, ArtifactKind kind, byte[] plaintext);

    /** 서명 증거 객체를 문서 키로 암호화한다(3B 수용심사 §3-4). AAD = JCS {@code {disclosureId, kind, signatureId, tenantId, v:1}}. */
    byte[] encryptEvidence(TenantId tenant, DisclosureId disclosure, StoredKey key, UUID signatureId, SignatureEvidenceKind kind, byte[] plaintext);

    /** 서명 증거 객체 복호화. 다른 서명·종류·확인서의 바이트거나 변조됐으면 {@link ArtifactUnreadableException}. */
    byte[] openEvidence(TenantId tenant, DisclosureId disclosure, StoredKey key, UUID signatureId, SignatureEvidenceKind kind, byte[] ciphertext);

    /** {@code document_key} 행의 재료. */
    record StoredKey(String keyId, String kekKeyId, byte[] wrappedDek) {
        public StoredKey {
            Objects.requireNonNull(keyId, "keyId");
            Objects.requireNonNull(kekKeyId, "kekKeyId");
            wrappedDek = Objects.requireNonNull(wrappedDek, "wrappedDek").clone();
        }

        @Override
        public byte[] wrappedDek() {
            return wrappedDek.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof StoredKey k && keyId.equals(k.keyId) && kekKeyId.equals(k.kekKeyId) && Arrays.equals(wrappedDek, k.wrappedDek);
        }

        @Override
        public int hashCode() {
            return keyId.hashCode();
        }

        @Override
        public String toString() {
            return "StoredKey[" + keyId + ", " + kekKeyId + "]";
        }
    }

    /** 암호화 결과: 문서 키(감싼 형태)와 종류별 암호문. */
    record Sealed(StoredKey key, Map<ArtifactKind, byte[]> ciphertexts) {
        public Sealed {
            Objects.requireNonNull(key, "key");
            Map<ArtifactKind, byte[]> copy = new EnumMap<>(ArtifactKind.class);
            ciphertexts.forEach((k, v) -> copy.put(k, v.clone()));
            ciphertexts = copy;
        }

        public byte[] ciphertext(ArtifactKind kind) {
            byte[] c = ciphertexts.get(kind);
            if (c == null) {
                throw new IllegalArgumentException("no ciphertext for " + kind);
            }
            return c.clone();
        }
    }
}
