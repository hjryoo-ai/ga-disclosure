package com.ga.disclosure.api.dto;

import java.util.List;

/** 검증 미리보기 영수증: 규칙 ID·통과·오버라이드 가능·실패 대상 해시(예외 승인 입력). 문장은 싣지 않는다. */
public record ValidationReceipt(String disclosureId, List<Result> results) {

    public record Result(String ruleId, boolean passed, boolean overridable, String subjectHash) {
    }
}
