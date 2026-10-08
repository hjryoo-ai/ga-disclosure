package com.ga.disclosure.workflow.authz;

import com.ga.platform.core.tenant.TenantId;

/**
 * 테넌트 행이 있는가(6A 계획 §8 ④ — 토큰의 테넌트 클레임을 바인딩한 뒤 RLS 아래에서 확인한다, 없으면 401). 인가가 아니다 — 형식이 맞는 모르는 테넌트를
 * 인증 단계에서 끊을 뿐이다.
 */
public interface TenantRegistry {

    boolean exists(TenantId tenant);
}
