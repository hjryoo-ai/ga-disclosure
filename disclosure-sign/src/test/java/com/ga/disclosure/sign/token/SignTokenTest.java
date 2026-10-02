package com.ga.disclosure.sign.token;

import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.core.testing.SeededCases;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 토큰 형식 {@code {tenant}~{base64url 256비트}}(4 계획 승인 Q4), 저장 해시, 비밀 비노출, 형식 오류는 원인 구분 없는 한 타입(B2). */
class SignTokenTest {

    private static final TenantId T = TenantId.of("T1");

    /** 시드 고정 난수(테스트 전용 TokenSource). */
    static TokenSource seeded(long seed) {
        RandomGenerator r = SeededCases.generator(seed);
        return () -> {
            byte[] b = new byte[32];
            r.nextBytes(b);
            return b;
        };
    }

    @Test
    void formatIsTenantTildeAndFortyThreeBase64UrlChars() {
        SignToken token = SignToken.issue(T, seeded(7));
        assertThat(token.reveal()).matches("T1~[A-Za-z0-9_-]{43}");
        assertThat(token.tenant()).isEqualTo(T);
        assertThat(SignToken.parse(token.reveal())).isEqualTo(token);
        assertThat(SignToken.issue(T, seeded(7)).reveal()).isEqualTo(token.reveal());       // 결정론은 소스에서만 온다
        TokenSource source = seeded(8);
        assertThat(SignToken.issue(T, source).reveal()).isNotEqualTo(SignToken.issue(T, source).reveal());
    }

    @Test
    void storedHashIsSha256OfTheWholeTokenAscii() throws Exception {
        SignToken token = SignToken.issue(T, seeded(9));
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.reveal().getBytes(StandardCharsets.US_ASCII)));
        assertThat(token.hash().hex()).isEqualTo(expected);
        // 같은 비밀이라도 테넌트가 다르면 해시가 다르다
        String secret = token.reveal().substring(3);
        assertThat(SignToken.parse("T2~" + secret).hash()).isNotEqualTo(token.hash());
    }

    @Test
    void toStringNeverCarriesTheSecret() {
        SignToken token = SignToken.issue(T, seeded(10));
        String secret = token.reveal().substring(3);
        assertThat(token.toString()).isEqualTo("SignToken[T1~***]").doesNotContain(secret);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "~AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",              // 테넌트 없음
            "t1~AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",            // 테넌트 형식
            "T1-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",            // 구분자
            "T1~AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",             // 42자
            "T1~AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",           // 44자
            "T1~AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",            // 패딩
            "T1~AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+",            // base64(url 아님)
            "T1~~AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "T1~AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n"})
    void malformedTokensAreOneUndistinguishedRejection(String raw) {
        assertThatThrownBy(() -> SignToken.parse(raw)).isExactlyInstanceOf(SignTokenRejected.class)
                .hasMessage("sign token rejected");
    }

    @Test
    void nullAndShortSourcesFail() {
        assertThatThrownBy(() -> SignToken.parse(null)).isExactlyInstanceOf(SignTokenRejected.class);
        assertThatThrownBy(() -> SignToken.issue(T, () -> new byte[31])).isInstanceOf(IllegalStateException.class);
    }

    static Stream<Arguments> roundTrips() {
        return SeededCases.of(20261003L, 200, r -> new Object[]{"T" + r.nextInt(1000), r.nextLong()});
    }

    @ParameterizedTest
    @MethodSource("roundTrips")
    void parseInvertsIssue(String tenant, long seed) {
        SignToken token = SignToken.issue(TenantId.of(tenant), seeded(seed));
        SignToken parsed = SignToken.parse(token.reveal());
        assertThat(parsed.reveal()).isEqualTo(token.reveal());
        assertThat(parsed.hash()).isEqualTo(token.hash());
    }
}
