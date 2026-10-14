package com.ga.disclosure.infra.storage;

import com.ga.disclosure.workflow.artifact.ArtifactStore;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 첫 사용 직전에 버킷을 한 번 확인하는 저장소(Object Lock 활성·버전 관리 Enabled·기본 보존 규칙 없음 — {@link ArtifactStoreBootstrap}). 확인이
 * 실패하면 그 조작도 실패한다. 저장소를 쓰지 않는 명령(룰 배포·카탈로그 수입 등)과 부팅이 저장소 가용성에 묶이지 않게 기동 시점이 아니라 첫 사용
 * 시점에 확인한다. {@code createIfMissing}은 개발·데모 전용(운영 버킷은 인프라가 만든다).
 */
public final class VerifiedArtifactStore implements ArtifactStore {

    private final ArtifactStore delegate;
    private final ArtifactStoreBootstrap bootstrap;
    private final boolean createIfMissing;
    private volatile boolean verified;

    public VerifiedArtifactStore(ArtifactStore delegate, ArtifactStoreBootstrap bootstrap, boolean createIfMissing) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
        this.createIfMissing = createIfMissing;
    }

    /** S3 설정으로 조립한다(SDK 타입을 이 패키지 밖에 드러내지 않는다). */
    public static VerifiedArtifactStore of(S3StorageSettings settings, boolean createIfMissing) {
        return new VerifiedArtifactStore(S3ArtifactStore.of(settings),
                new ArtifactStoreBootstrap(S3ArtifactStore.client(settings), settings.bucket()), createIfMissing);
    }

    /**
     * 준비성 확인(Phase 8 — 첫 사용 때 하던 확인을 준비성으로 앞당긴다): 기대와 다른 점 목록, 비어 있으면 준비됨. 저장소에 닿지 못하면 그 사실 한 줄
     * (SDK 메시지·엔드포인트를 싣지 않는다). 통과하면 첫 사용 확인도 끝난 것으로 본다.
     */
    public List<String> readinessProblems() {
        List<String> problems;
        try {
            if (createIfMissing) {
                bootstrap.createIfMissing();
            }
            problems = bootstrap.problems();
        } catch (RuntimeException e) {
            return List.of("storage unreachable (" + e.getClass().getSimpleName() + ")");
        }
        if (problems.isEmpty()) {
            verified = true;
        }
        return problems;
    }

    private ArtifactStore ready() {
        if (!verified) {
            synchronized (this) {
                if (!verified) {
                    if (createIfMissing) {
                        bootstrap.createIfMissing();
                    }
                    bootstrap.verify();
                    verified = true;
                }
            }
        }
        return delegate;
    }

    @Override
    public void put(String key, byte[] bytes) {
        ready().put(key, bytes);
    }

    @Override
    public byte[] get(String key) {
        return ready().get(key);
    }

    @Override
    public boolean exists(String key) {
        return ready().exists(key);
    }

    @Override
    public void applyRetention(String key, Instant until) {
        ready().applyRetention(key, until);
    }

    @Override
    public Optional<Instant> retention(String key) {
        return ready().retention(key);
    }

    @Override
    public List<StoredObject> list(String prefix) {
        return ready().list(prefix);
    }

    @Override
    public void delete(String key) {
        ready().delete(key);
    }

    @Override
    public VersionCount versionCount(String key) {
        return ready().versionCount(key);
    }

    @Override
    public Capabilities capabilities() {
        return ready().capabilities();
    }

    @Override
    public void setLegalHold(String key, boolean on) {
        ready().setLegalHold(key, on);
    }

    @Override
    public boolean legalHold(String key) {
        return ready().legalHold(key);
    }
}
