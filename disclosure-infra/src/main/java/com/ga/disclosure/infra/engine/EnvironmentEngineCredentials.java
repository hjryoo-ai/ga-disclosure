package com.ga.disclosure.infra.engine;

import com.ga.platform.core.tenant.TenantId;
import org.springframework.core.env.Environment;

import java.util.Objects;
import java.util.Optional;

/**
 * 배포 설정에서 읽는 서비스 토큰: {@code ga.engine.credentials.<TENANT_ID>}(값은 배포 비밀로 주입 — 저장소·명령줄에 두지 않는다).
 * 3A의 {@link EngineCredentialPort} 구현이며 Phase 8에서 볼트 구현으로 바뀐다.
 */
public final class EnvironmentEngineCredentials implements EngineCredentialPort {

    static final String PREFIX = "ga.engine.credentials.";

    private final Environment environment;

    public EnvironmentEngineCredentials(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    @Override
    public Optional<String> tokenFor(TenantId tenant) {
        String token = environment.getProperty(PREFIX + tenant.value());
        return token == null || token.isBlank() ? Optional.empty() : Optional.of(token);
    }
}
