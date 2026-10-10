package com.ga.disclosure.app.config;

import com.ga.disclosure.workflow.secret.SecretName;
import com.ga.disclosure.workflow.secret.SecretSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 신뢰 앵커 출처(11단계): 비밀 {@code tsa/trust-anchors.pem}(집합)이 있으면 모드와 무관하게 그것, 없으면 http는 null·stub은 스텁이 내보낸 파일. 스텁 키를
 * 회전한 kind에서 앱 안 검증이 옛 앵커를 믿지 못했던 결함의 회귀 시험.
 */
class TsaTrustSourceTest {

    private static SecretSource secrets(Map<String, String> values) {
        return new SecretSource() {
            @Override
            public byte[] read(SecretName name) {
                return values.get(name.value()).getBytes(StandardCharsets.US_ASCII);
            }

            @Override
            public boolean exists(SecretName name) {
                return values.containsKey(name.value());
            }
        };
    }

    @Test
    void theAnchorSetSecretWinsInEveryMode(@TempDir Path dir) throws IOException {
        Path stubPem = Files.writeString(dir.resolve("tsa-trust.pem"), "STUB-ONLY");
        SecretSource withSet = secrets(Map.of("tsa/trust-anchors.pem", "OLD+NEW"));
        RetentionConfiguration config = new RetentionConfiguration();
        assertThat(config.tsaTrust("stub", stubPem.toString(), withSet).pemOrNull()).asString(StandardCharsets.US_ASCII).isEqualTo("OLD+NEW");
        assertThat(config.tsaTrust("http", stubPem.toString(), withSet).pemOrNull()).asString(StandardCharsets.US_ASCII).isEqualTo("OLD+NEW");
    }

    @Test
    void withoutTheSecretStubReadsItsOwnExportAndHttpTrustsNothing(@TempDir Path dir) throws IOException {
        Path stubPem = Files.writeString(dir.resolve("tsa-trust.pem"), "STUB-ONLY");
        SecretSource none = secrets(Map.of());
        RetentionConfiguration config = new RetentionConfiguration();
        assertThat(config.tsaTrust("stub", stubPem.toString(), none).pemOrNull()).asString(StandardCharsets.US_ASCII).isEqualTo("STUB-ONLY");
        assertThat(config.tsaTrust("http", stubPem.toString(), none).pemOrNull()).isNull();
        assertThat(config.tsaTrust("stub", dir.resolve("missing.pem").toString(), none).pemOrNull()).isNull();
    }
}
