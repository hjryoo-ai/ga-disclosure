package com.ga.disclosure.audit.tsa;

import java.time.Instant;
import java.util.Objects;

/**
 * 파싱한 RFC 3161 토큰의 보고용 필드(5 계획 §3). 정수는 소문자 hex(앞자리 0 없음). {@code nonceHex}는 토큰에 nonce가 없으면 null —
 * 저장된 토큰의 nonce는 보고만 하고, 요청과의 비교는 응답 수락 때뿐이다. {@code signerCertSha256}은 서명자 인증서 DER의 SHA-256.
 */
public record TimestampToken(Instant genTime, String policyOid, String serialHex, String imprintHex, String nonceHex,
                             String signerCertSha256) {

    public TimestampToken {
        Objects.requireNonNull(genTime, "genTime");
        Objects.requireNonNull(policyOid, "policyOid");
        Objects.requireNonNull(serialHex, "serialHex");
        Objects.requireNonNull(imprintHex, "imprintHex");
        Objects.requireNonNull(signerCertSha256, "signerCertSha256");
    }
}
