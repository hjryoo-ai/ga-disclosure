package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.DisclosureId;

/**
 * 정정 계보(V1 {@code version}·{@code supersedes_id}): 첫 버전은 1·없음, 정정본은 원본 버전 + 1과 원본 ID(설계서 §6.6).
 */
public record Lineage(int version, DisclosureId supersedesIdOrNull) {

    public static final Lineage FIRST = new Lineage(1, null);

    public Lineage {
        if (version < 1 || version > Short.MAX_VALUE) {
            throw new IllegalArgumentException("version must be 1..32767");
        }
        if ((version == 1) != (supersedesIdOrNull == null)) {
            throw new IllegalArgumentException("only a corrected version (≥ 2) supersedes another");
        }
    }

    public Lineage next(DisclosureId original) {
        return new Lineage(version + 1, original);
    }
}
