package com.ga.disclosure.seal.renderer;

import com.ga.disclosure.domain.enums.IdentityMethod;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignatureMethod;
import com.ga.disclosure.domain.enums.SignerRole;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 서명 외관 페이지의 한 줄(서명 레코드 요약). 본인확인은 결과만(입력값 없음), 이미지는 서명 PNG(SSO 승인은 없음). 이미지 바이트는 페이지에만
 * 그려지고 서명본 바이트 외 어디에도 남지 않는다 — 증거 패키지에는 해시만(4 계획 §4).
 *
 * @param imagePng 서명 이미지 PNG, 없으면 {@code null}
 */
public record SignatureAppearance(SignerRole role, SignatureChannel channel, SignatureMethod method, Instant signedAt,
                                  List<IdentityResult> identity, byte[] imagePng) {

    /** 본인확인 수단 하나의 결과. */
    public record IdentityResult(IdentityMethod method, boolean passed) {
        public IdentityResult {
            Objects.requireNonNull(method, "method");
        }
    }

    public SignatureAppearance {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(signedAt, "signedAt");
        identity = List.copyOf(identity);
        imagePng = imagePng == null ? null : imagePng.clone();
    }

    @Override
    public byte[] imagePng() {
        return imagePng == null ? null : imagePng.clone();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SignatureAppearance a && role == a.role && channel == a.channel && method == a.method
                && signedAt.equals(a.signedAt) && identity.equals(a.identity) && Arrays.equals(imagePng, a.imagePng);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, channel, method, signedAt, identity, Arrays.hashCode(imagePng));
    }

    @Override
    public String toString() {
        return "SignatureAppearance[" + role + " " + channel + " " + method + " " + signedAt + " image="
                + (imagePng == null ? "none" : imagePng.length + " bytes") + "]";
    }
}
