package com.ga.disclosure.workflow.catalog;

import java.util.List;

/** 수입 파일이 계약 스키마 또는 의미 규칙(중복 키·키 접두·실수 금액·구간)을 어겼다. 아무것도 저장하지 않는다. */
public class InvalidCatalogFileException extends RuntimeException {

    private final List<String> problems;

    public InvalidCatalogFileException(List<String> problems) {
        super("invalid catalog file: " + problems);
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
