package com.ga.platform.core.tenant;

import java.util.regex.Pattern;

/**
 * 테넌트 식별자. 대문자·숫자·밑줄, 1~32자, 첫 글자는 대문자 또는 숫자.
 *
 * <p>하이픈을 허용하지 않는다 — 확인서 번호({@code {tenant}-{yyyy}-{seq}})의 구분자이기 때문이다.
 * 대소문자를 섞지 않는 것은 RLS 비교({@code tenant_id = current_setting(...)})와 해시 입력의 결정론을 위해서다.
 */
public record TenantId(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-Z0-9][A-Z0-9_]{0,31}");

    public TenantId {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid tenant id: " + value);
        }
    }

    public static TenantId of(String value) {
        return new TenantId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
