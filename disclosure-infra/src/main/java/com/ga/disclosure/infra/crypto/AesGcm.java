package com.ga.disclosure.infra.crypto;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/** AES-256-GCM 봉투: {@code 0x01 ‖ nonce(12) ‖ ciphertext‖tag(16)}. 키는 32바이트만 받는다. */
public final class AesGcm {

    public static final byte FORMAT_VERSION = 0x01;
    public static final int KEY_BYTES = 32;
    static final int NONCE_BYTES = 12;
    static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcm() {
    }

    public static byte[] newKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return key;
    }

    public static byte[] encrypt(byte[] key, byte[] plaintext, byte[] aad) {
        requireKey(key);
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            byte[] sealed = cipher.doFinal(plaintext);
            byte[] out = new byte[1 + NONCE_BYTES + sealed.length];
            out[0] = FORMAT_VERSION;
            System.arraycopy(nonce, 0, out, 1, NONCE_BYTES);
            System.arraycopy(sealed, 0, out, 1 + NONCE_BYTES, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption unavailable", e);
        }
    }

    public static byte[] decrypt(byte[] key, byte[] envelope, byte[] aad) {
        requireKey(key);
        Objects.requireNonNull(envelope, "envelope");
        if (envelope.length < 1 + NONCE_BYTES + TAG_BITS / 8 || envelope[0] != FORMAT_VERSION) {
            throw new CiphertextRejectedException("ciphertext has an unknown format");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(envelope, 1, 1 + NONCE_BYTES)));
            cipher.updateAAD(aad);
            return cipher.doFinal(envelope, 1 + NONCE_BYTES, envelope.length - 1 - NONCE_BYTES);
        } catch (AEADBadTagException e) {
            throw new CiphertextRejectedException("ciphertext does not authenticate for this key and context (moved, altered or wrong key)");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM decryption unavailable", e);
        }
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("AES-256 key must be " + KEY_BYTES + " bytes");
        }
    }
}
