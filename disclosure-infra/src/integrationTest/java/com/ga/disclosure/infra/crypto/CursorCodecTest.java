package com.ga.disclosure.infra.crypto;

import com.ga.disclosure.workflow.page.InvalidCursorException;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import com.ga.disclosure.infra.secret.FileSecretSource;
import com.ga.disclosure.workflow.secret.SecretMissingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 서명된 목록 커서(6A 계획 §4.2): 형식, 테넌트·목록 종류에 묶인 MAC, 변조 거부, 키 파일 규약(소유자 전용·32바이트·첫 사용에 생성). */
class CursorCodecTest {

    static final TenantId T1 = TenantId.of("CUR_ONE");
    static final TenantId T2 = TenantId.of("CUR_TWO");

    @Test
    void formatIsCanonicalPayloadDotTruncatedMac() {
        CursorCodec codec = CursorCodec.ephemeral();
        String cursor = codec.seal(T1, "jobs", "2026-10-08T00:00:00Z|abc");
        String[] parts = cursor.split("\\.");
        assertThat(parts).hasSize(2);
        byte[] payload = Base64.getUrlDecoder().decode(parts[0]);
        assertThat(new String(payload, StandardCharsets.UTF_8)).isEqualTo("{\"q\":\"2026-10-08T00:00:00Z|abc\",\"s\":\"jobs\",\"v\":1}");
        assertThat(payload).isEqualTo(Canonicalizer.canonicalize(new String(payload, StandardCharsets.UTF_8)));
        assertThat(Base64.getUrlDecoder().decode(parts[1])).hasSize(CursorCodec.MAC_BYTES);
        assertThat(cursor).doesNotContain("=", "+", "/");
        assertThat(codec.open(T1, "jobs", cursor)).isEqualTo("2026-10-08T00:00:00Z|abc");
    }

    @Test
    void otherTenantOtherListTamperingAndGarbageAreRejected() {
        CursorCodec codec = CursorCodec.ephemeral();
        String cursor = codec.seal(T1, "disclosures", "2026-09-01|x");
        int dot = cursor.indexOf('.');
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"q\":\"2027-01-01|x\",\"s\":\"disclosures\",\"v\":1}".getBytes(StandardCharsets.UTF_8)) + cursor.substring(dot);
        String flippedMac = cursor.substring(0, cursor.length() - 2) + (cursor.endsWith("AA") ? "BA" : "AA");
        for (String bad : List.of(forgedPayload, flippedMac, cursor + ".x", "." + cursor, "", "abc", cursor.substring(0, dot) + ".", "x".repeat(600))) {
            assertThatThrownBy(() -> codec.open(T1, "disclosures", bad)).as(bad).isInstanceOf(InvalidCursorException.class)
                    .hasMessage("invalid cursor");
        }
        assertThatThrownBy(() -> codec.open(T2, "disclosures", cursor)).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> codec.open(T1, "jobs", cursor)).isInstanceOf(InvalidCursorException.class);
        assertThatThrownBy(() -> CursorCodec.ephemeral().open(T1, "disclosures", cursor)).as("another key").isInstanceOf(InvalidCursorException.class);
    }

    /** Phase 8: 키는 비밀 출처의 {@code api/cursor} — 없으면 기동 실패(만들지 않는다), 넓은 권한·틀린 길이도 실패. */
    @Test
    void theKeyComesFromTheSecretSourceAndIsNeverCreated(@TempDir Path dir) throws Exception {
        FileSecretSource secrets = new FileSecretSource(dir, false);
        assertThatThrownBy(() -> CursorCodec.fromSecret(secrets)).isInstanceOf(SecretMissingException.class).hasMessageContaining("api/cursor");
        assertThat(Files.exists(dir.resolve("api/cursor"))).as("never created by the app").isFalse();
        FileSecretSource.create(dir, CursorCodec.SECRET, Base64.getEncoder().encode(new byte[CursorCodec.KEY_BYTES]));
        String cursor = CursorCodec.fromSecret(secrets).seal(T1, "jobs", "p");
        assertThat(CursorCodec.fromSecret(secrets).open(T1, "jobs", cursor)).isEqualTo("p");
    }

    @Test
    void looseOrMalformedKeysFailStartup(@TempDir Path dir) throws Exception {
        FileSecretSource.create(dir, CursorCodec.SECRET, Base64.getEncoder().encode(new byte[CursorCodec.KEY_BYTES]));
        Files.setPosixFilePermissions(dir.resolve("api/cursor"), PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> CursorCodec.fromSecret(new FileSecretSource(dir, false))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chmod 600");
        Files.setPosixFilePermissions(dir.resolve("api/cursor"), PosixFilePermissions.fromString("rw-------"));
        Files.writeString(dir.resolve("api/cursor"), Base64.getEncoder().encodeToString(new byte[16]));
        assertThatThrownBy(() -> CursorCodec.fromSecret(new FileSecretSource(dir, false))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32-byte");
    }
}
