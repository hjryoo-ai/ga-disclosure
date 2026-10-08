package com.ga.disclosure.api.mapper;

import com.ga.disclosure.api.error.MalformedRequestException;

import java.util.Optional;

/** 목록 매개변수(6A 계획 §4.2): {@code limit} 1~100(기본 50), {@code after}는 불투명 커서 그대로(유스케이스가 연다). */
public final class PageMapper {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private PageMapper() {
    }

    public static int limit(Integer limitOrNull) {
        int limit = limitOrNull == null ? DEFAULT_LIMIT : limitOrNull;
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new MalformedRequestException("limit");
        }
        return limit;
    }

    public static Optional<String> after(String afterOrNull) {
        return Optional.ofNullable(afterOrNull);
    }
}
