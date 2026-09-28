package com.ga.disclosure.domain.vo;

/** 엔진 등급·순위 산출 스냅샷 ID(예: {@code GRD-20260923-000481}). 원 수치는 이 ID로만 엔진에서 역추적한다. */
public record SnapshotId(String value) {

    public SnapshotId {
        Patterns.require(Patterns.OPAQUE_CODE, value, "snapshot id");
    }

    public static SnapshotId of(String value) {
        return new SnapshotId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
