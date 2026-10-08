package com.ga.disclosure.workflow.job;

import tools.jackson.databind.node.ObjectNode;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * HTTP 제출이 쓰는 종류별 작업 본체(매개변수 → 본체). 앵커는 등록하지 않는다(승인 Q7). 조립은 앱이 한다.
 */
public final class JobHandlers {

    private final Map<JobKind, Function<ObjectNode, JobWork<?>>> handlers;

    public JobHandlers(Map<JobKind, Function<ObjectNode, JobWork<?>>> handlers) {
        if (handlers.containsKey(JobKind.ANCHOR)) {
            throw new IllegalArgumentException("ANCHOR runs only from the operator CLI (6A approval Q7)");
        }
        this.handlers = new EnumMap<>(JobKind.class);
        this.handlers.putAll(handlers);
    }

    /** 매개변수가 잘못됐으면 {@link IllegalArgumentException}(400). 등록되지 않은 종류는 빈 값. */
    public Optional<JobWork<?>> work(JobKind kind, ObjectNode params) {
        Function<ObjectNode, JobWork<?>> h = handlers.get(kind);
        return h == null ? Optional.empty() : Optional.of(h.apply(params));
    }

    public boolean supports(JobKind kind) {
        return handlers.containsKey(kind);
    }
}
