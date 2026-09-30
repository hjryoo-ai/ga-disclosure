package com.ga.disclosure.workflow.catalog;

import com.ga.disclosure.domain.enums.InsuranceLine;
import com.ga.disclosure.domain.vo.InsurerCode;

import java.time.LocalDate;
import java.util.Objects;

/** 보험사 패널 항목 = GA가 위탁계약을 맺은 기간 [activeFrom, activeTo). 같은 보험사의 기간은 겹치지 않는다(DB 배타 제약). */
public record PanelEntry(InsurerCode insurer, String insurerName, InsuranceLine line, LocalDate activeFrom, LocalDate activeTo) {

    public PanelEntry {
        Objects.requireNonNull(insurer, "insurer");
        Objects.requireNonNull(insurerName, "insurerName");
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(activeFrom, "activeFrom");
        Validity.check(activeFrom, activeTo);
    }

    public boolean activeOn(LocalDate date) {
        return Validity.contains(activeFrom, activeTo, date);
    }
}
