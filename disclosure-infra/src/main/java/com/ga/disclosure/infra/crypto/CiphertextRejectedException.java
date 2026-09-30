package com.ga.disclosure.infra.crypto;

/**
 * 복호화 거부: 태그 불일치(다른 행·컬럼·키 ID로 옮겨진 암호문, 변조), 형식 오류, 파기된 키. 메시지에 암호문·평문·키를 넣지 않는다.
 */
public class CiphertextRejectedException extends RuntimeException {

    public CiphertextRejectedException(String message) {
        super(message);
    }
}
