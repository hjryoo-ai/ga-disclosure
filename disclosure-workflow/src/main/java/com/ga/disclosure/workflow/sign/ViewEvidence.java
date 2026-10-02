package com.ga.disclosure.workflow.sign;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Objects;

/**
 * 열람 증거(V8 {@code sign_session.view_evidence}·{@code signature.view_evidence}): 열람 완료 시각, 끝까지 스크롤했는가, 열람 소요 초. 세션에 1회만
 * 기록된다(GD101). {@code scrollComplete}가 본인확인 수단 {@code SCROLL_COMPLETE}의 근거다(4 계획 §2.4).
 */
public record ViewEvidence(Instant viewedAt, boolean scrollComplete, int viewSeconds) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public ViewEvidence {
        Objects.requireNonNull(viewedAt, "viewedAt");
        if (viewSeconds < 0) {
            throw new IllegalArgumentException("viewSeconds < 0");
        }
    }

    public ObjectNode toJson() {
        return JSON.createObjectNode().put("viewedAt", viewedAt.toString()).put("scrollComplete", scrollComplete).put("viewSeconds", viewSeconds);
    }

    public static ViewEvidence fromJson(JsonNode n) {
        return new ViewEvidence(Instant.parse(n.get("viewedAt").asString()), n.get("scrollComplete").booleanValue(), n.get("viewSeconds").intValue());
    }
}
