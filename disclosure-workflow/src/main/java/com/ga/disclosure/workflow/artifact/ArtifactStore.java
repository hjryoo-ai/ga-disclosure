package com.ga.disclosure.workflow.artifact;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 객체 저장소 포트(S3 호환 + Object Lock, 3B 계획 §6). 어댑터는 벤더 무관 표준 S3 API만 쓴다(승인 Q1). 버킷은 테넌트 공통 1개이고 키 접두
 * {@code {tenant}/}로 나눈다. 버킷에 기본 보존 규칙을 두지 않는다 — 잠금은 커밋 후 {@link #applyRetention}으로만 건다(커밋 전 잠금 금지).
 * 저장 바이트는 언제나 암호문이다(클라이언트 측 암호화).
 */
public interface ArtifactStore {

    /** 잠금 없이 올린다. 같은 키가 있으면 새 버전이 된다(객체 키가 암호문 해시라 정상 경로에서는 생기지 않는다). */
    void put(String key, byte[] bytes);

    /** 최신 버전의 바이트. 없으면 {@link ArtifactMissingException}. */
    byte[] get(String key);

    boolean exists(String key);

    /**
     * 최신 버전에 COMPLIANCE 보존을 {@code until}까지 건다. 연장은 허용, 단축은 저장소가 거부한다({@link ObjectLockedException}) — 이 포트에
     * 단축·해제 경로는 없다(승인 Q6).
     */
    void applyRetention(String key, Instant until);

    /** 최신 버전의 보존 기한(없으면 빈 값). */
    Optional<Instant> retention(String key);

    /** 접두 아래 객체(최신 버전) 목록 — 잔여물 정리용. */
    List<StoredObject> list(String prefix);

    /**
     * 키의 모든 버전을 지운다(잔여물 정리 전용). 잠긴 버전이 있으면 저장소가 거부하고 {@link ObjectLockedException} — 잠긴 산출물은 보존기한 전에
     * 어떤 경로로도 지워지지 않는다.
     */
    void delete(String key);

    /** 객체 1건(최신 버전). */
    record StoredObject(String key, Instant lastModified, long size) {
    }
}
