package com.ga.disclosure.workflow.sign;

import java.util.regex.Pattern;

/** 서명 IP 형식 검사(V8 {@code signature.ip INET}): IPv4 점 표기 또는 IPv6 16진 표기만 받는다 — 이름 해석은 하지 않는다. */
final class IpAddress {

    private static final Pattern V4 = Pattern.compile("((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");
    private static final Pattern V6 = Pattern.compile("[0-9A-Fa-f:]{2,39}");

    private IpAddress() {
    }

    static String check(String ipOrNull) {
        if (ipOrNull == null) {
            return null;
        }
        if (!V4.matcher(ipOrNull).matches() && !(V6.matcher(ipOrNull).matches() && ipOrNull.contains(":"))) {
            throw new IllegalArgumentException("ip must be an IPv4 or IPv6 literal");
        }
        return ipOrNull;
    }
}
