package com.ga.disclosure.infra.crypto;

import com.ga.disclosure.infra.testing.TestKeks;
import com.ga.disclosure.workflow.artifact.ArtifactUnreadableException;
import com.ga.disclosure.workflow.customer.KeyProviderPort;
import com.ga.disclosure.workflow.job.ReportCryptoPort;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 작업 보고서 암호화(6A 계획 §2.6, 설계서 §6.10): 보고서마다 새 키(키 ID {@code RPT-{job}}로 감싼다), 본문 AAD = JCS
 * {@code {jobId, kind:"REPORT", tenantId, v:1}}. 다른 작업·테넌트로 옮긴 바이트는 같은 DEK로도 풀리지 않는다. DB 없음(시험 테넌트 KEK).
 */
class ReportCipherTest {

    final KeyProviderPort keys = TestKeks.shared().standalone();
    final ReportCipher cipher = new ReportCipher(keys);
    final TenantId tenant = TenantId.of("RPT_T1");
    final byte[] report = "{\"kind\":\"VERIFY_TENANT\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void theBodyAadIsTheSpecifiedJcs() {
        UUID job = UUID.fromString("00000000-0000-4000-8000-000000000001");
        assertThat(new String(ReportCipher.aad(tenant, job), StandardCharsets.UTF_8))
                .isEqualTo("{\"jobId\":\"00000000-0000-4000-8000-000000000001\",\"kind\":\"REPORT\",\"tenantId\":\"RPT_T1\",\"v\":1}");
    }

    @Test
    void aReportOpensOnlyForItsOwnJobAndTenant() {
        UUID job = UUID.randomUUID();
        ReportCryptoPort.Sealed sealed = cipher.seal(tenant, job, report);
        assertThat(sealed.ciphertext()).hasSize(report.length + 29);
        assertThat(cipher.open(tenant, job, sealed.key(), sealed.ciphertext())).isEqualTo(report);

        // 같은 DEK라도 다른 작업·테넌트의 AAD로는 풀리지 않는다
        byte[] dek = keys.unwrap(tenant, "RPT-" + job, sealed.key().kekId(), sealed.key().wrapped());
        assertThatThrownBy(() -> AesGcm.decrypt(dek, sealed.ciphertext(), ReportCipher.aad(tenant, UUID.randomUUID())))
                .isInstanceOf(CiphertextRejectedException.class);
        assertThatThrownBy(() -> AesGcm.decrypt(dek, sealed.ciphertext(), ReportCipher.aad(TenantId.of("RPT_T2"), job)))
                .isInstanceOf(CiphertextRejectedException.class);

        UUID other = UUID.randomUUID();
        ReportCryptoPort.Sealed second = cipher.seal(tenant, other, report);
        assertThat(second.key().wrapped()).as("a fresh key per report").isNotEqualTo(sealed.key().wrapped());
        assertThatThrownBy(() -> cipher.open(tenant, other, second.key(), sealed.ciphertext())).as("moved to another job")
                .isInstanceOf(ArtifactUnreadableException.class);
        assertThatThrownBy(() -> cipher.open(tenant, other, sealed.key(), sealed.ciphertext())).as("the key is bound to its job (RPT-{job})")
                .isInstanceOf(RuntimeException.class);
    }
}
