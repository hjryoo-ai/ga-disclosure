package com.ga.disclosure.workflow.catalog;

import java.time.LocalDate;
import java.util.Objects;

/** 반개구간 [from, to) — 룰 버전과 같은 규약(설계서 §5). to null이면 무기한, from = to는 빈 구간(어느 날에도 유효하지 않음). */
final class Validity {

    private Validity() {
    }

    static void check(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("validity end " + to + " is before start " + from);
        }
    }

    static boolean contains(LocalDate from, LocalDate to, LocalDate date) {
        Objects.requireNonNull(date, "date");
        return !date.isBefore(from) && (to == null || date.isBefore(to));
    }
}
