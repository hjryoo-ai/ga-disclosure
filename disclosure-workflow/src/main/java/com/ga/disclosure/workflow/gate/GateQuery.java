package com.ga.disclosure.workflow.gate;

import com.ga.disclosure.domain.vo.CustomerRef;

import java.util.Objects;
import java.util.regex.Pattern;

/** 게이트 질의: 청약번호 또는 증권번호 하나와 고객 가명. 번호는 예외 메시지·감사에 원문으로 싣지 않는다. */
public record GateQuery(Kind kind, String identifier, CustomerRef customerRef) {

    public enum Kind { APPLICATION_NO, POLICY_NO }

    /** 청약·증권 번호 형식(§14 #19 — 공백 없는 1~64자). */
    public static final Pattern NUMBER = Pattern.compile("\\S{1,64}");

    public GateQuery {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(customerRef, "customerRef");
        if (!NUMBER.matcher(identifier).matches()) {
            throw new IllegalArgumentException("the identifier must be 1..64 non-space characters");
        }
    }

    @Override
    public String toString() {
        return "GateQuery[" + kind + ", <identifier>, " + customerRef + "]";
    }
}
