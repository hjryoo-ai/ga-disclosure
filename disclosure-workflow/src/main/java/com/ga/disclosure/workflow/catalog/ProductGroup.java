package com.ga.disclosure.workflow.catalog;

import com.ga.disclosure.domain.enums.InsuranceLine;
import com.ga.disclosure.domain.vo.GroupCode;

import java.time.LocalDate;
import java.util.Objects;

/** 유사상품군(외부 코드 체계, 설계서 §14 #1). 유효기간 [applyFrom, applyTo), applyTo null이면 무기한. */
public record ProductGroup(GroupCode code, String name, InsuranceLine line, LocalDate applyFrom, LocalDate applyTo) {

    public ProductGroup {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(applyFrom, "applyFrom");
        Validity.check(applyFrom, applyTo);
    }

    public boolean inForceOn(LocalDate date) {
        return Validity.contains(applyFrom, applyTo, date);
    }
}
