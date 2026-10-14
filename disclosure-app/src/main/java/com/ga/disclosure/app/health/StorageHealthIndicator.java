package com.ga.disclosure.app.health;

import com.ga.disclosure.infra.storage.VerifiedArtifactStore;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.util.List;
import java.util.Objects;

/** 봉인 산출물 저장소 준비성(Phase 8): 버킷 Object Lock 활성·버전 관리 Enabled·기본 보존 규칙 없음({@link VerifiedArtifactStore#readinessProblems()}). */
public final class StorageHealthIndicator implements HealthIndicator {

    private final VerifiedArtifactStore store;

    public StorageHealthIndicator(VerifiedArtifactStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public Health health() {
        List<String> problems = store.readinessProblems();
        return problems.isEmpty() ? Health.up().build() : Health.down().withDetail("problems", problems).build();
    }
}
