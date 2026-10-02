package com.ga.disclosure.sign.identity;

import com.ga.disclosure.domain.enums.IdentityMethod;

import java.util.List;

/** 요구 본인확인 수단을 다 통과하지 않은 채 서명을 시도했다(업무 거부 — 세션은 바뀌지 않는다). 수단 이름만 담는다. */
public final class IdentityIncomplete extends RuntimeException {

    private final List<IdentityMethod> missing;

    public IdentityIncomplete(List<IdentityMethod> missing) {
        super("identity check incomplete: " + missing);
        this.missing = List.copyOf(missing);
    }

    public List<IdentityMethod> missing() {
        return missing;
    }
}
