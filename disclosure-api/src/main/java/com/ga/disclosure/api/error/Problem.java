package com.ga.disclosure.api.error;

import com.ga.platform.canonical.Canonicalizer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 오류 응답(6A 계획 §4.2). 본문은 JCS 바이트다 — 같은 오류는 언제나 같은 바이트(권한 없음과 없는 자원의 404가 바이트로 같아야 한다, G2). {@code message}는
 * 코드별 고정 영문 문장이고 입력값·자원 값을 넣지 않는다. {@code details}는 닫힌 모양 둘뿐이다: {@code {rejections:[{code, ruleId?}]}},
 * {@code {field}}.
 */
public final class Problem {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 코드 → 고정 문장(닫힌 목록 — 여기에 없는 코드는 만들 수 없다). */
    static final Map<String, String> MESSAGES = Map.ofEntries(
            Map.entry("UNAUTHENTICATED", "Authentication required."),
            Map.entry("NOT_FOUND", "Resource not found."),
            Map.entry("METHOD_NOT_ALLOWED", "Method not allowed."),
            Map.entry("MALFORMED_REQUEST", "The request is malformed."),
            Map.entry("REJECTED", "The command was rejected."),
            Map.entry("CONFLICT", "The resource is in a conflicting state."),
            Map.entry("CONCURRENT_WRITE", "The resource was changed concurrently; retry."),
            Map.entry("JOB_ALREADY_RUNNING", "A job of this kind is already running."),
            Map.entry("REPORT_NOT_AVAILABLE", "The job has no report."),
            Map.entry("IDEMPOTENCY_KEY_REQUIRED", "An Idempotency-Key header is required."),
            Map.entry("IDEMPOTENCY_KEY_REUSED", "The Idempotency-Key was used for a different request."),
            Map.entry("IDEMPOTENCY_IN_PROGRESS", "A request with this Idempotency-Key is in progress."),
            Map.entry("IDEMPOTENCY_NOT_REPLAYABLE", "The original response carried a one-time credential and is not replayed."),
            Map.entry("INVALID_CURSOR", "The cursor is not valid."),
            Map.entry("INTERNAL_ERROR", "Internal error."));

    private Problem() {
    }

    public static ResponseEntity<byte[]> of(HttpStatus status, String code) {
        return of(status, code, JSON.createObjectNode());
    }

    /** {@code details.field} — 형식이 틀린 필드 이름(값은 싣지 않는다). */
    public static ResponseEntity<byte[]> field(HttpStatus status, String code, String fieldOrNull) {
        ObjectNode details = JSON.createObjectNode();
        if (fieldOrNull != null) {
            details.put("field", fieldOrNull);
        }
        return of(status, code, details);
    }

    /** {@code details.rejections} — 업무 거부 코드(와 규칙 ID). */
    public static ResponseEntity<byte[]> rejections(HttpStatus status, List<Rejection> rejections) {
        ObjectNode details = JSON.createObjectNode();
        ArrayNode list = details.putArray("rejections");
        rejections.forEach(r -> {
            ObjectNode o = list.addObject().put("code", r.code());
            if (r.ruleIdOrNull() != null) {
                o.put("ruleId", r.ruleIdOrNull());
            }
        });
        return of(status, "REJECTED", details);
    }

    public record Rejection(String code, String ruleIdOrNull) {
        public Rejection {
            Objects.requireNonNull(code, "code");
        }
    }

    public static byte[] body(String code, ObjectNode details) {
        String message = MESSAGES.get(code);
        if (message == null) {
            throw new IllegalArgumentException("unknown problem code " + code);
        }
        ObjectNode o = JSON.createObjectNode().put("code", code).put("message", message);
        o.set("details", details);
        return Canonicalizer.canonicalize(o);
    }

    private static ResponseEntity<byte[]> of(HttpStatus status, String code, ObjectNode details) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body(code, details), headers, status);
    }
}
