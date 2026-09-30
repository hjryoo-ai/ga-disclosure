package com.ga.disclosure.infra.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * 평문 유출 스캔의 센티널(Phase 2 P4, {@code pii-sentinels.properties}). 이 값으로 등록한 고객의 이름·전화·생년월일이 어떤 출력에도
 * 나타나면 안 된다. {@link #forbidden()}은 원문·변형과 각각의 UTF-8 16진 표현(평문을 BYTEA로 잘못 넣으면 덤프에 {@code \x…}로 나온다)이다.
 */
public final class PiiSentinels {

    private static final Properties VALUES = load();

    public static final String NAME = VALUES.getProperty("name");
    public static final String PHONE = VALUES.getProperty("phone");
    public static final String BIRTH_DATE = VALUES.getProperty("birthDate");

    private PiiSentinels() {
    }

    public static List<String> forbidden() {
        Set<String> plain = new LinkedHashSet<>(List.of(NAME, PHONE, BIRTH_DATE));
        for (String v : VALUES.getProperty("variants").split(",")) {
            plain.add(v.strip());
        }
        List<String> out = new ArrayList<>(plain);
        plain.forEach(v -> out.add(HexFormat.of().formatHex(v.getBytes(StandardCharsets.UTF_8))));
        return List.copyOf(out);
    }

    /** {@code text}에 나타난 금지 문자열(없으면 빈 목록). */
    public static List<String> findIn(String text) {
        return forbidden().stream().filter(text::contains).toList();
    }

    private static Properties load() {
        try (InputStream in = PiiSentinels.class.getResourceAsStream("/pii-sentinels.properties")) {
            if (in == null) {
                throw new IllegalStateException("pii-sentinels.properties is missing from the test fixtures");
            }
            Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
