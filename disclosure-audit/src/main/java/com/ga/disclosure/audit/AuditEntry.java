package com.ga.disclosure.audit;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 감사 기록 1건의 본문(체인 위치·해시 제외). {@code at}은 저장 정밀도(PostgreSQL {@code TIMESTAMPTZ} = 마이크로초)로 절삭된다 —
 * DB에서 다시 읽어도 같은 정규화 바이트가 나와야 해시가 재현된다.
 *
 * @param detail JSON 객체(행위별 세부). 고객 개인정보를 넣지 않는다(CLAUDE.md 절대 규칙 6).
 */
public record AuditEntry(Instant at, String actorSubject, String actorRole, AuditAction action, String targetKind, String targetId,
                         JsonNode detail) {

    public AuditEntry {
        at = Objects.requireNonNull(at, "at").truncatedTo(ChronoUnit.MICROS);
        Objects.requireNonNull(action, "action");
        detail = Objects.requireNonNull(detail, "detail").deepCopy();
        if (!detail.isObject()) {
            throw new IllegalArgumentException("audit detail must be a JSON object");
        }
    }

    @Override
    public JsonNode detail() {
        return detail.deepCopy();
    }
}
