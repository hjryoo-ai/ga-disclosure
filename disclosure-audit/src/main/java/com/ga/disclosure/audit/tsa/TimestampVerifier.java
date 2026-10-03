package com.ga.disclosure.audit.tsa;

import com.ga.platform.canonical.Sha256;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.tsp.TSTInfo;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerId;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampToken;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * RFC 3161 토큰 검증(5 계획 §3): ① 파싱 ② imprint = 기대값(SHA-256) ③ 서명자 인증서 EKU = {@code id-kp-timeStamping}만, critical
 * ④ CMS 서명·ESSCertID(v2) 결속·genTime 시점 인증서 유효(BC {@code TimeStampToken.validate}) ⑤ 설정된 신뢰 앵커로 PKIX 경로
 * (폐기 확인 끔 — 스텁에는 CRL·OCSP가 없고 실 TSA 폐기 정책은 설계서 §14 #4, 기준 시각 genTime). ①~④ 실패는 INVALID, ⑤ 실패와
 * 신뢰 앵커 없음은 UNTRUSTED. 신뢰 판정에는 JCA 기본 제공자만 쓴다(BC 제공자를 전역 등록하지 않는다).
 */
public final class TimestampVerifier {

    private final TrustAnchors trust;

    public TimestampVerifier(TrustAnchors trust) {
        this.trust = Objects.requireNonNull(trust, "trust");
    }

    public TimestampVerification verify(byte[] tokenDer, byte[] expectedImprint) {
        Objects.requireNonNull(tokenDer, "tokenDer");
        Objects.requireNonNull(expectedImprint, "expectedImprint");
        TimeStampToken bc;
        try {
            bc = new TimeStampToken(new CMSSignedData(tokenDer));
        } catch (CMSException | TSPException | IOException | RuntimeException e) {
            return new TimestampVerification.Invalid("TOKEN_MALFORMED", null);
        }
        X509CertificateHolder signer = signerCertificate(bc);
        if (signer == null) {
            return new TimestampVerification.Invalid("SIGNER_CERT_MISSING", null);
        }
        TSTInfo info = bc.getTimeStampInfo().toASN1Structure();
        TimestampToken token = describe(bc, info, signer);
        if (!NISTObjectIdentifiers.id_sha256.equals(info.getMessageImprint().getHashAlgorithm().getAlgorithm())
                || !MessageDigest.isEqual(info.getMessageImprint().getHashedMessage(), expectedImprint)) {
            return new TimestampVerification.Invalid("IMPRINT_MISMATCH", token);
        }
        if (!timeStampingOnlyAndCritical(signer)) {
            return new TimestampVerification.Invalid("SIGNER_EKU", token);
        }
        try {
            bc.validate(new JcaSimpleSignerInfoVerifierBuilder().build(signer));
        } catch (TSPException | OperatorCreationException | GeneralSecurityException | RuntimeException e) {
            return new TimestampVerification.Invalid("SIGNATURE_INVALID", token);
        }
        if (trust.isEmpty()) {
            return new TimestampVerification.Untrusted("NO_TRUST_ANCHORS", token);
        }
        if (!chainsToTrustAnchor(bc, signer, token)) {
            return new TimestampVerification.Untrusted("CHAIN_UNTRUSTED", token);
        }
        return new TimestampVerification.Valid(token);
    }

    /** 검증 없이 보고 필드만 읽는다(영수증 내보내기·보고서). 형식이 틀리면 {@link IllegalArgumentException}. */
    public static TimestampToken parse(byte[] tokenDer) {
        try {
            TimeStampToken bc = new TimeStampToken(new CMSSignedData(tokenDer));
            X509CertificateHolder signer = signerCertificate(bc);
            if (signer == null) {
                throw new IllegalArgumentException("timestamp token carries no signer certificate");
            }
            return describe(bc, bc.getTimeStampInfo().toASN1Structure(), signer);
        } catch (CMSException | TSPException | IOException e) {
            throw new IllegalArgumentException("not an RFC 3161 timestamp token", e);
        }
    }

    private boolean chainsToTrustAnchor(TimeStampToken bc, X509CertificateHolder signer, TimestampToken token) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> carried = new ArrayList<>();
            for (X509CertificateHolder h : bc.getCertificates().getMatches(null)) {
                carried.add(x509(factory, h));
            }
            X509CertSelector target = new X509CertSelector();
            target.setCertificate(x509(factory, signer));
            PKIXBuilderParameters params = new PKIXBuilderParameters(trust.asPkix(), target);
            params.setRevocationEnabled(false);
            params.setDate(Date.from(token.genTime()));
            params.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(carried)));
            CertPathBuilder.getInstance("PKIX").build(params);
            return true;
        } catch (GeneralSecurityException | IOException e) {
            return false;
        }
    }

    private static X509CertificateHolder signerCertificate(TimeStampToken bc) {
        SignerId sid = bc.getSID();
        for (X509CertificateHolder h : bc.getCertificates().getMatches(null)) {
            if (sid.match(h)) {
                return h;
            }
        }
        return null;
    }

    private static boolean timeStampingOnlyAndCritical(X509CertificateHolder signer) {
        Extension eku = signer.getExtension(Extension.extendedKeyUsage);
        if (eku == null || !eku.isCritical()) {
            return false;
        }
        KeyPurposeId[] usages = ExtendedKeyUsage.getInstance(eku.getParsedValue()).getUsages();
        return usages.length == 1 && KeyPurposeId.id_kp_timeStamping.equals(usages[0]);
    }

    private static TimestampToken describe(TimeStampToken bc, TSTInfo info, X509CertificateHolder signer) {
        try {
            return new TimestampToken(bc.getTimeStampInfo().getGenTime().toInstant(), info.getPolicy().getId(),
                    integerHex(info.getSerialNumber()), HexFormat.of().formatHex(info.getMessageImprint().getHashedMessage()),
                    info.getNonce() == null ? null : integerHex(info.getNonce()), Sha256.of(signer.getEncoded()));
        } catch (IOException e) {
            throw new IllegalArgumentException("timestamp token cannot be re-encoded", e);
        }
    }

    private static X509Certificate x509(CertificateFactory factory, X509CertificateHolder holder)
            throws GeneralSecurityException, IOException {
        return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(holder.getEncoded()));
    }

    /** ASN.1 INTEGER의 내용 옥텟을 소문자 hex로(앞자리 0 제거). 일련번호·nonce를 수로 다루지 않는다. */
    static String integerHex(ASN1Integer value) throws IOException {
        byte[] der = value.getEncoded(ASN1Encoding.DER);
        int length = der[1] & 0xff;
        int offset = length < 0x80 ? 2 : 2 + (length & 0x7f);
        String hex = HexFormat.of().formatHex(der, offset, der.length);
        int start = 0;
        while (start < hex.length() - 1 && hex.charAt(start) == '0') {
            start++;
        }
        return hex.substring(start);
    }
}
