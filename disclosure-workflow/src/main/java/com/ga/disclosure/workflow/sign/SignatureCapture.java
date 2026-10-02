package com.ga.disclosure.workflow.sign;

import com.ga.platform.canonical.Canonicalizer;
import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Objects;

/**
 * 터치 서명 입력(TOUCH_PAD·REMOTE_LINK 고객, DRAWN 설계사): 스트로크(좌표·시각 시퀀스 JSON)와 렌더 이미지(PNG), 기기 정보, IP. 스트로크·이미지는
 * 문서 키로 암호화돼 서명 증거 객체가 되고(평문은 어디에도 남지 않는다, G5), 이미지는 서명본 외관 페이지에만 그려진다. 형식 검사는 여기서 한다 —
 * 스트로크는 엄격 JSON {@code [[{x, y, t}…]…]}(빈 획 없음), 이미지는 PNG 시그니처·크기 상한.
 */
public final class SignatureCapture {

    /** 저장·렌더 안전 상한(규제 룰이 아니다). */
    public static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_STROKE_BYTES = 1024 * 1024;
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private final byte[] strokes;
    private final byte[] imagePng;
    private final DeviceInfo device;
    private final String ip;

    /**
     * @param strokesJson 스트로크 JSON(UTF-8). 저장은 JCS로 정규화한 바이트다
     * @param imagePng    렌더 이미지 PNG
     */
    public SignatureCapture(byte[] strokesJson, byte[] imagePng, DeviceInfo deviceOrNull, String ipOrNull) {
        Objects.requireNonNull(strokesJson, "strokesJson");
        Objects.requireNonNull(imagePng, "imagePng");
        if (strokesJson.length == 0 || strokesJson.length > MAX_STROKE_BYTES) {
            throw new IllegalArgumentException("strokes must be 1.." + MAX_STROKE_BYTES + " bytes");
        }
        JsonNode parsed;
        try {
            parsed = Canonicalizer.parseStrict(new String(strokesJson, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("strokes are not strict JSON");
        }
        if (!parsed.isArray() || parsed.isEmpty()) {
            throw new IllegalArgumentException("strokes must be a non-empty array of strokes");
        }
        for (JsonNode stroke : parsed) {
            if (!stroke.isArray() || stroke.isEmpty()) {
                throw new IllegalArgumentException("every stroke is a non-empty array of points");
            }
            for (JsonNode p : stroke) {
                if (!p.isObject() || p.size() != 3 || !p.path("x").isNumber() || !p.path("y").isNumber() || !p.path("t").isIntegralNumber()) {
                    throw new IllegalArgumentException("every point is {x, y, t} (t = milliseconds)");
                }
            }
        }
        this.strokes = Canonicalizer.canonicalize(parsed);
        this.imagePng = requirePng(imagePng, MAX_IMAGE_BYTES);
        this.device = deviceOrNull;
        this.ip = IpAddress.check(ipOrNull);
    }

    static byte[] requirePng(byte[] bytes, int max) {
        if (bytes.length <= PNG.length || bytes.length > max || !Arrays.equals(bytes, 0, PNG.length, PNG, 0, PNG.length)) {
            throw new IllegalArgumentException("signature image must be a PNG of at most " + max + " bytes");
        }
        return bytes.clone();
    }

    /** 정규화된 스트로크 바이트(JCS). */
    public byte[] strokes() {
        return strokes.clone();
    }

    public byte[] imagePng() {
        return imagePng.clone();
    }

    public DeviceInfo deviceOrNull() {
        return device;
    }

    public String ipOrNull() {
        return ip;
    }

    @Override
    public String toString() {
        return "SignatureCapture[strokes=" + strokes.length + "B, image=" + imagePng.length + "B]";
    }
}
