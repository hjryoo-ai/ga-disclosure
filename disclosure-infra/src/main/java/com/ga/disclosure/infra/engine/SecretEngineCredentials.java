package com.ga.disclosure.infra.engine;

import com.ga.disclosure.workflow.secret.SecretName;
import com.ga.disclosure.workflow.secret.SecretSource;
import com.ga.platform.core.tenant.TenantId;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

/**
 * 엔진 서비스 토큰(Phase 8, 8 계획 Q12): 비밀 {@code engine/<TENANT_ID>}(UTF-8 한 줄, 앞뒤 공백 제거). 설계서 §9의 "Phase 8에서 Phase 2 볼트(DB, 테넌트
 * DEK)"를 비밀 저장소로 바꿨다 — 비밀의 출처를 하나로 두고 회전은 비밀 저장소가 한다(엔진은 겹침 기간 두 해시를 인정 — E3.1). 없으면 빈 값(그 테넌트의 엔진
 * 호출은 명시 오류).
 */
public final class SecretEngineCredentials implements EngineCredentialPort {

    private final SecretSource secrets;

    public SecretEngineCredentials(SecretSource secrets) {
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    static SecretName name(TenantId tenant) {
        return SecretName.of("engine/" + tenant.value());
    }

    @Override
    public Optional<String> tokenFor(TenantId tenant) {
        SecretName name = name(tenant);
        if (!secrets.exists(name)) {
            return Optional.empty();
        }
        String token = new String(secrets.read(name), StandardCharsets.UTF_8).strip();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
