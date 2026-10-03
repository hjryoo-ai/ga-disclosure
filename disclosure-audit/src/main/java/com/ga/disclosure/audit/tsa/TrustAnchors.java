package com.ga.disclosure.audit.tsa;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 설정된 TSA 신뢰 앵커(배포 설정·{@code --tsa-trust} PEM). 비어 있을 수 있다 — 그때 검증 결과는 서명이 맞아도 UNTRUSTED이다(5 계획 §3).
 */
public record TrustAnchors(List<X509Certificate> certificates) {

    public TrustAnchors {
        certificates = List.copyOf(Objects.requireNonNull(certificates, "certificates"));
    }

    public static TrustAnchors none() {
        return new TrustAnchors(List.of());
    }

    public static TrustAnchors of(X509Certificate... certificates) {
        return new TrustAnchors(List.of(certificates));
    }

    /** PEM(여러 개 이어 붙임 가능). 인증서가 하나도 없거나 형식이 틀리면 {@link IllegalArgumentException}. */
    public static TrustAnchors fromPem(byte[] pem) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> parsed = factory.generateCertificates(new ByteArrayInputStream(pem)).stream()
                    .map(TrustAnchors::x509).toList();
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("trust bundle holds no certificate");
            }
            return new TrustAnchors(parsed);
        } catch (CertificateException e) {
            throw new IllegalArgumentException("trust bundle is not a PEM certificate list", e);
        }
    }

    public boolean isEmpty() {
        return certificates.isEmpty();
    }

    Set<TrustAnchor> asPkix() {
        Set<TrustAnchor> anchors = new LinkedHashSet<>();
        certificates.forEach(c -> anchors.add(new TrustAnchor(c, null)));
        return anchors;
    }

    public String toPem() {
        StringBuilder out = new StringBuilder();
        Base64.Encoder mime = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        for (X509Certificate c : certificates) {
            try {
                out.append("-----BEGIN CERTIFICATE-----\n").append(mime.encodeToString(c.getEncoded())).append("\n-----END CERTIFICATE-----\n");
            } catch (CertificateEncodingException e) {
                throw new IllegalStateException("certificate cannot be encoded", e);
            }
        }
        return out.toString();
    }

    private static X509Certificate x509(Certificate c) {
        if (c instanceof X509Certificate x) {
            return x;
        }
        throw new IllegalArgumentException("trust bundle holds a non-X.509 certificate");
    }
}
