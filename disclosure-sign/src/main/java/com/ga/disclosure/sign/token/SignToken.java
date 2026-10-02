package com.ga.disclosure.sign.token;

import com.ga.disclosure.domain.vo.Sha256;
import com.ga.platform.core.tenant.TenantId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 고객 서명 토큰(4 계획 승인 Q4): {@code {tenantId}~{base64url(32바이트, 패딩 없음)}}. 테넌트 접두는 비밀이 아니다 — 공개 엔드포인트가
 * RLS 바인딩 전에 테넌트를 알기 위한 것이고, 비밀은 뒤 256비트다. 저장은 {@link #hash()}(토큰 전체 ASCII의 SHA-256)뿐이다.
 * {@link #toString()}은 비밀을 싣지 않는다 — 원문은 {@link #reveal()}로만 꺼낸다(통지·CLI 출력 한 곳).
 */
public final class SignToken {

    private static final char SEPARATOR = '~';
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final TenantId tenant;
    private final String secret;

    private SignToken(TenantId tenant, String secret) {
        this.tenant = Objects.requireNonNull(tenant, "tenant");
        this.secret = secret;
    }

    /** 새 토큰. 난수는 32바이트여야 한다. */
    public static SignToken issue(TenantId tenant, TokenSource source) {
        byte[] bits = source.next256Bits();
        if (bits == null || bits.length != 32) {
            throw new IllegalStateException("token source must return 32 bytes");
        }
        return new SignToken(tenant, ENCODER.encodeToString(bits));
    }

    /** 원문 해석. 형식이 틀리면(테넌트 형식 포함) {@link SignTokenRejected} — 원인을 구분하지 않는다. */
    public static SignToken parse(String raw) {
        if (raw == null) {
            throw new SignTokenRejected();
        }
        int at = raw.indexOf(SEPARATOR);
        if (at < 1 || !SECRET.matcher(raw.substring(at + 1)).matches()) {
            throw new SignTokenRejected();
        }
        TenantId tenant;
        try {
            tenant = TenantId.of(raw.substring(0, at));
        } catch (IllegalArgumentException e) {
            throw new SignTokenRejected();
        }
        return new SignToken(tenant, raw.substring(at + 1));
    }

    public TenantId tenant() {
        return tenant;
    }

    /** 저장·조회 키: SHA-256(토큰 전체 ASCII). {@link Sha256#equals}는 상수 시간 비교다. */
    public Sha256 hash() {
        try {
            return Sha256.ofDigest(MessageDigest.getInstance("SHA-256").digest(reveal().getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 토큰 원문. 고객에게 보내는 통지(REMOTE_LINK)·설계사 기기 응답 한 곳에서만 쓴다 — 로그·감사·예외에 넣지 않는다. */
    public String reveal() {
        return tenant.value() + SEPARATOR + secret;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SignToken other && hash().equals(other.hash());
    }

    @Override
    public int hashCode() {
        return tenant.hashCode();
    }

    @Override
    public String toString() {
        return "SignToken[" + tenant.value() + "~***]";
    }
}
