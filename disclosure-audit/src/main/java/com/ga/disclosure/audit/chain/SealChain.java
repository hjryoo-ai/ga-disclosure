package com.ga.disclosure.audit.chain;

import com.ga.platform.canonical.Sha256;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 봉인 체인 식(설계서 §6.4): {@code chain_hash = SHA-256(ASCII(prev ‖ canonical ‖ pdf))}, 세 값은 소문자 hex, 테넌트의 첫 봉인은
 * {@code prev =} {@link #ZERO}. 봉인(생산)과 검증·앵커(소비)가 같은 Java 식 하나를 쓴다 — DB(V7 GD095)는 같은 식을 SQL로 다시 계산한다.
 */
public final class SealChain {

    public static final String ZERO = "0".repeat(64);

    private SealChain() {
    }

    public static String next(String prev, String canonicalHash, String pdfHash) {
        Objects.requireNonNull(prev, "prev");
        Objects.requireNonNull(canonicalHash, "canonicalHash");
        Objects.requireNonNull(pdfHash, "pdfHash");
        return Sha256.of((prev + canonicalHash + pdfHash).getBytes(StandardCharsets.US_ASCII));
    }
}
