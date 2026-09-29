package com.ga.disclosure.compliance.rules;

import java.io.Serial;

/** 룰 거버넌스 요청이 규칙에 어긋나 거부됐다(해당 테넌트의 트랜잭션은 롤백되어 아무것도 남지 않는다). */
public class GovernanceRejectedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public GovernanceRejectedException(String message) {
        super(message);
    }
}
