package com.ga.disclosure.workflow.idempotency;

/**
 * 멱등 요청 해시(6B 계획 §A-2): HMAC-SHA256(서버 키, 정규화 입력)의 소문자 hex 64자. 키 없는 SHA-256은 본문의 정의역이 작으면(고객 이름·전화·생년월일)
 * 사전 대입으로 되돌릴 수 있다 — 6A 결함, 6B 수정. 구현은 infra {@code RequestHashKey}(JCA는 {@code infra.crypto}만).
 */
public interface RequestHashPort {

    /** {@code canonicalInput} = JCS{method, routeTemplate, pathVariables, body}의 UTF-8 바이트. */
    String hash(byte[] canonicalInput);
}
