package com.ga.disclosure.workflow.sign;

import com.ga.disclosure.sign.token.SignToken;

import java.util.Objects;

/**
 * 고객 서명 링크(6A 계획 §5.5): {@code {base}{token}}이고 base는 배포 설정 {@code ga.sign.link-base-url}(예 {@code https://{host}/s#}) — 토큰이
 * 프래그먼트에 있어 서버·액세스 로그에 가지 않는다. 원문은 {@link #reveal()}로만 꺼내고 {@link #toString()}은 가린다.
 */
public final class SignLink {

    private final String base;
    private final SignToken token;

    private SignLink(String base, SignToken token) {
        this.base = base;
        this.token = token;
    }

    /** {@code base}는 {@code #}로 끝나야 한다(토큰은 프래그먼트). */
    public static SignLink of(String base, SignToken token) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(token, "token");
        if (!base.endsWith("#")) {
            throw new IllegalArgumentException("ga.sign.link-base-url must end with '#' so the token stays in the fragment");
        }
        return new SignLink(base, token);
    }

    /** 통지 본문에 싣는 링크 원문. 로그·예외에 넣지 않는다. */
    public String reveal() {
        return base + token.reveal();
    }

    @Override
    public String toString() {
        return "SignLink[****]";
    }
}
