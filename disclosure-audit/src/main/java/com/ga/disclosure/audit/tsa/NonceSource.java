package com.ga.disclosure.audit.tsa;

import java.security.SecureRandom;

/** TSA 요청 nonce(63비트 양수 난수). 테스트가 고정할 수 있도록 포트로 받는다(5 계획 §3). */
@FunctionalInterface
public interface NonceSource {

    long next();

    static NonceSource secure() {
        SecureRandom random = new SecureRandom();
        return () -> random.nextLong() & Long.MAX_VALUE;
    }
}
