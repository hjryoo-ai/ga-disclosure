package com.ga.disclosure.api.security;

/**
 * 공개 서명 경로 거부 사유(Phase 8 G8 미터 {@code ga.public.rejections{reason}}). 응답은 사유와 무관하게 언제나 같은 거부 바이트다 — 사유는 운영 미터에만
 * 간다(테넌트 미상이 있으므로 테넌트 라벨 없음). 닫힌 목록이다.
 */
public enum PublicRejection {
    /** 질의 문자열·POST 아님·공개 경로 목록 밖. */
    NOT_A_SIGN_REQUEST,
    BODY_TOO_LARGE,
    /** 본문 JSON·토큰 형식 오류, 토큰 없음. */
    MALFORMED,
    UNKNOWN_TENANT,
    RATE_LIMITED,
    /** 핸들러가 거부 표식·허용 밖 상태·sendError·리다이렉트로 끝냄. */
    HANDLER_REJECTED,
    /** 입장 검사·핸들러의 예외. */
    ERROR;

    /** 사유를 받는 쪽(앱이 Micrometer 카운터로). 기록 실패는 거부 응답을 바꾸지 않는다. */
    @FunctionalInterface
    public interface Counter {
        void rejected(PublicRejection reason);

        Counter NONE = reason -> {
        };
    }
}
