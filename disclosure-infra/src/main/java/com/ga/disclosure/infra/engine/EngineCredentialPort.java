package com.ga.disclosure.infra.engine;

import com.ga.platform.core.tenant.TenantId;

import java.util.Optional;

/**
 * 엔진 서비스 토큰(테넌트별, 3A 계획 승인 B4). 3A 구현은 배포 설정({@link EnvironmentEngineCredentials}) — {@code tenant.params}(평문 JSONB)에는
 * 두지 않는다. Phase 8 운영 조립에서 Phase 2 볼트(테넌트 DEK, AAD = {@code tenant_id}·{@code engine_token}) 구현으로 교체한다.
 * 토큰 값은 로그·예외 메시지에 넣지 않는다.
 */
public interface EngineCredentialPort {

    Optional<String> tokenFor(TenantId tenant);
}
