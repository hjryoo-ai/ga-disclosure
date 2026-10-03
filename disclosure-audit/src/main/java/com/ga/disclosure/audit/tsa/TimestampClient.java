package com.ga.disclosure.audit.tsa;

import org.bouncycastle.asn1.ASN1Boolean;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.tsp.MessageImprint;
import org.bouncycastle.asn1.tsp.TSTInfo;
import org.bouncycastle.asn1.tsp.TimeStampReq;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.tsp.TSPException;
import org.bouncycastle.tsp.TimeStampResponse;
import org.bouncycastle.tsp.TimeStampToken;

import java.io.IOException;
import java.util.Objects;

/**
 * 일일 루트에 토큰을 받는다(5 계획 §3): 요청은 imprint = {SHA-256, 루트}(루트가 이미 SHA-256 출력이라 다시 해시하지 않는다), nonce,
 * {@code certReq=true}, 정책 OID 없음(TSA 기본). 응답 수락 = 상태 granted(·withMods), nonce 일치, 그리고 {@link TimestampVerifier}가
 * VALID(imprint·서명·신뢰 앵커). 하나라도 어긋나면 {@link TimestampFailure}이고 영수증을 쓰지 않는다.
 */
public final class TimestampClient {

    private static final int GRANTED = 0;
    private static final int GRANTED_WITH_MODS = 1;

    private final TimestampAuthorityPort port;
    private final NonceSource nonces;
    private final TimestampVerifier verifier;

    public TimestampClient(TimestampAuthorityPort port, NonceSource nonces, TrustAnchors trust) {
        this.port = Objects.requireNonNull(port, "port");
        this.nonces = Objects.requireNonNull(nonces, "nonces");
        this.verifier = new TimestampVerifier(trust);
    }

    public StampResponse stamp(byte[] digest) {
        if (Objects.requireNonNull(digest, "digest").length != 32) {
            throw new IllegalArgumentException("imprint is a 32-byte SHA-256 value");
        }
        long nonce = nonces.next();
        byte[] request;
        try {
            request = new TimeStampReq(new MessageImprint(new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256), digest.clone()), null,
                    new ASN1Integer(nonce), ASN1Boolean.TRUE, null).getEncoded(ASN1Encoding.DER);
        } catch (IOException e) {
            throw new IllegalStateException("timestamp request cannot be encoded", e);
        }
        byte[] reply = port.exchange(request);
        if (reply == null) {
            throw new TimestampFailure(TimestampFailure.Kind.UNAVAILABLE, "NO_REPLY");
        }
        TimeStampResponse response;
        try {
            response = new TimeStampResponse(reply);
        } catch (TSPException | IOException | RuntimeException e) {
            throw new TimestampFailure(TimestampFailure.Kind.REJECTED, "MALFORMED_REPLY", e);
        }
        if (response.getStatus() != GRANTED && response.getStatus() != GRANTED_WITH_MODS) {
            throw new TimestampFailure(TimestampFailure.Kind.REJECTED, "STATUS_" + response.getStatus());
        }
        TimeStampToken token = response.getTimeStampToken();
        if (token == null) {
            throw new TimestampFailure(TimestampFailure.Kind.REJECTED, "NO_TOKEN");
        }
        TSTInfo info = token.getTimeStampInfo().toASN1Structure();
        if (info.getNonce() == null || !info.getNonce().hasValue(nonce)) {
            throw new TimestampFailure(TimestampFailure.Kind.REJECTED, "NONCE_MISMATCH");
        }
        byte[] der;
        try {
            der = token.getEncoded();
        } catch (IOException e) {
            throw new TimestampFailure(TimestampFailure.Kind.REJECTED, "MALFORMED_REPLY", e);
        }
        return switch (verifier.verify(der, digest)) {
            case TimestampVerification.Valid v -> new StampResponse(der, v.token());
            case TimestampVerification.Invalid i -> throw new TimestampFailure(TimestampFailure.Kind.REJECTED, i.reason());
            case TimestampVerification.Untrusted u -> throw new TimestampFailure(TimestampFailure.Kind.REJECTED, u.reason());
        };
    }
}
