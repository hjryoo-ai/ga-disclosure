package com.ga.disclosure.infra.migration;

import java.util.Arrays;
import java.util.Objects;

/**
 * 마이그레이션 버전({@code V22__…}의 {@code 22}, 점 구분 정수열). 비교는 자리마다 정수로 — 문자열 비교면 {@code 9 > 22}가 된다.
 * 금액·비율이 아니다(정수 자리 번호).
 */
public record SchemaVersion(int[] parts) implements Comparable<SchemaVersion> {

    public SchemaVersion {
        Objects.requireNonNull(parts, "parts");
        if (parts.length == 0) {
            throw new IllegalArgumentException("empty schema version");
        }
        parts = parts.clone();
    }

    /** {@code "22"}·{@code "1.2"} — 점·밑줄 구분(Flyway 파일 이름은 밑줄도 점으로 읽는다). */
    public static SchemaVersion parse(String text) {
        Objects.requireNonNull(text, "text");
        if (!text.matches("[0-9]{1,6}([._][0-9]{1,6}){0,3}")) {
            throw new IllegalArgumentException("not a schema version: " + text);
        }
        return new SchemaVersion(Arrays.stream(text.split("[._]")).mapToInt(Integer::parseInt).toArray());
    }

    @Override
    public int compareTo(SchemaVersion other) {
        int n = Math.max(parts.length, other.parts.length);
        for (int i = 0; i < n; i++) {
            int a = i < parts.length ? parts[i] : 0;
            int b = i < other.parts.length ? other.parts[i] : 0;
            if (a != b) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SchemaVersion v && compareTo(v) == 0;
    }

    @Override
    public int hashCode() {
        int[] trimmed = parts;
        int end = trimmed.length;
        while (end > 1 && trimmed[end - 1] == 0) {
            end--;
        }
        return Arrays.hashCode(Arrays.copyOf(trimmed, end));
    }

    @Override
    public String toString() {
        return String.join(".", Arrays.stream(parts).mapToObj(Integer::toString).toList());
    }
}
