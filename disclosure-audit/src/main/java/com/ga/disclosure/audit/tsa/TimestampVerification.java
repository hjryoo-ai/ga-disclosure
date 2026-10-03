package com.ga.disclosure.audit.tsa;

import java.util.Objects;

/**
 * 토큰 검증 결과(5 계획 §3): {@code VALID | INVALID(이유) | UNTRUSTED(이유)}. 서명·EKU·imprint가 어긋나면 INVALID, 서명은 맞지만
 * 신뢰 앵커가 없거나 체인이 닿지 않으면 UNTRUSTED — 통과가 아니다. 파싱조차 안 되면 {@code token}이 null이다.
 */
public sealed interface TimestampVerification {

    TimestampToken token();

    record Valid(TimestampToken token) implements TimestampVerification {
        public Valid {
            Objects.requireNonNull(token, "token");
        }
    }

    record Invalid(String reason, TimestampToken token) implements TimestampVerification {
        public Invalid {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record Untrusted(String reason, TimestampToken token) implements TimestampVerification {
        public Untrusted {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(token, "token");
        }
    }
}
