package com.ga.disclosure.infra.engine;

import com.ga.platform.core.tenant.TenantId;

import java.util.Optional;

/**
 * 엔진 서비스 토큰(테넌트별, 3A 계획 승인 B4) — {@code tenant.params}(평문 JSONB)에는 두지 않는다. (Phase 8) 구현은 비밀 출처
 * {@link SecretEngineCredentials}({@code engine/<TENANT>}) — 3A가 예정한 "Phase 2 볼트(DB)" 대신 비밀 저장소를 단일 출처로(8 계획 Q12).
 * 토큰 값은 로그·예외 메시지에 넣지 않는다.
 */
public interface EngineCredentialPort {

    Optional<String> tokenFor(TenantId tenant);
}
