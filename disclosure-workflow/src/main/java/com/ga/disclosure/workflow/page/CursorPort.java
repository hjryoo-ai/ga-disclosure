package com.ga.disclosure.workflow.page;

import com.ga.platform.core.tenant.TenantId;

/**
 * 불투명 커서(infra {@code CursorCodec}). 형식은 {@code base64url(JCS{v:1, s:stream, q:position}) "." base64url(HMAC-SHA256(key, tenant ‖ 0x00 ‖ 앞부분)[0..16])}
 * — 테넌트와 목록 종류({@code stream})가 다르면 열리지 않는다. {@code position}은 목록이 정한 키셋 위치 문자열이다.
 */
public interface CursorPort {

    String seal(TenantId tenant, String stream, String position);

    /** 위치 문자열. 형식·MAC·테넌트·목록 종류가 맞지 않으면 {@link InvalidCursorException}. */
    String open(TenantId tenant, String stream, String cursor);
}
