package com.ga.platform.spring.jdbc;

/** 단건이어야 하는 조회가 2건 이상을 돌려줬다(기준일 단건 해석의 fail-fast 규약). */
public final class AmbiguousResultException extends IllegalStateException {

    private final int count;

    public AmbiguousResultException(int count) {
        super("expected at most one row but found " + count);
        this.count = count;
    }

    public int count() {
        return count;
    }
}
