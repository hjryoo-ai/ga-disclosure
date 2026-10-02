package com.ga.disclosure.domain.enums;

/** 서명 증거 객체 종류(V8 {@code signature_evidence.kind}, 승인 Q2): 터치 스트로크(좌표·시각 열), 서명 이미지, 종이 스캔. */
public enum SignatureEvidenceKind {
    STROKES,
    IMAGE,
    SCAN
}
