package com.ga.disclosure.workflow.job;

import com.ga.platform.core.tenant.TenantId;

import java.util.UUID;

/**
 * 작업 보고서 암호화(6A 계획 §2.6): 보고서마다 새 DEK를 테넌트 KEK로 감싼다. 본문 AAD = JCS {@code {jobId, kind:"REPORT", tenantId, v:1}} —
 * 다른 작업·테넌트로 옮긴 바이트는 풀리지 않는다. 형식은 산출물과 같다({@code 0x01 ‖ nonce ‖ 암호문 ‖ tag}).
 */
public interface ReportCryptoPort {

    Sealed seal(TenantId tenant, UUID jobId, byte[] plaintext);

    /** 변조·다른 작업의 바이트면 {@link com.ga.disclosure.workflow.artifact.ArtifactUnreadableException}. */
    byte[] open(TenantId tenant, UUID jobId, JobStore.ReportKey key, byte[] ciphertext);

    record Sealed(byte[] ciphertext, JobStore.ReportKey key) {
        public Sealed {
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Sealed s && java.util.Arrays.equals(ciphertext, s.ciphertext) && key.equals(s.key);
        }

        @Override
        public int hashCode() {
            return key.hashCode();
        }

        @Override
        public String toString() {
            return "Sealed[" + key + ", " + ciphertext.length + " bytes]";
        }
    }
}
