package com.ga.disclosure.domain.enums;

/** 대상 계약 ↔ 확인서 대사 결과(§6.8, {@code subject_policy.recon_status}). */
public enum ReconStatus {
    MATCHED,
    MISSING,
    LATE,
    EXEMPT
}
