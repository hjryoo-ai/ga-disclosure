package com.ga.disclosure.app.demo;

import com.ga.platform.core.tenant.TenantId;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;

/**
 * 데모 OIDC 발급자(6A 계획 §10, 데모 프로파일만): 로컬 RSA 키로 RS256 JWT를 만든다. 클레임은 {@code sub}·{@code tenant_id}·{@code iss}
 * ({@value #ISSUER})·{@code aud}({@value #AUDIENCE})·{@code exp}뿐 — 역할 클레임은 없다(역할은 {@code identity_link}).
 * <ul>
 *   <li>서명 키 {@code ga.demo.oidc-key-file}(기본 {@code ~/.ga-disclosure/demo-oidc.key}, 저장소 밖): 첫 사용에 만든다(소유자 전용 600), 권한이 넓으면
 *       거부. PKCS#8 PEM이다 — PKCS#12에 개인키를 넣으려면 인증서가 필요하고, 인증서를 만드는 BouncyCastle은 {@code ..audit.tsa..}에만 허용된다.</li>
 *   <li>공개키 {@code ga.demo.oidc-public-pem}(기본 {@code build/demo/demo-oidc.pem}, gitignore): 쓸 때마다 다시 내보낸다. 데모 웹 앱의
 *       {@code ga.api.jwt.public-key-location}이 이 파일이다({@code application-demo.yaml}).</li>
 * </ul>
 */
@Component
@Profile("demo")
public class DemoOidcIssuer {

    public static final String ISSUER = "ga-demo";
    public static final String AUDIENCE = "ga-disclosure";
    static final Duration MAX_TTL = Duration.ofHours(24);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private final Path keyFile;
    private final Path publicPem;
    private final Clock clock;

    public DemoOidcIssuer(DemoClockConfiguration.DemoProperties properties, Clock clock) {
        this.keyFile = properties.oidcKeyFile();
        this.publicPem = properties.oidcPublicPem();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** JWT 한 개(공개키 PEM을 함께 내보낸다). */
    public String token(TenantId tenant, String subject, Duration ttl) {
        Objects.requireNonNull(tenant, "tenant");
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException("ttl must be within (0, " + MAX_TTL + "]");
        }
        KeyPair keys = keys();
        String header = encode(JSON.createObjectNode().put("alg", "RS256").put("typ", "JWT").toString());
        String claims = encode(JSON.createObjectNode().put("sub", subject).put("tenant_id", tenant.value()).put("iss", ISSUER).put("aud", AUDIENCE)
                .put("exp", clock.instant().plus(ttl).getEpochSecond()).toString());
        String signingInput = header + "." + claims;
        try {
            Signature rs256 = Signature.getInstance("SHA256withRSA");
            rs256.initSign(keys.getPrivate());
            rs256.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + URL.encodeToString(rs256.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("demo token cannot be signed", e);
        }
    }

    private static String encode(String json) {
        return URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** 키를 읽거나(없으면 만든다) 공개키 PEM을 내보낸다. */
    KeyPair keys() {
        try {
            if (!Files.exists(keyFile)) {
                create();
            }
            if (keyFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                for (PosixFilePermission p : Files.getPosixFilePermissions(keyFile)) {
                    if (p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")) {
                        throw new IllegalStateException("demo OIDC key " + keyFile + " must be readable by its owner only (chmod 600)");
                    }
                }
            }
            String base64 = Files.readString(keyFile).replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = rsa.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
                throw new IllegalStateException("demo OIDC key " + keyFile + " is not an RSA CRT private key");
            }
            PublicKey publicKey = rsa.generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
            if (publicPem.getParent() != null) {
                Files.createDirectories(publicPem.getParent());
            }
            Files.writeString(publicPem, pem("PUBLIC KEY", publicKey.getEncoded()));
            return new KeyPair(publicKey, privateKey);
        } catch (IOException e) {
            throw new UncheckedIOException("demo OIDC key files are not usable", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("demo OIDC key " + keyFile + " is not a PKCS#8 RSA key", e);
        }
    }

    private void create() throws IOException, GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        if (keyFile.getParent() != null) {
            Files.createDirectories(keyFile.getParent());
        }
        try {
            if (keyFile.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
            } else {
                Files.createFile(keyFile);
            }
        } catch (FileAlreadyExistsException raced) {
            return;
        }
        Files.writeString(keyFile, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
