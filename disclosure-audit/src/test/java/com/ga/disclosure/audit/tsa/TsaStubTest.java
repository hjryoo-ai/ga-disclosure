package com.ga.disclosure.audit.tsa;

import com.ga.disclosure.audit.tsa.http.HttpTimestampAuthority;
import com.ga.disclosure.audit.tsa.stub.LocalStubTsa;
import com.ga.platform.canonical.Sha256;
import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.tsp.TimeStampReq;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G4(TSA): 스텁 토큰이 신뢰 앵커로 VALID, 루트·nonce·인증서(다른 키)·서명 바이트 변조 각각 실패, 신뢰 앵커 없으면 UNTRUSTED,
 * EKU critical 아님·genTime에 인증서 만료는 INVALID, 실 TSA형 체인(루트 CA → TSA 인증서)은 VALID, HTTP 어댑터·데모 키 파일.
 */
class TsaStubTest {

    static final Instant NOW = Instant.parse("2026-10-03T15:00:05Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final long NONCE = 0x1234_5678_9abc_def0L;
    static final byte[] ROOT = HexFormat.of().parseHex(Sha256.of("ga-disclosure/anchor/test-root".getBytes()));

    final LocalStubTsa stub = LocalStubTsa.ephemeral(CLOCK);

    @Test
    void stubTokenIsValidAgainstItsTrustAnchor() throws Exception {
        StampResponse response = new TimestampClient(stub, () -> NONCE, stub.trustAnchors()).stamp(ROOT);

        TimestampToken token = response.token();
        assertThat(token.genTime()).isEqualTo(NOW);
        assertThat(token.policyOid()).isEqualTo(LocalStubTsa.POLICY_OID);
        assertThat(token.imprintHex()).isEqualTo(HexFormat.of().formatHex(ROOT));
        assertThat(token.nonceHex()).isEqualTo("123456789abcdef0");
        assertThat(token.serialHex()).matches("^[1-9a-f][0-9a-f]*$");
        assertThat(token.signerCertSha256()).isEqualTo(Sha256.of(stub.certificate().getEncoded()));
        assertThat(new TimestampVerifier(stub.trustAnchors()).verify(response.tokenDer(), ROOT))
                .isEqualTo(new TimestampVerification.Valid(token));
        assertThat(TimestampVerifier.parse(response.tokenDer())).isEqualTo(token);
    }

    @Test
    void anotherRootIsAnImprintMismatch() {
        byte[] token = stamp(stub);
        byte[] otherRoot = ROOT.clone();
        otherRoot[31] ^= 1;

        assertThat(new TimestampVerifier(stub.trustAnchors()).verify(token, otherRoot))
                .isInstanceOfSatisfying(TimestampVerification.Invalid.class, i -> assertThat(i.reason()).isEqualTo("IMPRINT_MISMATCH"));
    }

    @Test
    void aReplyForAnotherNonceIsRejectedAtAcceptance() {
        TimestampAuthorityPort replaysOtherNonce = request -> {
            TimeStampReq original = TimeStampReq.getInstance(request);
            TimeStampReq swapped = new TimeStampReq(original.getMessageImprint(), null, new ASN1Integer(NONCE + 1), original.getCertReq(), null);
            try {
                return stub.exchange(swapped.getEncoded(ASN1Encoding.DER));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        };

        assertThatThrownBy(() -> new TimestampClient(replaysOtherNonce, () -> NONCE, stub.trustAnchors()).stamp(ROOT))
                .isInstanceOfSatisfying(TimestampFailure.class, f -> {
                    assertThat(f.kind()).isEqualTo(TimestampFailure.Kind.REJECTED);
                    assertThat(f.reason()).isEqualTo("NONCE_MISMATCH");
                });
    }

    @Test
    void aTokenFromAnotherKeyIsUntrusted() {
        LocalStubTsa other = LocalStubTsa.ephemeral(CLOCK);
        byte[] token = stamp(other);

        assertThat(new TimestampVerifier(stub.trustAnchors()).verify(token, ROOT))
                .isInstanceOfSatisfying(TimestampVerification.Untrusted.class, u -> assertThat(u.reason()).isEqualTo("CHAIN_UNTRUSTED"));
        assertThatThrownBy(() -> new TimestampClient(other, () -> NONCE, stub.trustAnchors()).stamp(ROOT))
                .isInstanceOfSatisfying(TimestampFailure.class, f -> assertThat(f.reason()).isEqualTo("CHAIN_UNTRUSTED"));
    }

    @Test
    void aTrustedCertificateOverAnotherKeysSignatureIsInvalid() throws Exception {
        KeyPair impostor = p256();
        LocalStubTsa lying = new LocalStubTsa(impostor.getPrivate(), stub.certificate(), CLOCK);

        assertThat(new TimestampVerifier(stub.trustAnchors()).verify(stamp(lying), ROOT))
                .isInstanceOfSatisfying(TimestampVerification.Invalid.class, i -> assertThat(i.reason()).isEqualTo("SIGNATURE_INVALID"));
    }

    @Test
    void aFlippedSignatureByteIsInvalid() {
        byte[] token = stamp(stub);
        token[token.length - 3] ^= 0x01;

        assertThat(new TimestampVerifier(stub.trustAnchors()).verify(token, ROOT)).isInstanceOf(TimestampVerification.Invalid.class);
    }

    @Test
    void withoutTrustAnchorsAValidSignatureIsStillUntrusted() {
        assertThat(new TimestampVerifier(TrustAnchors.none()).verify(stamp(stub), ROOT))
                .isInstanceOfSatisfying(TimestampVerification.Untrusted.class, u -> assertThat(u.reason()).isEqualTo("NO_TRUST_ANCHORS"));
        assertThatThrownBy(() -> new TimestampClient(stub, () -> NONCE, TrustAnchors.none()).stamp(ROOT))
                .isInstanceOfSatisfying(TimestampFailure.class, f -> assertThat(f.reason()).isEqualTo("NO_TRUST_ANCHORS"));
    }

    @Test
    void aSignerWhoseTimeStampingUsageIsNotCriticalIsInvalid() throws Exception {
        KeyPair pair = p256();
        BigInteger serial = BigInteger.valueOf(4242);
        X509Certificate good = certificate(pair, serial, true);
        X509Certificate lax = certificate(pair, serial, false);
        byte[] token = stamp(new LocalStubTsa(pair.getPrivate(), good, CLOCK));
        CMSSignedData swapped = CMSSignedData.replaceCertificatesAndCRLs(new CMSSignedData(token), new JcaCertStore(List.of(lax)), null, null);

        assertThat(new TimestampVerifier(TrustAnchors.of(lax)).verify(swapped.getEncoded(), ROOT))
                .isInstanceOfSatisfying(TimestampVerification.Invalid.class, i -> assertThat(i.reason()).isEqualTo("SIGNER_EKU"));
    }

    @Test
    void aCertificateExpiredAtGenTimeIsInvalid() throws Exception {
        KeyPair pair = p256();
        X509Certificate oneDay = certificate(pair, BigInteger.TEN, true);
        LocalStubTsa onTime = new LocalStubTsa(pair.getPrivate(), oneDay, CLOCK);
        LocalStubTsa late = new LocalStubTsa(pair.getPrivate(), oneDay, Clock.offset(CLOCK, Duration.ofDays(2)));

        assertThat(new TimestampVerifier(TrustAnchors.of(oneDay)).verify(stamp(onTime), ROOT)).isInstanceOf(TimestampVerification.Valid.class);
        assertThat(new TimestampVerifier(TrustAnchors.of(oneDay)).verify(stamp(late), ROOT))
                .isInstanceOfSatisfying(TimestampVerification.Invalid.class, i -> assertThat(i.reason()).isEqualTo("SIGNATURE_INVALID"));
    }

    @Test
    void aRealTsaShapedChainVerifiesToTheRootCa() throws Exception {
        KeyPair caKey = p256();
        X500Name caName = new X500Name("CN=ga-disclosure test root CA");
        JcaX509v3CertificateBuilder ca = new JcaX509v3CertificateBuilder(caName, BigInteger.ONE, Date.from(NOW.minusSeconds(3600)),
                Date.from(NOW.plusSeconds(86_400)), caName, caKey.getPublic());
        ca.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        ca.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign));
        X509Certificate caCert = new JcaX509CertificateConverter().getCertificate(ca.build(new JcaContentSignerBuilder("SHA256withECDSA").build(caKey.getPrivate())));
        KeyPair tsaKey = p256();
        JcaX509v3CertificateBuilder leaf = new JcaX509v3CertificateBuilder(caName, BigInteger.TWO, Date.from(NOW.minusSeconds(3600)),
                Date.from(NOW.plusSeconds(86_400)), new X500Name("CN=ga-disclosure test TSA"), tsaKey.getPublic());
        leaf.addExtension(Extension.extendedKeyUsage, true, new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
        leaf.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        X509Certificate tsaCert = new JcaX509CertificateConverter().getCertificate(leaf.build(new JcaContentSignerBuilder("SHA256withECDSA").build(caKey.getPrivate())));
        byte[] token = stamp(new LocalStubTsa(tsaKey.getPrivate(), tsaCert, CLOCK));

        assertThat(new TimestampVerifier(TrustAnchors.of(caCert)).verify(token, ROOT)).isInstanceOf(TimestampVerification.Valid.class);
        assertThat(new TimestampVerifier(stub.trustAnchors()).verify(token, ROOT)).isInstanceOf(TimestampVerification.Untrusted.class);
    }

    @Test
    void unreadableRepliesAndOutagesAreFailuresNotTokens() {
        assertThatThrownBy(() -> new TimestampClient(request -> new byte[]{1, 2, 3}, () -> NONCE, stub.trustAnchors()).stamp(ROOT))
                .isInstanceOfSatisfying(TimestampFailure.class, f -> assertThat(f.reason()).isEqualTo("MALFORMED_REPLY"));
        TimestampAuthorityPort down = request -> {
            throw new TimestampFailure(TimestampFailure.Kind.UNAVAILABLE, "TRANSPORT");
        };
        assertThatThrownBy(() -> new TimestampClient(down, () -> NONCE, stub.trustAnchors()).stamp(ROOT))
                .isInstanceOfSatisfying(TimestampFailure.class, f -> assertThat(f.kind()).isEqualTo(TimestampFailure.Kind.UNAVAILABLE));
        assertThatThrownBy(() -> new TimestampClient(stub, () -> NONCE, stub.trustAnchors()).stamp(new byte[31]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void httpAdapterPostsTheQueryAndMapsErrorsToUnavailable() throws Exception {
        AtomicReference<String> contentType = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tsa", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] reply = stub.exchange(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().add("Content-Type", "application/timestamp-reply");
            exchange.sendResponseHeaders(200, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        server.createContext("/down", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            StampResponse response = new TimestampClient(new HttpTimestampAuthority(URI.create(base + "/tsa"), Duration.ofSeconds(5)),
                    () -> NONCE, stub.trustAnchors()).stamp(ROOT);
            assertThat(response.token().nonceHex()).isEqualTo("123456789abcdef0");
            assertThat(contentType.get()).isEqualTo("application/timestamp-query");

            assertThatThrownBy(() -> new HttpTimestampAuthority(URI.create(base + "/down"), Duration.ofSeconds(5)).exchange(new byte[]{0}))
                    .isInstanceOfSatisfying(TimestampFailure.class, f -> {
                        assertThat(f.kind()).isEqualTo(TimestampFailure.Kind.UNAVAILABLE);
                        assertThat(f.reason()).isEqualTo("HTTP_503");
                    });
        } finally {
            server.stop(0);
        }
    }

    @Test
    void demoKeyIsCreatedOnceOwnerOnlyAndReused(@TempDir Path dir) throws Exception {
        Path keyStore = dir.resolve("ga-home/tsa-stub.p12");
        Path trust = dir.resolve("ga-home/tsa-trust.pem");

        LocalStubTsa first = LocalStubTsa.loadOrCreate(keyStore, trust, CLOCK);
        LocalStubTsa again = LocalStubTsa.loadOrCreate(keyStore, trust, Clock.offset(CLOCK, Duration.ofDays(400)));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(keyStore))).isEqualTo("rw-------");
        assertThat(again.certificate()).isEqualTo(first.certificate());
        assertThat(TrustAnchors.fromPem(Files.readAllBytes(trust)).certificates()).containsExactly(first.certificate());
        byte[] later = new TimestampClient(again, () -> NONCE, TrustAnchors.fromPem(Files.readAllBytes(trust))).stamp(ROOT).tokenDer();
        assertThat(TimestampVerifier.parse(later).genTime()).isEqualTo(NOW.plus(Duration.ofDays(400)));

        Files.delete(keyStore);                                            // 키를 다시 만들면 신뢰 앵커 파일도 새 인증서로
        LocalStubTsa renewed = LocalStubTsa.loadOrCreate(keyStore, trust, CLOCK);
        assertThat(renewed.certificate()).isNotEqualTo(first.certificate());
        assertThat(TrustAnchors.fromPem(Files.readAllBytes(trust)).certificates()).containsExactly(renewed.certificate());

        Files.setPosixFilePermissions(keyStore, PosixFilePermissions.fromString("rw-r--r--"));
        assertThatThrownBy(() -> LocalStubTsa.loadOrCreate(keyStore, trust, CLOCK)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chmod 600");
    }

    @Test
    void trustBundlesMustHoldCertificates() {
        assertThatThrownBy(() -> TrustAnchors.fromPem("not a pem".getBytes())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrustAnchors.fromPem(new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThat(TrustAnchors.fromPem(stub.trustAnchors().toPem().getBytes()).certificates()).containsExactly(stub.certificate());
    }

    private static byte[] stamp(LocalStubTsa tsa) {
        // 수락 검사를 건너뛴 원시 토큰(검증기를 따로 시험하기 위해): 신뢰 앵커를 그 TSA 자신으로 두면 서명이 틀린 토큰은 수락되지 않으므로
        // 요청을 직접 만들어 응답에서 토큰만 꺼낸다.
        try {
            TimeStampReq request = new TimeStampReq(new org.bouncycastle.asn1.tsp.MessageImprint(
                    new org.bouncycastle.asn1.x509.AlgorithmIdentifier(org.bouncycastle.asn1.nist.NISTObjectIdentifiers.id_sha256), ROOT),
                    null, new ASN1Integer(NONCE), org.bouncycastle.asn1.ASN1Boolean.TRUE, null);
            return new org.bouncycastle.tsp.TimeStampResponse(tsa.exchange(request.getEncoded(ASN1Encoding.DER))).getTimeStampToken().getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair p256() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static X509Certificate certificate(KeyPair pair, BigInteger serial, boolean ekuCritical) throws Exception {
        X500Name name = new X500Name("CN=ga-disclosure test TSA (EKU)");
        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, serial, Date.from(NOW.minusSeconds(3600)),
                Date.from(NOW.plusSeconds(86_400)), name, pair.getPublic());
        b.addExtension(Extension.extendedKeyUsage, ekuCritical, new ExtendedKeyUsage(KeyPurposeId.id_kp_timeStamping));
        return new JcaX509CertificateConverter().getCertificate(b.build(new JcaContentSignerBuilder("SHA256withECDSA").build(pair.getPrivate())));
    }
}
