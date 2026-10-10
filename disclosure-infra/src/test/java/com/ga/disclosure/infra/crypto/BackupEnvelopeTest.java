package com.ga.disclosure.infra.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 백업 봉투(Phase 8 ⑤): 조각 경계 전후 크기의 왕복, 평문 SHA-256 보고, 그리고 열리지 않아야 하는 것 전부 — 다른 키, 암호문 한 비트, 조각 하나 빼기·
 * 순서 바꾸기, 마지막 조각 자르기, 끝 뒤 덧붙이기, 머리 변조. 봉한 바이트에 평문 센티널이 없다(주입 "백업 암호화 생략"의 대조 — 평문 tar에는 있다).
 */
class BackupEnvelopeTest {

    static final byte[] KEY = key(1);
    static final byte[] SENTINEL = "SENTINEL-허구고객-홍길동-010-0000-0000".getBytes(StandardCharsets.UTF_8);

    static byte[] key(int seed) {
        byte[] k = new byte[32];
        new Random(seed).nextBytes(k);
        return k;
    }

    static byte[] plaintext(int size) {
        byte[] p = new byte[size];
        new Random(size).nextBytes(p);
        if (size >= SENTINEL.length * 2) {
            System.arraycopy(SENTINEL, 0, p, size / 2, SENTINEL.length);
        }
        return p;
    }

    static byte[] seal(byte[] key, byte[] plain) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupEnvelope.seal(key, new ByteArrayInputStream(plain), out);
        return out.toByteArray();
    }

    static byte[] open(byte[] key, byte[] sealed) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        BackupEnvelope.open(key, new ByteArrayInputStream(sealed), out);
        return out.toByteArray();
    }

    static int indexOf(byte[] hay, byte[] needle) {
        outer:
        for (int i = 0; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 100, BackupEnvelope.CHUNK - 1, BackupEnvelope.CHUNK, BackupEnvelope.CHUNK + 1, 3 * BackupEnvelope.CHUNK + 17})
    void roundTripsAndReportsThePlaintextHash(int size) throws Exception {
        byte[] plain = plaintext(size);
        ByteArrayOutputStream sealedOut = new ByteArrayOutputStream();
        BackupEnvelope.Summary sealed = BackupEnvelope.seal(KEY, new ByteArrayInputStream(plain), sealedOut);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plain));
        assertThat(sealed).isEqualTo(new BackupEnvelope.Summary(size, sha));
        ByteArrayOutputStream opened = new ByteArrayOutputStream();
        assertThat(BackupEnvelope.open(KEY, new ByteArrayInputStream(sealedOut.toByteArray()), opened)).isEqualTo(sealed);
        assertThat(opened.toByteArray()).isEqualTo(plain);
        if (size >= SENTINEL.length * 2) {
            assertThat(indexOf(plain, SENTINEL)).as("control: the sentinel is in the plaintext").isPositive();
            assertThat(indexOf(sealedOut.toByteArray(), SENTINEL)).as("no plaintext in the sealed bytes").isEqualTo(-1);
        }
    }

    @Test
    void twoSealsOfTheSameBytesDiffer() throws Exception {
        byte[] plain = plaintext(1000);
        assertThat(seal(KEY, plain)).isNotEqualTo(seal(KEY, plain));
    }

    /** 머리 줄 길이(조각 위치 계산용). */
    static int headerLength(byte[] sealed) {
        for (int i = 0; i < sealed.length; i++) {
            if (sealed[i] == '\n') {
                return i + 1;
            }
        }
        throw new AssertionError("no header");
    }

    /** [머리, 조각1(길이 포함), 조각2, …]. */
    static java.util.List<byte[]> parts(byte[] sealed) {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        int h = headerLength(sealed);
        out.add(Arrays.copyOf(sealed, h));
        int pos = h;
        while (pos < sealed.length) {
            int len = ByteBuffer.wrap(sealed, pos, 4).getInt();
            out.add(Arrays.copyOfRange(sealed, pos, pos + 4 + len));
            pos += 4 + len;
        }
        return out;
    }

    static byte[] join(java.util.List<byte[]> parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        parts.forEach(out::writeBytes);
        return out.toByteArray();
    }

    @Test
    void nothingAlteredOpens() throws Exception {
        byte[] sealed = seal(KEY, plaintext(3 * BackupEnvelope.CHUNK + 17));
        java.util.List<byte[]> parts = parts(sealed);
        assertThat(parts).hasSize(5);                                              // 머리 + 조각 넷
        assertThat(open(KEY, join(parts))).hasSize(3 * BackupEnvelope.CHUNK + 17);

        assertThatThrownBy(() -> open(key(2), sealed)).isInstanceOf(BackupEnvelope.NotOpenable.class).hasMessageContaining("does not open");
        byte[] flipped = sealed.clone();
        flipped[headerLength(sealed) + 4 + 100] ^= 1;
        assertThatThrownBy(() -> open(KEY, flipped)).isInstanceOf(BackupEnvelope.NotOpenable.class);
        // 조각 하나 빼기·순서 바꾸기
        java.util.List<byte[]> dropped = new java.util.ArrayList<>(parts);
        dropped.remove(2);
        assertThatThrownBy(() -> open(KEY, join(dropped))).isInstanceOf(BackupEnvelope.NotOpenable.class);
        java.util.List<byte[]> swapped = new java.util.ArrayList<>(parts);
        java.util.Collections.swap(swapped, 1, 2);
        assertThatThrownBy(() -> open(KEY, join(swapped))).isInstanceOf(BackupEnvelope.NotOpenable.class);
        // 마지막 조각 자르기(앞 조각들은 "마지막 아님"으로 봉해졌다) — 잘림
        assertThatThrownBy(() -> open(KEY, join(parts.subList(0, 4)))).isInstanceOf(BackupEnvelope.NotOpenable.class).hasMessageContaining("truncated");
        // 끝 뒤 덧붙이기
        byte[] appended = Arrays.copyOf(sealed, sealed.length + 1);
        assertThatThrownBy(() -> open(KEY, appended)).isInstanceOf(BackupEnvelope.NotOpenable.class).hasMessageContaining("after its final chunk");
        // 머리의 접두를 바꾸면(같은 형식이어도) 논스·AAD가 달라 열리지 않는다
        byte[] header = parts.getFirst().clone();
        int at = new String(header, StandardCharsets.US_ASCII).indexOf("\"prefix\":\"") + 10;
        header[at] = (byte) (header[at] == 'A' ? 'B' : 'A');
        java.util.List<byte[]> otherHeader = new java.util.ArrayList<>(parts);
        otherHeader.set(0, header);
        assertThatThrownBy(() -> open(KEY, join(otherHeader))).isInstanceOf(BackupEnvelope.NotOpenable.class);
        assertThatThrownBy(() -> open(KEY, "not a backup\n".getBytes(StandardCharsets.US_ASCII))).isInstanceOf(BackupEnvelope.NotOpenable.class)
                .hasMessageContaining("not a ga-backup");
    }

    @Test
    void theKeyMustBe32Bytes() {
        assertThatThrownBy(() -> seal(new byte[16], new byte[1])).isInstanceOf(IllegalArgumentException.class).hasMessage("backup key must be 32 bytes");
    }
}
