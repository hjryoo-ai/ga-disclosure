package com.ga.platform.canonical;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 C12: RFC 8785 테스트 벡터 전부 통과. 벡터 출처는 {@code src/test/resources/jcs/SOURCES.txt}.
 * <ul>
 *   <li>RFC 8785 §3.2.2 예제 → §3.2.3 정규형 → §3.2.4 UTF-8 바이트(hex)</li>
 *   <li>RFC 8785 §3.2.3 속성 정렬 예제(UTF-16 코드 단위 순서)</li>
 *   <li>RFC 8785 부록 B 숫자 직렬화 26행(NaN·Infinity는 오류)</li>
 *   <li>참조 구현(cyberphone/json-canonicalization) testdata 6종(커밋 고정)과 ES6 숫자 표본 1,000행</li>
 *   <li>RFC의 MUST에서 만든 거부 사례 6종(짝 없는 서로게이트, 중복 키, 표현 불가 숫자)</li>
 * </ul>
 */
class CanonicalizerTest {

    private static String resource(String path) {
        try (InputStream in = CanonicalizerTest.class.getResourceAsStream("/jcs/" + path)) {
            if (in == null) {
                throw new IllegalStateException("missing vector " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] hex(String spaced) {
        return HexFormat.of().parseHex(spaced.replaceAll("\\s+", ""));
    }

    static Stream<String> fileVectors() {
        return Stream.of("arrays", "french", "structures", "unicode", "values", "weird", "rfc8785-3.2.2", "rfc8785-3.2.3-sorting");
    }

    @ParameterizedTest
    @MethodSource("fileVectors")
    void fileVectorCanonicalizesToExpectedBytes(String name) {
        byte[] actual = Canonicalizer.canonicalize(resource("input/" + name + ".json"));
        assertThat(new String(actual, StandardCharsets.UTF_8)).isEqualTo(resource("output/" + name + ".json"));
        assertThat(actual).isEqualTo(resource("output/" + name + ".json").getBytes(StandardCharsets.UTF_8));
    }

    static Stream<String> hexVectors() {
        return Stream.of("arrays", "french", "structures", "unicode", "values", "weird", "rfc8785-3.2.2");
    }

    @ParameterizedTest
    @MethodSource("hexVectors")
    void fileVectorMatchesPublishedUtf8Hex(String name) {
        assertThat(Canonicalizer.canonicalize(resource("input/" + name + ".json"))).isEqualTo(hex(resource("outhex/" + name + ".txt")));
    }

    /** RFC 8785 §3.2.3 "Expected argument order" — 정규형에서 값이 나타나는 순서. */
    @Test
    void propertySortingFollowsRfcOrder() {
        String canonical = new String(Canonicalizer.canonicalize(resource("input/rfc8785-3.2.3-sorting.json")), StandardCharsets.UTF_8);
        List<String> expectedOrder = resource("rfc8785-3.2.3-sorting.order.txt").lines().toList();
        int from = 0;
        for (String value : expectedOrder) {
            int at = canonical.indexOf(value, from);
            assertThat(at).as("%s appears after position %d in %s", value, from, canonical).isGreaterThanOrEqualTo(from);
            from = at + value.length();
        }
        assertThat(expectedOrder).hasSize(7);
    }

    static Stream<Arguments> appendixBNumbers() {
        return resource("numbers-rfc8785-appendix-b.txt").lines()
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .map(l -> l.split(",", 2))
                .map(p -> Arguments.of(p[0], p[1]));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("appendixBNumbers")
    void appendixBNumberSerialization(String ieeeHex, String expected) {
        String text = "[" + Double.toString(Double.longBitsToDouble(Long.parseUnsignedLong(ieeeHex, 16))) + "]";
        if (expected.equals("ERROR")) {
            assertThatThrownBy(() -> Canonicalizer.canonicalize(text)).isInstanceOf(CanonicalizationException.class);
        } else {
            assertThat(new String(Canonicalizer.canonicalize(text), StandardCharsets.UTF_8)).isEqualTo("[" + expected + "]");
        }
    }

    @Test
    void appendixBHasAllTwentySixRows() {
        assertThat(appendixBNumbers()).hasSize(26);
    }

    static Stream<Arguments> es6Sample() {
        return resource("es6testfile-1k.txt").lines().map(l -> l.split(",", 2)).map(p -> Arguments.of(p[0], p[1]));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("es6Sample")
    void es6NumberSample(String ieeeHex, String expected) {
        String text = "[" + Double.toString(Double.longBitsToDouble(Long.parseUnsignedLong(ieeeHex, 16))) + "]";
        assertThat(new String(Canonicalizer.canonicalize(text), StandardCharsets.UTF_8)).isEqualTo("[" + expected + "]");
    }

    static Stream<String> invalidVectors() {
        return Stream.of("duplicate-property", "lone-surrogate-high", "lone-surrogate-in-key", "lone-surrogate-low",
                "number-overflow-infinity", "number-overflow-neg-infinity");
    }

    @ParameterizedTest
    @MethodSource("invalidVectors")
    void rfcMustErrorCasesAreRejected(String name) {
        assertThatThrownBy(() -> Canonicalizer.canonicalize(resource("invalid/" + name + ".json")))
                .isInstanceOf(CanonicalizationException.class);
    }

    @Test
    void validSurrogatePairIsEncodedNormally() {
        assertThat(Canonicalizer.canonicalize("[\"\\ud83d\\ude00\"]")).isEqualTo("[\"😀\"]".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void jsonNodePathIsIdenticalToTextPath() {
        JsonMapper plain = JsonMapper.builder().build();
        for (String name : fileVectors().toList()) {
            JsonNode node = plain.readTree(resource("input/" + name + ".json"));
            assertThat(Canonicalizer.canonicalize(node)).as(name).isEqualTo(Canonicalizer.canonicalize(resource("input/" + name + ".json")));
        }
    }

    @Test
    void jsonNodeWithUnrepresentableNumberIsRejectedNotStringified() {
        JsonNode overflow = JsonMapper.builder().build().readTree("[1e400]");
        assertThatThrownBy(() -> Canonicalizer.canonicalize(overflow)).isInstanceOf(CanonicalizationException.class);
    }

    @Test
    void parseStrictRejectsDuplicateKeys() {
        assertThatThrownBy(() -> Canonicalizer.parseStrict("{\"a\":1,\"a\":2}")).isInstanceOf(CanonicalizationException.class);
        assertThat(Canonicalizer.parseStrict("{\"b\":1,\"a\":2}").propertyNames()).containsExactly("b", "a");
    }

    @Test
    void sha256OfCanonicalFormIgnoresWhitespaceAndKeyOrder() {
        assertThat(Sha256.ofCanonical("{ \"b\": 2, \"a\": [1, 2.50] }")).isEqualTo(Sha256.ofCanonical("{\"a\":[1,2.5],\"b\":2}"));
        assertThat(Sha256.of("abc".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
