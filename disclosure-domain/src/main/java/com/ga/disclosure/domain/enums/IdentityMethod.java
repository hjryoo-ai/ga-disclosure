package com.ga.disclosure.domain.enums;

/**
 * 본인확인 수단(설계서 §6.5, 룰 {@code identityCheck}의 닫힌 어휘). 결과만 남고 입력값은 어디에도 남지 않는다(4 계획 §2.4).
 * PROVIDER는 v2 인정 전자서명 사업자 예약.
 */
public enum IdentityMethod {
    LINK_POSSESSION,
    BIRTH_DATE,
    AGENT_FACE_TO_FACE,
    SCROLL_COMPLETE,
    PROVIDER
}
