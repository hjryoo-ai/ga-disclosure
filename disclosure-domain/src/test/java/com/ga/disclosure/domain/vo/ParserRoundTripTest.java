package com.ga.disclosure.domain.vo;

import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.core.testing.SeededCases;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.random.RandomGenerator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DisclosureNo·ProductKey 파서 왕복(시드 고정 속성 테스트). */
class ParserRoundTripTest {

    private static final long SEED = 0x5EED_0000_0101L;
    private static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String UPPER_TAIL = UPPER + "_-";
    private static final String TENANT_TAIL = UPPER + "_";
    private static final String OPAQUE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final String OPAQUE_TAIL = OPAQUE + "_.-";

    static Stream<Arguments> disclosureNos() {
        return SeededCases.of(SEED, r -> new Object[] {
                word(r, UPPER, TENANT_TAIL, 32), r.nextInt(1000, 10000), r.nextInt(1, 1_000_000)});
    }

    static Stream<Arguments> productKeys() {
        return SeededCases.of(SEED + 1, r -> new Object[] {word(r, UPPER, UPPER_TAIL, 64), word(r, OPAQUE, OPAQUE_TAIL, 64)});
    }

    @ParameterizedTest
    @MethodSource("disclosureNos")
    void disclosureNoRoundTrip(String tenant, int year, int seq) {
        DisclosureNo no = new DisclosureNo(TenantId.of(tenant), year, seq);
        assertThat(DisclosureNo.parse(no.value())).isEqualTo(no);
        assertThat(no.value()).matches(tenant + "-\\d{4}-\\d{6}");
    }

    @ParameterizedTest
    @MethodSource("productKeys")
    void productKeyRoundTrip(String insurer, String code) {
        ProductKey key = new ProductKey(InsurerCode.of(insurer), code);
        assertThat(ProductKey.parse(key.value())).isEqualTo(key);
        assertThat(key.value()).containsOnlyOnce(":");
    }

    @ParameterizedTest
    @ValueSource(strings = {"T1-2026-481", "T1-26-000481", "t1-2026-000481", "T-1-2026-000481", "T1-2026-000000", "T1_2026_000481", ""})
    void disclosureNoRejectsMalformed(String raw) {
        assertThatThrownBy(() -> DisclosureNo.parse(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"INS-A", "INS-A:", ":PRD-1", "INS-A:PRD:1", "ins-a:PRD-1", "INS-A:PRD 1", ""})
    void productKeyRejectsMalformed(String raw) {
        assertThatThrownBy(() -> ProductKey.parse(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    private static String word(RandomGenerator r, String head, String tail, int maxLength) {
        int length = r.nextInt(1, maxLength + 1);
        StringBuilder sb = new StringBuilder(length).append(head.charAt(r.nextInt(head.length())));
        for (int i = 1; i < length; i++) {
            sb.append(tail.charAt(r.nextInt(tail.length())));
        }
        return sb.toString();
    }
}
