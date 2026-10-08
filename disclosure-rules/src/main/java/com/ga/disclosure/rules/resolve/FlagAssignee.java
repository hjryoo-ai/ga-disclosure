package com.ga.disclosure.rules.resolve;

/** 준법 플래그 담당 역할(룰 {@code complianceQueue.types[].assignedRole}, DB CHECK {@code ck_compliance_flag_assigned_role}과 같은 닫힌 어휘). */
public enum FlagAssignee {
    COMPLIANCE,
    MANAGER
}
