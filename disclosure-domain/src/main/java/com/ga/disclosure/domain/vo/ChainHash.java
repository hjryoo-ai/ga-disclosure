package com.ga.disclosure.domain.vo;

import java.util.Objects;

/** 봉인 체인 해시 {@code H(prev_chain_hash || canonical_hash || pdf_hash)}. 계산은 Phase 3(disclosure-seal). */
public record ChainHash(Sha256 value) {

    public ChainHash {
        Objects.requireNonNull(value, "value");
    }

    public static ChainHash of(String hex) {
        return new ChainHash(Sha256.of(hex));
    }

    @Override
    public String toString() {
        return value.hex();
    }
}
