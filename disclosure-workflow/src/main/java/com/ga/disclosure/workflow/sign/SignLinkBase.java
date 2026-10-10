package com.ga.disclosure.workflow.sign;

import com.ga.disclosure.sign.token.SignToken;

import java.util.Objects;

/**
 * 고객 서명 화면의 기준 주소 {@code ga.sign.link-base-url}(끝이 {@code #} — 토큰은 프래그먼트). 원격 링크(통지)와 현장 기기 서명 창(세션 발급 응답의
 * {@code signUrl})이 같은 값을 쓴다(Phase 8): 서명 호스트가 직원 호스트와 다른 배포에서 직원 화면이 상대 경로 {@code /s#}로 창을 열면 공개 서명 API가
 * 없는 호스트에 닿는다 — 서명 화면이 어디 있는지는 배포 설정 하나가 말한다.
 */
public record SignLinkBase(String value) {

    public SignLinkBase {
        Objects.requireNonNull(value, "value");
        if (!value.endsWith("#")) {
            throw new IllegalArgumentException("ga.sign.link-base-url must end with '#' so the token stays in the fragment");
        }
    }

    public SignLink link(SignToken token) {
        return SignLink.of(value, token);
    }
}
