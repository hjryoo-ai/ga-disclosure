package com.ga.disclosure.infra.crypto;

import com.ga.disclosure.workflow.page.InvalidCursorException;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
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

    @Test
    void keyFileIsCreatedOwnerOnlyAndReused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("keys/cursor.key");
        CursorCodec first = CursorCodec.fromKeyFile(file);
        assertThat(Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(Base64.getDecoder().decode(Files.readString(file).strip())).hasSize(CursorCodec.KEY_BYTES);
        String cursor = first.seal(T1, "jobs", "p");
        assertThat(CursorCodec.fromKeyFile(file).open(T1, "jobs", cursor)).isEqualTo("p");
    }

    @Test
    void looseOrMalformedKeyFilesFailStartup(@TempDir Path dir) throws Exception {
        Path loose = dir.resolve("loose.key");
        Files.writeString(loose, Base64.getEncoder().encodeToString(new byte[CursorCodec.KEY_BYTES]));
        Files.setPosixFilePermissions(loose, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> CursorCodec.fromKeyFile(loose)).isInstanceOf(IllegalStateException.class).hasMessageContaining("chmod 600");
        Path shortKey = dir.resolve("short.key");
        Files.writeString(shortKey, Base64.getEncoder().encodeToString(new byte[16]));
        Files.setPosixFilePermissions(shortKey, PosixFilePermissions.fromString("rw-------"));
        assertThatThrownBy(() -> CursorCodec.fromKeyFile(shortKey)).isInstanceOf(IllegalStateException.class).hasMessageContaining("32-byte");
    }
}
