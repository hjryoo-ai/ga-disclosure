package com.ga.disclosure.api.dto;

import java.util.List;

/** 본인확인 결과: 모든 수단 통과 여부와 남은 수단. 남은 시도 수는 내지 않는다. */
public record PublicIdentityResult(boolean passed, List<String> missing) {
}
