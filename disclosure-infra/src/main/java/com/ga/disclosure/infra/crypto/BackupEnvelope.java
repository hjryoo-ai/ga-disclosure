package com.ga.disclosure.infra.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 백업 봉투(Phase 8 ⑤, 승인 Q3 "백업은 별도 키로 암호화"): DB 물리 백업 tar 같은 큰 파일을 백업 키(비밀 {@code backup/key} — 앱 키와 별개)로 AES-256-GCM
 * 조각 암호화한다. 한 번에 다 읽지 않는다 — 조각(1 MiB)마다 GCM, 논스 = 무작위 접두 8바이트 ‖ 조각 번호 4바이트, AAD = 머리 해시 ‖ 조각 번호 ‖ 마지막
 * 표지(STREAM 구성: 조각을 빼거나 바꾸거나 순서를 바꾸거나 끝을 자르면 열리지 않는다).
 * <pre>
 * {"alg":"AES-256-GCM","chunk":1048576,"format":"ga-backup","prefix":"&lt;base64&gt;","v":1}\n   (머리 한 줄, 키 순서 고정)
 * [u32 길이][암호문+태그] …                                                                     (마지막 조각은 표지 1, 비어 있을 수 있다)
 * </pre>
 * 평문의 SHA-256과 크기를 돌려준다(값은 내지 않는다 — 키·평문은 출력·예외 문장에 없다).
 */
public final class BackupEnvelope {

    public static final int CHUNK = 1 << 20;
    private static final int TAG_BITS = 128;
    private static final int MAX_CIPHER_CHUNK = CHUNK + TAG_BITS / 8;
    private static final Pattern HEADER = Pattern.compile(
            "\\{\"alg\":\"AES-256-GCM\",\"chunk\":" + CHUNK + ",\"format\":\"ga-backup\",\"prefix\":\"([A-Za-z0-9+/=]{12})\",\"v\":1}");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 봉하거나 연 결과: 평문 크기·SHA-256(16진). */
    public record Summary(long plaintextBytes, String plaintextSha256) {
    }

    /** 열리지 않는다(키가 다름·변조·잘림·덧붙임·형식 아님). 원인을 구분해 알리지 않는다. */
    public static final class NotOpenable extends RuntimeException {
        NotOpenable(String message) {
            super(message);
        }
    }

    private BackupEnvelope() {
    }

    public static Summary seal(byte[] key, InputStream plaintext, OutputStream sealed) throws IOException {
        SecretKeySpec k = key(key);
        byte[] prefix = new byte[8];
        RANDOM.nextBytes(prefix);
        byte[] header = ("{\"alg\":\"AES-256-GCM\",\"chunk\":" + CHUNK + ",\"format\":\"ga-backup\",\"prefix\":\""
                + Base64.getEncoder().encodeToString(prefix) + "\",\"v\":1}\n").getBytes(StandardCharsets.US_ASCII);
        byte[] headerHash = sha256(header);
        sealed.write(header);
        DataOutputStream out = new DataOutputStream(sealed);
        MessageDigest digest = digest();
        long total = 0;
        int index = 0;
        byte[] current = plaintext.readNBytes(CHUNK);
        while (true) {
            byte[] next = current.length == CHUNK ? plaintext.readNBytes(CHUNK) : new byte[0];
            boolean last = next.length == 0;
            digest.update(current);
            total += current.length;
            byte[] cipher = crypt(Cipher.ENCRYPT_MODE, k, prefix, headerHash, index, last, current);
            Arrays.fill(current, (byte) 0);
            out.writeInt(cipher.length);
            out.write(cipher);
            if (last) {
                break;
            }
            current = next;
            index++;
        }
        out.flush();
        return new Summary(total, HexFormat.of().formatHex(digest.digest()));
    }

    public static Summary open(byte[] key, InputStream sealed, OutputStream plaintext) throws IOException {
        SecretKeySpec k = key(key);
        byte[] header = readHeader(sealed);
        Matcher m = HEADER.matcher(new String(header, 0, header.length - 1, StandardCharsets.US_ASCII));
        if (!m.matches()) {
            throw new NotOpenable("not a ga-backup v1 envelope");
        }
        byte[] prefix = Base64.getDecoder().decode(m.group(1));
        byte[] headerHash = sha256(header);
        DataInputStream in = new DataInputStream(sealed);
        MessageDigest digest = digest();
        long total = 0;
        for (int index = 0; ; index++) {
            int length;
            try {
                length = in.readInt();
            } catch (EOFException e) {
                throw new NotOpenable("backup envelope is truncated");
            }
            if (length < TAG_BITS / 8 || length > MAX_CIPHER_CHUNK) {
                throw new NotOpenable("backup envelope chunk length is invalid");
            }
            byte[] cipher = in.readNBytes(length);
            if (cipher.length != length) {
                throw new NotOpenable("backup envelope is truncated");
            }
            // 마지막 표지는 암호문에 묶여 있다 — 먼저 "마지막 아님"으로 열어 보고, 안 되면 "마지막"으로
            byte[] plain = tryCrypt(k, prefix, headerHash, index, false, cipher);
            boolean last = false;
            if (plain == null) {
                plain = tryCrypt(k, prefix, headerHash, index, true, cipher);
                last = true;
            }
            if (plain == null) {
                throw new NotOpenable("backup envelope does not open with this key (or was altered)");
            }
            digest.update(plain);
            total += plain.length;
            plaintext.write(plain);
            Arrays.fill(plain, (byte) 0);
            if (last) {
                if (sealed.read() != -1) {
                    throw new NotOpenable("backup envelope has data after its final chunk");
                }
                plaintext.flush();
                return new Summary(total, HexFormat.of().formatHex(digest.digest()));
            }
        }
    }

    private static byte[] readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (int i = 0; i < 256; i++) {
            int b = in.read();
            if (b == -1) {
                break;
            }
            buffer.write(b);
            if (b == '\n') {
                return buffer.toByteArray();
            }
        }
        throw new NotOpenable("not a ga-backup v1 envelope");
    }

    private static byte[] tryCrypt(SecretKeySpec k, byte[] prefix, byte[] headerHash, int index, boolean last, byte[] cipher) {
        try {
            return crypt(Cipher.DECRYPT_MODE, k, prefix, headerHash, index, last, cipher);
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof AEADBadTagException) {
                return null;
            }
            throw e;
        }
    }

    private static byte[] crypt(int mode, SecretKeySpec k, byte[] prefix, byte[] headerHash, int index, boolean last, byte[] input) {
        try {
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce = ByteBuffer.allocate(12).put(prefix).putInt(index).array();
            gcm.init(mode, k, new GCMParameterSpec(TAG_BITS, nonce));
            gcm.updateAAD(ByteBuffer.allocate(headerHash.length + 5).put(headerHash).putInt(index).put((byte) (last ? 1 : 0)).array());
            return gcm.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("backup envelope cipher failed", e);
        }
    }

    private static SecretKeySpec key(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length != 32) {
            throw new IllegalArgumentException("backup key must be 32 bytes");
        }
        return new SecretKeySpec(key, "AES");
    }

    private static byte[] sha256(byte[] bytes) {
        return digest().digest(bytes);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
