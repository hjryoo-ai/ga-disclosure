package com.ga.disclosure.domain.vo;

import com.ga.platform.core.tenant.TenantId;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 확인서 번호 {@code {tenant}-{yyyy}-{6자리}}(예: {@code T1-2026-000481}). 봉인 시 채번된다.
 * 번호 체계는 가상의 예시값이다(CLAUDE.md 면책).
 *
 * @param tenant   테넌트
 * @param year     발급 연도(4자리)
 * @param sequence 테넌트·연도 시퀀스(1~999999)
 */
public record DisclosureNo(TenantId tenant, int year, int sequence) {

    private static final Pattern FORMAT = Pattern.compile("([A-Z0-9][A-Z0-9_]{0,31})-(\\d{4})-(\\d{6})");

    public DisclosureNo {
        Objects.requireNonNull(tenant, "tenant");
        if (year < 1000 || year > 9999) {
            throw new IllegalArgumentException("year out of range: " + year);
        }
        if (sequence < 1 || sequence > 999_999) {
            throw new IllegalArgumentException("sequence out of range: " + sequence);
        }
    }

    public static DisclosureNo parse(String raw) {
        Matcher m = raw == null ? null : FORMAT.matcher(raw);
        if (m == null || !m.matches()) {
            throw new IllegalArgumentException("invalid disclosure no: " + raw);
        }
        return new DisclosureNo(TenantId.of(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }

    public String value() {
        return String.format(java.util.Locale.ROOT, "%s-%04d-%06d", tenant.value(), year, sequence);   // 로케일 숫자 표기 배제
    }

    @Override
    public String toString() {
        return value();
    }
}
