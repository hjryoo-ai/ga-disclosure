package com.ga.disclosure.audit.tsa;

import java.util.Objects;

/** 수락된 응답: 영수증에 그대로 저장할 토큰 DER(CMS SignedData)과 그 파싱 결과. */
public record StampResponse(byte[] tokenDer, TimestampToken token) {

    public StampResponse {
        Objects.requireNonNull(tokenDer, "tokenDer");
        Objects.requireNonNull(token, "token");
        tokenDer = tokenDer.clone();
    }

    @Override
    public byte[] tokenDer() {
        return tokenDer.clone();
    }
}
