package com.ga.disclosure.domain.enums;

/** 서명 행위(4 계획 승인 Q3): 터치 자필 서명, 종이 스캔 업로드, 사내 인증 승인 클릭. 설계사 방식은 룰 {@code agentSignMethod}. */
public enum SignatureMethod {
    DRAWN,
    UPLOADED_SCAN,
    SSO_APPROVAL
}
