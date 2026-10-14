package com.ga.disclosure.workflow.secret;

import java.util.regex.Pattern;

/**
 * 비밀의 이름: 소문자 첫 마디 + {@code /}로 나눈 마디(예: {@code kek/DEMO/DEMO-KEK-1}, {@code api/cursor}). 마디에 {@code ..}·빈 마디가 없다 —
 * 파일 어댑터가 이름을 경로로 쓰므로 출처 디렉터리 밖을 가리킬 수 없어야 한다.
 */
public record SecretName(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z][a-z0-9-]{0,31}(/[A-Za-z0-9][A-Za-z0-9_.-]{0,63}){0,3}");

    public SecretName {
        if (value == null || !FORMAT.matcher(value).matches() || value.contains("..")) {
            throw new IllegalArgumentException("invalid secret name");
        }
    }

    public static SecretName of(String value) {
        return new SecretName(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
