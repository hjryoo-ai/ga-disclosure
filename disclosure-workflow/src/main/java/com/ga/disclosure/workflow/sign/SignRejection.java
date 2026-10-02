package com.ga.disclosure.workflow.sign;

/**
 * 서명 유스케이스의 업무 거부 코드(닫힌 어휘, 4 계획 §7). 업무 거부는 상태를 바꾸지 않고 커밋하며 감사 {@code DISCLOSURE_REJECT} 1행에 코드 전부를
 * 싣는다(단락 없음). 토큰으로 세션을 열 수 없는 경우(형식·모르는 토큰·닫힌 세션·TTL 경과)는 여기 없다 — 존재 누설 금지로 한 예외 타입
 * {@code SignTokenRejected}다(승인 B2).
 */
public enum SignRejection {
    /** 룰 {@code channels[channel].enabled = false}. */
    CHANNEL_DISABLED,
    /** 그 채널이 요구하는 본인확인 수단을 다 통과하지 않았다. */
    IDENTITY_INCOMPLETE,
    /** SEQUENTIAL에서 앞 역할의 서명이 아직 없다. */
    ORDER_VIOLATION,
    /** 세션에 고정된 해시가 확인서의 해시와 다르다(정상 경로에는 없다 — 불변 트리거의 이중 검사). */
    HASH_CHANGED,
    /** 서명 기한 끝이 지났다. */
    DEADLINE_PASSED,
    /** 그 역할은 이미 서명했다. */
    ALREADY_SIGNED,
    /** 그 역할은 고정 룰의 서명자 집합에 없다(OPTIONAL 관리자 확인 제외). */
    NOT_IN_SIGNER_SET,
    /** 행위자가 확인서의 담당 설계사가 아니다({@code identity_link}). */
    AGENT_NOT_ASSIGNED,
    /** 설계사 서명 방식({@code agentSignMethod})과 입력이 맞지 않는다(DRAWN이면 스트로크·이미지 필요, SSO_APPROVAL이면 없음). */
    AGENT_SIGN_METHOD_MISMATCH,
    /** {@code managerConfirmMode = OFF} — 관리자 확인이 없는 테넌트다(예외 승인은 별도 경로). */
    MANAGER_CONFIRM_DISABLED,
    /** 관리자가 확인서에 걸린 플래그 전부(열림·닫힘)의 사유를 확인하지 않았다(승인 Q9). */
    ACKNOWLEDGEMENT_MISSING,
    /** 종이 스캔의 확인서 번호·해시 접두가 원본과 맞지 않는다. */
    SCAN_MISMATCH,
    /** 종이 스캔 검토는 {@code exceptionApproval.role}의 몫이다. */
    REVIEW_ROLE_REQUIRED,
    /** 관리자가 서명자 집합에 있으면 종이 스캔 검토는 관리자 확인이 한다(승인 Q10). */
    REVIEW_VIA_MANAGER_CONFIRM,
    /** 열린 종이 스캔 검토가 없다. */
    NO_REVIEW_PENDING,
    /** 완료 명령: 서명자 집합·순서·기한이 충족되지 않았다(R-SIGNER-SET). */
    SIGNER_SET_INCOMPLETE,
    /** 완료 명령: 열린 종이 스캔 검토가 있다. */
    PAPER_SCAN_REVIEW_OPEN
}
