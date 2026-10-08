package com.ga.disclosure.app.api;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;

/**
 * 시험용 발급기: RSA 키 한 쌍(프로세스당 하나)으로 JWT를 서명하고 공개키 PEM을 임시 파일로 내보낸다(앱의 {@code ga.api.jwt.public-key-location}).
 * 클레임은 {@code sub}·{@code tenant_id}·{@code iss}·{@code aud}·{@code exp}뿐 — 역할 클레임은 없다(역할은 {@code identity_link}).
 */
public final class TestJwts {

    public static final String ISSUER = "ga-test";
    public static final String AUDIENCE = "ga-disclosure";
    private static final KeyPair KEYS = generate();
    private static final KeyPair OTHER_KEYS = generate();
    private static final Path PEM = exportPem();

    private TestJwts() {
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path exportPem() {
        try {
            Path file = Files.createTempDirectory("ga-jwt").resolve("public.pem");
            String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(KEYS.getPublic().getEncoded());
            Files.writeString(file, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String publicKeyPem() {
        return PEM.toString();
    }

    public static String token(String tenant, String subject) {
        return sign(KEYS, Map.of("tenant_id", tenant), subject, ISSUER, AUDIENCE, Duration.ofMinutes(10));
    }

    /** 변형 토큰(인증 실패 시험): 다른 키·발급자·대상·만료·클레임. */
    public static String sign(boolean otherKey, Map<String, Object> claims, String subject, String issuer, String audience, Duration ttl) {
        return sign(otherKey ? OTHER_KEYS : KEYS, claims, subject, issuer, audience, ttl);
    }

    private static String sign(KeyPair keys, Map<String, Object> claims, String subject, String issuer, String audience, Duration ttl) {
        try {
            JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().subject(subject).issuer(issuer).audience(audience)
                    .issueTime(Date.from(Instant.now())).expirationTime(Date.from(Instant.now().plus(ttl)));
            claims.forEach(b::claim);
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), b.build());
            jwt.sign(new RSASSASigner((RSAPrivateKey) keys.getPrivate()));
            return jwt.serialize();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
