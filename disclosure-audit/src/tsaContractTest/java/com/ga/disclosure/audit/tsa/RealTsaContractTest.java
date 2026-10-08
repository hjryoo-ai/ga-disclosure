package com.ga.disclosure.audit.tsa;

import com.ga.disclosure.audit.tsa.http.HttpTimestampAuthority;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실 TSA 계약(opt-in 태스크 {@code tsaContractTest} — {@code check} 밖): 운영 경로 그대로 외부 TSA에 SHA-256 imprint로 토큰을 받는다. 수락(상태
 * granted·nonce 일치·imprint·서명·신뢰 앵커)이 통과하고, 토큰 시각이 지금 근처이며, 같은 토큰은 다른 imprint에 대해 무효이고, 신뢰 앵커가 없으면
 * 수락하지 않는다. URL·신뢰 앵커가 없으면 스킵이 아니라 실패다.
 */
class RealTsaContractTest {

    static String required(String property) {
        String value = System.getProperty(property, "");
        assertThat(value).as(property + " (set GA_TSA_URL and GA_TSA_TRUST_PEM — this task never skips)").isNotBlank();
        return value;
    }

    static byte[] sha256(String text) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aRealTsaTokenIsAcceptedVerifiedAndBoundToItsImprint() throws Exception {
        URI url = URI.create(required("ga.tsa.contract.url"));
        TrustAnchors trust = TrustAnchors.fromPem(Files.readAllBytes(Path.of(required("ga.tsa.contract.trust-pem"))));
        HttpTimestampAuthority authority = new HttpTimestampAuthority(url, Duration.ofSeconds(30));
        byte[] digest = sha256("ga-disclosure tsa contract " + Instant.now());

        StampResponse stamped = new TimestampClient(authority, NonceSource.secure(), trust).stamp(digest);
        assertThat(stamped.tokenDer()).isNotEmpty();
        assertThat(Duration.between(stamped.token().genTime(), Instant.now()).abs()).isLessThan(Duration.ofMinutes(10));

        TimestampVerifier verifier = new TimestampVerifier(trust);
        assertThat(verifier.verify(stamped.tokenDer(), digest)).isInstanceOf(TimestampVerification.Valid.class);
        assertThat(verifier.verify(stamped.tokenDer(), sha256("another imprint"))).isNotInstanceOf(TimestampVerification.Valid.class);
        assertThatThrownBy(() -> new TimestampClient(authority, NonceSource.secure(), TrustAnchors.none()).stamp(sha256("untrusted " + Instant.now())))
                .isInstanceOf(TimestampFailure.class);
    }
}
