package com.ga.disclosure.infra.crypto;

import com.ga.disclosure.workflow.artifact.ArtifactUnreadableException;
import com.ga.disclosure.workflow.customer.KeyProviderPort;
import com.ga.disclosure.workflow.job.JobStore;
import com.ga.disclosure.workflow.job.ReportCryptoPort;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 작업 보고서 암호화(6A 계획 §2.6): 보고서마다 새 DEK(AES-256-GCM)를 만들고 {@link KeyProviderPort}로 감싼다(키 ID {@code RPT-{jobId}} —
 * 감싸기 AAD는 키 공급자의 {@code {kekId, keyId, tenantId, v:1}}). 본문 AAD = JCS {@code {jobId, kind:"REPORT", tenantId, v:1}}. 문서 키는 확인서에
 * 묶여 있어 쓰지 않는다. DEK 평문은 이 클래스 밖으로 나가지 않고 쓰고 나면 0으로 지운다.
 */
public final class ReportCipher implements ReportCryptoPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final KeyProviderPort keys;

    public ReportCipher(KeyProviderPort keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    static String keyId(UUID jobId) {
        return "RPT-" + jobId;
    }

    static byte[] aad(TenantId tenant, UUID jobId) {
        return Canonicalizer.canonicalize(JSON.createObjectNode().put("jobId", jobId.toString()).put("kind", "REPORT").put("tenantId", tenant.value())
                .put("v", 1));
    }

    @Override
    public Sealed seal(TenantId tenant, UUID jobId, byte[] plaintext) {
        String kekId = keys.currentKekId(tenant);
        byte[] dek = AesGcm.newKey();
        try {
            byte[] ciphertext = AesGcm.encrypt(dek, plaintext, aad(tenant, jobId));
            return new Sealed(ciphertext, new JobStore.ReportKey(kekId, keys.wrap(tenant, keyId(jobId), kekId, dek)));
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    @Override
    public byte[] open(TenantId tenant, UUID jobId, JobStore.ReportKey key, byte[] ciphertext) {
        byte[] dek = keys.unwrap(tenant, keyId(jobId), key.kekId(), key.wrapped());
        try {
            return AesGcm.decrypt(dek, ciphertext, aad(tenant, jobId));
        } catch (CiphertextRejectedException e) {
            throw new ArtifactUnreadableException(e);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }
}
