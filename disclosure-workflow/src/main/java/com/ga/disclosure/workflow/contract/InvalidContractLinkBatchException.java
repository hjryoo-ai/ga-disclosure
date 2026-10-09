package com.ga.disclosure.workflow.contract;

import java.util.List;

/**
 * 배치가 계약 스키마·의미 규칙(날짜 실재)을 어겼다 — 아무것도 저장하지 않는다. 문제는 위치·규칙 이름뿐이고 값(증권·청약 번호)을 싣지 않는다.
 */
public class InvalidContractLinkBatchException extends RuntimeException {

    private final List<String> problems;

    public InvalidContractLinkBatchException(List<String> problems) {
        super("invalid contract-link batch: " + problems.size() + " problem(s)");
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
