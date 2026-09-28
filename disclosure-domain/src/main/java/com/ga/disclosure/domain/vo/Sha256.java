package com.ga.disclosure.domain.vo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * SHA-256 다이제스트(소문자 16진 64자). 대문자를 받지 않는 것은 같은 해시의 표기가 둘이 되지 않게 하기 위해서다.
 *
 * <p>{@link #equals(Object)}는 {@link MessageDigest#isEqual(byte[], byte[])}로 <b>상수 시간 비교</b>한다
 * (서명 대상 해시·토큰 해시 대조에서 타이밍으로 접두가 새지 않게).
 */
public record Sha256(String hex) {

    private static final Pattern FORMAT = Pattern.compile("[0-9a-f]{64}");

    public Sha256 {
        if (hex == null || !FORMAT.matcher(hex).matches()) {
            throw new IllegalArgumentException("expected 64 lowercase hex chars");
        }
    }

    public static Sha256 of(String hex) {
        return new Sha256(hex);
    }

    public static Sha256 ofDigest(byte[] digest) {
        if (digest == null || digest.length != 32) {
            throw new IllegalArgumentException("SHA-256 digest must be 32 bytes");
        }
        return new Sha256(HexFormat.of().formatHex(digest));
    }

    public byte[] bytes() {
        return HexFormat.of().parseHex(hex);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Sha256 other
                && MessageDigest.isEqual(hex.getBytes(StandardCharsets.US_ASCII), other.hex.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public int hashCode() {
        return hex.hashCode();
    }

    @Override
    public String toString() {
        return hex;
    }
}
