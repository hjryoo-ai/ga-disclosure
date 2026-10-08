package com.ga.disclosure.api.dto;

/** 종이 스캔 업로드: 세션 토큰, 스캔본에서 읽은 확인서 번호·해시 접두, 이미지(JPEG·PNG, base64). */
public record PaperScanRequest(String token, String disclosureNo, String hashPrefix, String imageBase64) {
    /** 토큰·생체 서명·기기 지문은 싣지 않는다 — 프레임워크 TRACE 로그가 인자·반환값을 {@code toString}으로 찍는다(6A G6). */
    @Override
    public String toString() {
        return "PaperScanRequest[token=<redacted>, disclosureNo=" + disclosureNo + ", hashPrefix=" + hashPrefix + ", imageBase64=<redacted>]";
    }
}
