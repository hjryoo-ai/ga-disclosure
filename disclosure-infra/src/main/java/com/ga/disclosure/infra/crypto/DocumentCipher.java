package com.ga.disclosure.infra.crypto;

import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.artifact.ArtifactUnreadableException;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.customer.KeyProviderPort;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * 문서 데이터 키 어댑터(3A 수용심사 §3-5, 설계서 §9): 확인서마다 새 DEK(32바이트)를 만들어 산출물을 AES-256-GCM으로 암호화하고 테넌트 KEK로
 * 감싼다. 산출물 AAD = JCS {@code {"disclosureId","kind","tenantId","v":1}}(다른 확인서·종류로 옮긴 바이트는 풀리지 않는다), 키 감싸기는
 * {@link KeyProviderPort}(tenant·keyId·kekId 결속). DEK 평문은 이 클래스 밖으로 나가지 않고 쓰고 나면 0으로 지운다.
 * 형식은 고객 필드와 같다: {@code 0x01 ‖ nonce(12) ‖ 암호문 ‖ tag(16)} — 암호문 길이 = 평문 + 29.
 */
public final class DocumentCipher implements DocumentCryptoPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeyProviderPort keys;

    public DocumentCipher(KeyProviderPort keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    @Override
    public Sealed seal(TenantId tenant, DisclosureId disclosure, Map<ArtifactKind, byte[]> plaintexts) {
        byte[] id = new byte[16];
        RANDOM.nextBytes(id);
        String keyId = "DOC-" + HexFormat.of().formatHex(id);
        String kekId = keys.currentKekId();
        byte[] dek = AesGcm.newKey();
        try {
            Map<ArtifactKind, byte[]> out = new EnumMap<>(ArtifactKind.class);
            plaintexts.forEach((kind, bytes) -> out.put(kind, AesGcm.encrypt(dek, bytes, aad(tenant, disclosure, kind))));
            byte[] wrapped = keys.wrap(tenant, keyId, kekId, dek);
            return new Sealed(new StoredKey(keyId, kekId, wrapped), out);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    @Override
    public byte[] open(TenantId tenant, DisclosureId disclosure, StoredKey key, ArtifactKind kind, byte[] ciphertext) {
        byte[] dek = keys.unwrap(tenant, key.keyId(), key.kekKeyId(), key.wrappedDek());
        try {
            return AesGcm.decrypt(dek, ciphertext, aad(tenant, disclosure, kind));
        } catch (CiphertextRejectedException e) {
            throw new ArtifactUnreadableException(e);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    static byte[] aad(TenantId tenant, DisclosureId disclosure, ArtifactKind kind) {
        return Canonicalizer.canonicalize(JSON.createObjectNode().put("disclosureId", disclosure.value().toString()).put("kind", kind.name())
                .put("tenantId", tenant.value()).put("v", 1));
    }
}
