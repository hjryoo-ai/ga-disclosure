package com.ga.disclosure.audit.verify;

/** 발견 코드(지시문 13개 + 계획 추가 4개, 5 계획 §4). 스키마 {@code verify-report.schema.json#/$defs/findingCode}와 같은 목록이다(테스트 대조). */
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
    RECEIPT_NOT_COVERING
}
