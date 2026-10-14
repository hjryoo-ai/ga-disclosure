package com.ga.disclosure.infra.crypto;

import com.ga.platform.canonical.Sha256;
import com.ga.disclosure.infra.secret.FileSecretSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 멱등 요청 해시 키(6B 계획 §A-2): HMAC-SHA256(키, 입력)의 hex — 키 없는 SHA-256과 다르고, 키가 다르면 다르다(키 없이 사전 대입이 안 된다). 키는 비밀
 * {@code api/request-hash}(Phase 8 — 커서 키와 같은 규약: 소유자 전용·32바이트·만들지 않음·넓은 권한이나 틀린 길이는 기동 실패).
 */
class RequestHashKeyTest {

    static final byte[] INPUT = "{\"body\":{\"name\":\"x\"},\"method\":\"POST\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void theHashIsAnHmacUnderTheSecretKeyNotAPlainDigest(@TempDir Path dir) throws Exception {
        byte[] raw = new byte[RequestHashKey.KEY_BYTES];
        new java.security.SecureRandom().nextBytes(raw);
        FileSecretSource.create(dir, RequestHashKey.SECRET, Base64.getEncoder().encode(raw));
        String hash = RequestHashKey.fromSecret(new FileSecretSource(dir, false)).hash(INPUT);
        assertThat(hash).matches("[0-9a-f]{64}").isNotEqualTo(Sha256.of(INPUT));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(raw, "HmacSHA256"));
        assertThat(hash).isEqualTo(HexFormat.of().formatHex(mac.doFinal(INPUT)));
        Path other = Files.createDirectories(dir.resolve("other"));
        FileSecretSource.create(other, RequestHashKey.SECRET, Base64.getEncoder().encode(new byte[RequestHashKey.KEY_BYTES]));
        assertThat(RequestHashKey.fromSecret(new FileSecretSource(other, false)).hash(INPUT)).as("another key, another hash").isNotEqualTo(hash);
    }

    @Test
    void looseOrMalformedKeysFailStartup(@TempDir Path dir) throws Exception {
        FileSecretSource.create(dir, RequestHashKey.SECRET, Base64.getEncoder().encode(new byte[RequestHashKey.KEY_BYTES]));
        Files.setPosixFilePermissions(dir.resolve("api/request-hash"), PosixFilePermissions.fromString("rw-r-----"));
        assertThatThrownBy(() -> RequestHashKey.fromSecret(new FileSecretSource(dir, false))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("api/request-hash").hasMessageContaining("chmod 600");
        Files.setPosixFilePermissions(dir.resolve("api/request-hash"), PosixFilePermissions.fromString("rw-------"));
        Files.writeString(dir.resolve("api/request-hash"), Base64.getEncoder().encodeToString(new byte[16]));
        assertThatThrownBy(() -> RequestHashKey.fromSecret(new FileSecretSource(dir, false))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32-byte");
    }
}
