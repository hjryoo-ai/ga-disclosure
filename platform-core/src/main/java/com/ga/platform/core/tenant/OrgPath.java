package com.ga.platform.core.tenant;

import java.util.regex.Pattern;

/**
 * 조직 경로({@code /HQ/B1}). 토큰 클레임에서 만들지 않는다 — {@code identity_link} 조회 결과나 확인서의 작성 시점 스냅샷으로만 만든다
 * (CLAUDE.md 절대 규칙 5). 범위 판정은 <b>세그먼트 단위</b> 접두다: {@code /HQ}는 {@code /HQ/B1}을 포함하지만 {@code /HQX}는 포함하지 않는다.
 */
public record OrgPath(String value) {

    private static final Pattern FORMAT = Pattern.compile("(/[A-Za-z0-9_-]+)+");

    public OrgPath {
        if (value == null || value.length() > 512 || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid org path");
        }
    }

    public static OrgPath of(String value) {
        return new OrgPath(value);
    }

    /** {@code other}가 이 경로이거나 그 아래인가(세그먼트 단위). */
    public boolean contains(OrgPath other) {
        return other.value.equals(value) || other.value.startsWith(value + "/");
    }

    @Override
    public String toString() {
        return value;
    }
}
