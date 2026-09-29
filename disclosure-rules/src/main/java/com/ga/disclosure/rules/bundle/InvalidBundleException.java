package com.ga.disclosure.rules.bundle;

import java.io.Serial;
import java.util.List;

/** 번들 파일이 스키마·ID·해시 규칙을 어겼다. 배포는 시작되지 않는다. */
public class InvalidBundleException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient List<String> problems;

    public InvalidBundleException(String source, List<String> problems) {
        super("invalid bundle " + source + ": " + problems);
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
