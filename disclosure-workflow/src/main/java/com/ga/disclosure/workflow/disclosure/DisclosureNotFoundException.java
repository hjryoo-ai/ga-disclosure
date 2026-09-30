package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.DisclosureId;

/** 바인딩된 테넌트에 없는 확인서(다른 테넌트의 확인서도 여기로 온다 — 존재를 드러내지 않는다). */
public final class DisclosureNotFoundException extends RuntimeException {

    public DisclosureNotFoundException(DisclosureId id) {
        super("no disclosure " + id);
    }
}
