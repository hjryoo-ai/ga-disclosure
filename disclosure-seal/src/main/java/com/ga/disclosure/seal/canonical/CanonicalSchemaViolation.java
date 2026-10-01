package com.ga.disclosure.seal.canonical;

import java.util.List;

/** 봉인 본문이 스키마를 어겼다 — 코드 결함이며 봉인은 롤백된다(명령 오류). 메시지에 문서 값은 넣지 않는다(스키마 경로·규칙만). */
public final class CanonicalSchemaViolation extends RuntimeException {

    private final List<String> problems;

    public CanonicalSchemaViolation(List<String> problems) {
        super("canonical document violates contracts/seal/v1/canonical.schema.json (" + problems.size() + " problems)");
        this.problems = List.copyOf(problems);
    }

    /** 스키마 경로와 규칙(값은 포함될 수 있으므로 로그·감사에 싣지 않는다 — 테스트 진단용). */
    public List<String> problems() {
        return problems;
    }
}
