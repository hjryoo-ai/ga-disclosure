package com.ga.disclosure.api.dto;

/** 청약 게이트 질의(6B 계획 §6): 청약번호 또는 증권번호 중 하나와 고객 가명. 번호는 로그·예외에 싣지 않는다. */
public record GateRequest(String applicationNo, String policyNo, String customerRef) {

    @Override
    public String toString() {
        return "GateRequest[applicationNo=" + (applicationNo == null ? "-" : "***") + ", policyNo=" + (policyNo == null ? "-" : "***") + "]";
    }
}
