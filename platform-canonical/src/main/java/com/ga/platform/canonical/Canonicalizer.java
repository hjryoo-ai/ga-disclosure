package com.ga.platform.canonical;

import org.erdtman.jcs.JsonCanonicalizer;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * RFC 8785 JSON Canonicalization Scheme(JCS). 봉인·서명·체인·룰 번들 해시의 입력은 항상 이 클래스가 만든 바이트다.
 *
 * <p>정규화 자체는 {@code io.github.erdtman:java-json-canonicalization}(참조 구현 계열)에 맡긴다 — 이 저장소는 JCS를 직접
 * 구현하지 않는다. RFC 8785 부록 B·§3.2 예제·참조 구현 testdata를 전부 통과한 유일한 후보라서 채택했고, 이 클래스는 그 앞뒤의
 * 경계만 조인다.
 * <ul>
 *   <li><b>짝 없는 서로게이트</b>: RFC는 오류를 요구하지만 라이브러리는 {@code ?}로 바꾼다(라이브러리 이슈 #5). 정규화 문자열을
 *       REPORT 모드 UTF-8 인코더로 바이트화해 오류로 만든다.</li>
 *   <li><b>{@link JsonNode} 입력</b>: Jackson 기본 설정은 표현 불가 숫자(예: {@code 1e400} → Infinity)를 문자열
 *       {@code "Infinity"}로 조용히 바꾼다. {@code WRITE_NAN_AS_STRINGS}를 끈 전용 매퍼로 직렬화해 라이브러리가 거부하게 한다.</li>
 *   <li><b>중복 키</b>: 문자열 입력은 라이브러리 파서가 거부한다. {@link JsonNode}는 이미 중복이 사라진 뒤이므로, 원문을 읽는
 *       쪽은 {@link #parseStrict(String)}를 쓴다.</li>
 * </ul>
 * 알려진 한계: 라이브러리 이슈 #4(1e-320 부근 서브노멀 숫자 1개 오직렬화). 이 시스템의 해시 입력은 정수·문자열·불리언뿐이다.
 */
public final class Canonicalizer {

    private static final JsonMapper WRITER = JsonMapper.builder()
            .disable(JsonWriteFeature.WRITE_NAN_AS_STRINGS)
            .build();

    private static final JsonMapper STRICT_READER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private Canonicalizer() {
    }

    /** JSON 텍스트를 JCS 정규화 UTF-8 바이트로. 유효하지 않은 JSON·중복 키·짝 없는 서로게이트·표현 불가 숫자는 거부. */
    public static byte[] canonicalize(String json) {
        Objects.requireNonNull(json, "json");
        String canonical;
        try {
            canonical = new JsonCanonicalizer(json).getEncodedString();
        } catch (IOException | RuntimeException e) {
            throw new CanonicalizationException("not canonicalizable JSON: " + e.getMessage(), e);
        }
        return strictUtf8(canonical);
    }

    /** JSON 트리를 JCS 정규화 UTF-8 바이트로. */
    public static byte[] canonicalize(JsonNode node) {
        Objects.requireNonNull(node, "node");
        String text;
        try {
            text = WRITER.writeValueAsString(node);
        } catch (JacksonException e) {
            throw new CanonicalizationException("cannot serialize JSON tree: " + e.getOriginalMessage(), e);
        }
        return canonicalize(text);
    }

    /** 중복 키를 거부하며 읽는다. 해시 대상 원문(번들 파일 등)은 이 메서드로 읽는다. */
    public static JsonNode parseStrict(String json) {
        Objects.requireNonNull(json, "json");
        try {
            return STRICT_READER.readTree(json);
        } catch (JacksonException e) {
            throw new CanonicalizationException("invalid JSON: " + e.getOriginalMessage(), e);
        }
    }

    private static byte[] strictUtf8(String text) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new CanonicalizationException("lone surrogate in JSON string (RFC 8785 §3.2.2.2)", e);
        }
    }
}
