package com.ga.disclosure.audit.verify;

/** 발견 코드(지시문 13개 + 계획 추가 4개, 5 계획 §4; 6A R1 {@code ANCHOR_MISSING_DAY}; Phase 8 {@code KEK_UNREGISTERED}). 스키마 {@code verify-report.schema.json#/$defs/findingCode}와 같은 목록이다(테스트 대조). */
public enum FindingCode {
    PACKAGE_ENTRY_MISMATCH,
    SIGNED_PDF_NOT_PREFIXED,
    SIGNATURE_BINDING_MISMATCH,
    AUDIT_ENTRY_MISMATCH,
    SEAL_CHAIN_BROKEN,
    AUDIT_CHAIN_BROKEN,
    NUMBERING_GAP,
    OBJECT_HASH_MISMATCH,
    OBJECT_MISSING,
    OBJECT_NOT_DELETED,
    DESTRUCTION_UNAUDITED,
    ANCHOR_MISMATCH,
    RECEIPT_PATH_INVALID,
    TSA_INVALID,
    TSA_UNTRUSTED,
    ANCHOR_UNSTAMPED,
    RECEIPT_NOT_COVERING,
    ANCHOR_MISSING_DAY,
    /** (Phase 8) 테넌트 KEK 레지스트리에 없는 KEK ID로 감싼 살아 있는 키 — 재래핑하지 않은 전역 시절 키 또는 레지스트리 밖의 키(운영 신호, 플래그 없음). */
    KEK_UNREGISTERED,
    /**
     * (Phase 8) 레지스트리의 KEK로 감싼 살아 있는 키가 풀리지 않는다 — 재래핑 함수가 검증할 수 없는 바이트로 키를 바꿨거나 KEK 바이트가 비밀 저장소에서
     * 사라졌다(무결성 — {@code CHAIN_BROKEN}, 보안 검토 반영).
     */
    KEK_UNWRAP_FAILED
}
