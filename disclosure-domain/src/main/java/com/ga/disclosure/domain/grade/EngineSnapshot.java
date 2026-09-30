package com.ga.disclosure.domain.grade;

import java.time.Instant;
import java.util.Objects;

/**
 * 계약 스키마 검증·정합성 검증을 마친 엔진 응답 전체: 등급·순위 스냅샷({@link GradeSnapshot})과 헤더에 보존하는 원문 두 가지.
 *
 * @param basisCanonicalJson 엔진 {@code basis} 객체의 RFC 8785 정규형(도메인은 해석하지 않는다, V6 {@code grade_basis})
 * @param generatedAt        엔진 {@code generatedAt}(노후 검사는 3B 봉인 조건)
 */
public record EngineSnapshot(GradeSnapshot snapshot, String basisCanonicalJson, Instant generatedAt) {

    public EngineSnapshot {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(generatedAt, "generatedAt");
        if (basisCanonicalJson == null || !basisCanonicalJson.startsWith("{")) {
            throw new IllegalArgumentException("basis must be a canonical JSON object");
        }
    }
}
