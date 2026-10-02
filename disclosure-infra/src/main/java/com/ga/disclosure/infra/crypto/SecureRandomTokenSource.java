package com.ga.disclosure.infra.crypto;

import com.ga.disclosure.sign.token.TokenSource;

import java.security.SecureRandom;

/** 서명 토큰 난수(4 계획 §2.2): {@link SecureRandom} 256비트. {@code disclosure-sign}은 환경을 읽지 않으므로 난수원은 여기 둔다. */
public final class SecureRandomTokenSource implements TokenSource {

    private final SecureRandom random = new SecureRandom();

    @Override
    public byte[] next256Bits() {
        byte[] bits = new byte[32];
        random.nextBytes(bits);
        return bits;
    }
}
