package com.ga.disclosure.audit.tsa.stub;

import com.ga.disclosure.audit.tsa.TimestampAuthorityPort;
import com.ga.disclosure.audit.tsa.TimestampFailure;
import com.ga.disclosure.audit.tsa.TrustAnchors;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.Attribute;
import org.bouncycastle.asn1.cms.AttributeTable;
import org.bouncycastle.asn1.cms.CMSAttributes;
import org.bouncycastle.asn1.cms.Time;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampRequest;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 로컬 스텁 TSA(5 계획 §3, 4 수용심사 결정 3): ECDSA P-256 자체 서명 인증서(EKU {@code id-kp-timeStamping} critical, KeyUsage
 * digitalSignature critical)로 BC {@code TimeStampResponseGenerator}가 응답을 만든다. genTime은 주입된 {@link Clock}이다.
 * 신뢰 앵커는 이 인증서 하나({@link #trustAnchors()}).
 *
 * <p>키 수명: 테스트는 {@link #ephemeral(Clock)}(실행마다 생성, 유효 1일). 데모는 {@link #loadOrCreate(Path, Path, Clock)} — 저장소 밖
 * 파일에 처음 한 번 만들고 재사용한다(CLI는 명령마다 새 JVM이라 매번 만들면 신뢰 번들이 누적된다, 승인 Q11). PKCS#12 비밀번호는 비밀이
 * 아니며 보호는 로컬 KEK 파일과 같은 소유자 전용 권한(600)이다. BigInteger는 BC 생성 API(인증서·토큰 일련번호)의 경계에서만 쓴다.
 */
public final class LocalStubTsa implements TimestampAuthorityPort {

    /** 스텁 정책 OID: UUID 기반 자체 할당 arc(ITU-T X.667 {@code 2.25}). */
    public static final String POLICY_OID = "2.25.55583785870018585401904131584655276190";
    static final String SUBJECT = "CN=ga-disclosure local stub TSA,O=ga-disclosure (not a real TSA)";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";
    private static final char[] P12_PASSWORD = "ga-disclosure-stub-not-a-secret".toCharArray();
    private static final String P12_ALIAS = "tsa";
    private static final Duration EPHEMERAL_VALIDITY = Duration.ofDays(1);
    private static final Duration DEMO_VALIDITY = Duration.ofDays(366L * 30);
    private static final Duration BACKDATE = Duration.ofHours(1);

    private final PrivateKey key;
    private final X509Certificate certificate;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public LocalStubTsa(PrivateKey key, X509Certificate certificate, Clock clock) {
        this.key = Objects.requireNonNull(key, "key");
        this.certificate = Objects.requireNonNull(certificate, "certificate");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 실행마다 새 키·인증서(유효: 지금 −1시간 ~ +1일). */
    public static LocalStubTsa ephemeral(Clock clock) {
        KeyPair pair = newKeyPair();
        return new LocalStubTsa(pair.getPrivate(), selfSigned(pair, clock.instant(), EPHEMERAL_VALIDITY), clock);
    }

    /**
     * 데모 키 저장소 바이트(PKCS#12 — 새 키·인증서, 유효 30년). {@link #loadOrCreate}가 없을 때 쓰는 것과 같다. Phase 8: kind 데모는 이 바이트를 비밀
     * {@code demo/tsa-stub.p12}로 두고({@code secrets init --demo yes}) 모든 파드가 같은 키를 쓴다(복제본·앵커 CronJob — 파드마다 만들면 신뢰 앵커가 갈린다).
     */
    public static byte[] newDemoKeyStore(Clock clock) {
        try {
            KeyPair pair = newKeyPair();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            ks.setKeyEntry(P12_ALIAS, pair.getPrivate(), P12_PASSWORD, new Certificate[]{selfSigned(pair, clock.instant(), DEMO_VALIDITY)});
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            ks.store(out, P12_PASSWORD);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create the stub TSA key store", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("cannot create the stub TSA key store", e);
        }
    }

    /**
     * 데모: {@code keyStore}(PKCS#12)가 있으면 읽고(소유자 전용 권한 확인), 없으면 만든다. 신뢰 앵커 인증서는 {@code trustPem}에
     * 내보낸다(매번 덮어쓴다 — 키를 다시 만들면 신뢰 앵커도 따라간다).
     */
    public static LocalStubTsa loadOrCreate(Path keyStore, Path trustPem, Clock clock) {
        try {
            if (!Files.exists(keyStore)) {
                byte[] created = newDemoKeyStore(clock);
                try (OutputStream out = createOwnerOnly(keyStore)) {
                    out.write(created);
                }
            }
            requireOwnerOnly(keyStore);
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keyStore)) {
                ks.load(in, P12_PASSWORD);
            }
            LocalStubTsa tsa = new LocalStubTsa((PrivateKey) ks.getKey(P12_ALIAS, P12_PASSWORD), (X509Certificate) ks.getCertificate(P12_ALIAS), clock);
            // 매번 쓴다: 키를 다시 만들었는데 옛 인증서가 남으면 검증이 UNTRUSTED가 된다(내용은 키가 같으면 같다)
            Files.createDirectories(trustPem.toAbsolutePath().getParent());
            Files.writeString(trustPem, tsa.trustAnchors().toPem(), StandardCharsets.US_ASCII);
            return tsa;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot load or create the stub TSA key store", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("stub TSA key store is unreadable", e);
        }
    }

    public X509Certificate certificate() {
        return certificate;
    }

    public TrustAnchors trustAnchors() {
        return TrustAnchors.of(certificate);
    }

    @Override
    public byte[] exchange(byte[] timeStampRequestDer) {
        try {
            Date now = Date.from(clock.instant());
            // CMS signingTime도 주입된 시계로 — 기본 생성기는 벽시계를 읽는다(genTime과 어긋나면 검증이 인증서 유효기간 밖으로 본다)
            AttributeTable signingTime = new AttributeTable(new Attribute(CMSAttributes.signingTime, new DERSet(new Time(now))));
            TimeStampTokenGenerator tokens = new TimeStampTokenGenerator(
                    new JcaSimpleSignerInfoGeneratorBuilder().setSignedAttributeGenerator(signingTime).build(SIGNATURE_ALGORITHM, key, certificate),
                    new JcaDigestCalculatorProviderBuilder().build().get(new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256)),
                    new ASN1ObjectIdentifier(POLICY_OID));
            tokens.addCertificates(new JcaCertStore(List.of(certificate)));
            TimeStampResponseGenerator responses = new TimeStampResponseGenerator(tokens, TSPAlgorithms.ALLOWED);
            return responses.generate(new TimeStampRequest(timeStampRequestDer), new BigInteger(64, random).add(BigInteger.ONE),
                    now).getEncoded();
        } catch (IOException e) {
            throw new TimestampFailure(TimestampFailure.Kind.REJECTED, "MALFORMED_REQUEST", e);
        } catch (TSPException | OperatorCreationException | GeneralSecurityException e) {
            throw new TimestampFailure(TimestampFailure.Kind.UNAVAILABLE, "STUB_SIGNING_FAILED", e);
        }
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 key generation is unavailable", e);
        }
    }

    private static X509Certificate selfSigned(KeyPair pair, Instant now, Duration validity) {
        try {
            X500Name subject = new X500Name(SUBJECT);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject, new BigInteger(64, new SecureRandom()).add(BigInteger.ONE),
                    Date.from(now.minus(BACKDATE)), Date.from(now.plus(validity)), subject, pair.getPublic());
            builder.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            return new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(pair.getPrivate())));
        } catch (CertIOException | OperatorCreationException | GeneralSecurityException e) {
            throw new IllegalStateException("stub TSA certificate cannot be built", e);
        }
    }

    /** 만들기·열기를 한 번에(O_CREAT|O_EXCL, 소유자 전용) — 만든 뒤 경로로 다시 열면 그 사이 심볼릭 링크로 바꿔치기된 대상에 키를 쓸 수 있다(TOCTOU). */
    private static OutputStream createOwnerOnly(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        java.util.Set<java.nio.file.OpenOption> options = java.util.Set.of(java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        return java.nio.channels.Channels.newOutputStream(file.getFileSystem().supportedFileAttributeViews().contains("posix")
                ? Files.newByteChannel(file, options, PosixFilePermissions.asFileAttribute(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)))
                : Files.newByteChannel(file, options));
    }

    private static void requireOwnerOnly(Path file) throws IOException {
        if (!file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        for (PosixFilePermission p : Files.getPosixFilePermissions(file)) {
            if (p != PosixFilePermission.OWNER_READ && p != PosixFilePermission.OWNER_WRITE) {
                throw new IllegalStateException("stub TSA key store " + file + " must be readable by its owner only (chmod 600)");
            }
        }
    }
}
