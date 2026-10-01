package com.ga.disclosure.seal.renderer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * 렌더 자산: 저장소 동봉 폰트(나눔고딕, OFL)와 sRGB ICC(CC0) — 클래스패스 {@code render/} 아래(출처는 {@code render/SOURCES.md}). 시스템 폰트·
 * JDK 내장 ICC를 쓰지 않는다(OS·JDK 차이가 바이트에 들어가지 않게, 3A 선행 소과제 D).
 */
final class RenderAssets {

    static final String FONT_FAMILY = "Nanum";
    static final byte[] REGULAR = load("render/fonts/NanumGothic-Regular.ttf");
    static final byte[] BOLD = load("render/fonts/NanumGothic-Bold.ttf");
    static final byte[] SRGB = load("render/icc/sRGB-v2-magic.icc");

    private RenderAssets() {
    }

    private static byte[] load(String path) {
        try (InputStream in = RenderAssets.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("render asset missing: " + path);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
