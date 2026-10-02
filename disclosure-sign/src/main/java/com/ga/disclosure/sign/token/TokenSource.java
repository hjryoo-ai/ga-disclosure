package com.ga.disclosure.sign.token;

/** 토큰 비밀 난수 포트(4 계획 §2.2). 운영 = {@code SecureRandom}(infra), 테스트 = 시드 고정. 이 모듈은 환경을 읽지 않는다. */
public interface TokenSource {

    /** 새 난수 32바이트(256비트). 호출마다 새 배열. */
    byte[] next256Bits();
}
