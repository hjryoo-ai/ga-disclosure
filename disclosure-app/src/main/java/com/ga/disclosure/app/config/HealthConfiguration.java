package com.ga.disclosure.app.config;

import com.ga.disclosure.app.health.DatabaseHealthIndicator;
import com.ga.disclosure.app.health.IdpJwksHealthIndicator;
import com.ga.disclosure.app.health.StorageHealthIndicator;
import com.ga.disclosure.infra.storage.VerifiedArtifactStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.net.URI;
import java.util.Optional;

/**
 * 헬스·스키마 버전 가드(Phase 8, G7). 지표 이름(빈 이름에서 {@code HealthIndicator}를 뗀 것) — {@code database}·{@code storage}·{@code idpJwks}는
 * 준비성 그룹, 활성 그룹은 {@code livenessState}뿐({@code application.yaml}). Spring 기본 DB 지표({@code db})는 계속 끈다 — 앱 풀로 테넌트 바인딩 없이
 * 질의하기 때문이다.
 */
@Configuration
public class HealthConfiguration {

    /** 빈 팩토리 후처리기는 static으로(설정 클래스보다 먼저 만들어진다). */
    @Bean
    public static SchemaVersionGuard schemaVersionGuard() {
        return new SchemaVersionGuard();
    }

    @Bean
    public DatabaseHealthIndicator databaseHealthIndicator(Environment environment) {
        return SchemaVersionGuard.indicator(environment);
    }

    @Bean
    public StorageHealthIndicator storageHealthIndicator(VerifiedArtifactStore store) {
        return new StorageHealthIndicator(store);
    }

    @Bean
    public IdpJwksHealthIndicator idpJwksHealthIndicator(@Value("${ga.api.jwt.jwk-set-uri:}") String jwkSetUri) {
        return new IdpJwksHealthIndicator(jwkSetUri.isBlank() ? Optional.empty() : Optional.of(URI.create(jwkSetUri)));
    }
}
