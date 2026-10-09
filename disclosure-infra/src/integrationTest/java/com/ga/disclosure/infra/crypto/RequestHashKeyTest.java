package com.ga.disclosure.infra.crypto;

import com.ga.platform.canonical.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 멱등 요청 해시 키(6B 계획 §A-2): HMAC-SHA256(키, 입력)의 hex — 키 없는 SHA-256과 다르고, 키가 다르면 다르다(키 없이 사전 대입이 안 된다). 키 파일 규약은
 * 커서 키와 같다(소유자 전용·32바이트·첫 사용에 생성·넓은 권한이나 틀린 길이는 기동 실패).
 */
class RequestHashKeyTest {

    static final byte[] INPUT = "{\"body\":{\"name\":\"x\"},\"method\":\"POST\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void theHashIsAnHmacUnderTheFileKeyNotAPlainDigest(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("keys/request-hash.key");
        String hash = RequestHashKey.fromKeyFile(file).hash(INPUT);
        assertThat(hash).matches("[0-9a-f]{64}").isNotEqualTo(Sha256.of(INPUT));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(Base64.getDecoder().decode(Files.readString(file).strip()), "HmacSHA256"));
        assertThat(hash).isEqualTo(HexFormat.of().formatHex(mac.doFinal(INPUT)));
        assertThat(RequestHashKey.fromKeyFile(file).hash(INPUT)).as("the file key is reused").isEqualTo(hash);
        assertThat(RequestHashKey.fromKeyFile(dir.resolve("other.key")).hash(INPUT)).as("another key, another hash").isNotEqualTo(hash);
        assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void looseOrMalformedKeyFilesFailStartup(@TempDir Path dir) throws Exception {
        Path loose = dir.resolve("loose.key");
        Files.writeString(loose, Base64.getEncoder().encodeToString(new byte[RequestHashKey.KEY_BYTES]));
        Files.setPosixFilePermissions(loose, PosixFilePermissions.fromString("rw-r-----"));
        assertThatThrownBy(() -> RequestHashKey.fromKeyFile(loose)).isInstanceOf(IllegalStateException.class).hasMessageContaining("request hash key file")
                .hasMessageContaining("chmod 600");
        Path shortKey = dir.resolve("short.key");
        Files.writeString(shortKey, Base64.getEncoder().encodeToString(new byte[16]));
        Files.setPosixFilePermissions(shortKey, PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(() -> RequestHashKey.fromKeyFile(shortKey)).isInstanceOf(IllegalStateException.class).hasMessageContaining("32-byte");
    }
}
