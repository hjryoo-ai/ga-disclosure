package com.ga.disclosure.rules.template;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 서식 배치의 섹션 1개({@code layout.sections[]}). 섹션 이름·순서·방향·소속 항목은 서식 데이터다.
 *
 * @param labelOrNull     섹션 제목(인쇄), 없으면 제목 없이 인쇄
 * @param columnsPerTable COLUMN_PER_ITEM 섹션의 표당 항목(열) 수(DOCUMENT 섹션은 0) — 인쇄 배치도 서식 데이터다
 */
public record LayoutSection(String code, Orientation orientation, String labelOrNull, int columnsPerTable, List<String> fieldCodes) {

    /** 항목(열)마다 한 열인 비교표, 또는 확인서당 한 값인 줄 목록. */
    public enum Orientation {
        COLUMN_PER_ITEM,
        DOCUMENT
    }

    public LayoutSection {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(orientation, "orientation");
        if ((orientation == Orientation.COLUMN_PER_ITEM) != (columnsPerTable >= 1)) {
            throw new IllegalArgumentException("section " + code + ": columnsPerTable is set exactly for COLUMN_PER_ITEM");
        }
        fieldCodes = List.copyOf(fieldCodes);
    }

    public Optional<String> label() {
        return Optional.ofNullable(labelOrNull);
    }
}
