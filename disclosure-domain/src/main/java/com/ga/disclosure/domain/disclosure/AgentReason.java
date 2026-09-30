package com.ga.disclosure.domain.disclosure;

import com.ga.disclosure.domain.vo.ReasonCode;

import java.util.List;
import java.util.Objects;

/**
 * 설계사가 입력한 추천사유(추천사유 입력 명령의 원소, 항목 번호로 지목). 시스템은 이 값을 만들지 않는다(CLAUDE.md 절대 규칙 7).
 *
 * @param itemNo 1부터
 */
public record AgentReason(int itemNo, List<ReasonCode> codes, String textOrNull) {

    public AgentReason {
        if (itemNo < 1) {
            throw new IllegalArgumentException("itemNo starts at 1");
        }
        codes = List.copyOf(Objects.requireNonNull(codes, "codes"));
    }
}
