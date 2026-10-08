package com.ga.disclosure.api.dto;

/** 종이 스캔 업로드: 세션 토큰, 스캔본에서 읽은 확인서 번호·해시 접두, 이미지(JPEG·PNG, base64). */
public record PaperScanRequest(String token, String disclosureNo, String hashPrefix, String imageBase64) {
}
