package com.ga.disclosure.rules.resolve;

import java.io.Serial;

/**
 * 유효 룰 본문에 접근자가 찾는 키가 없거나 형식이 다르다. 룰 스키마가 필수 키를 보장하므로 정상 경로에서는 일어나지 않는다 —
 * 접근자는 기본값을 갖지 않는다(Phase 1 지시문 §4).
 */
public class MissingRuleKeyException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public MissingRuleKeyException(String message) {
        super(message);
    }
}
