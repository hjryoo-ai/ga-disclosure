package com.ga.platform.canonical;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * SHA-256 hex(소문자 64자). 도메인 값객체({@code com.ga.disclosure.domain.vo.Sha256})는 이 문자열을 감싼다 — 플랫폼 모듈은
 * 도메인을 모른다.
 */
public final class Sha256 {

    private Sha256() {
    }

    public static String of(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every Java platform", e);
        }
    }

    /** {@code SHA-256(JCS(json))}. */
    public static String ofCanonical(String json) {
        return of(Canonicalizer.canonicalize(json));
    }
}
