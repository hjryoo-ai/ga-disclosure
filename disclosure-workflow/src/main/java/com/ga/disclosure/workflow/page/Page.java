package com.ga.disclosure.workflow.page;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 목록 한 쪽: 항목과 다음 쪽 커서(더 없으면 빈 값). */
public record Page<T>(List<T> items, Optional<String> next) {

    public Page {
        items = List.copyOf(items);
        Objects.requireNonNull(next, "next");
    }
}
