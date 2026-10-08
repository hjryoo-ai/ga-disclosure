package com.ga.disclosure.app;

import com.ga.disclosure.infra.testing.PiiSentinels;
import com.ga.platform.canonical.Canonicalizer;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * CLI IT 버퍼의 평문 스캔(6A 계획 §9.3·머리말 10): 시험이 {@code System.out}을 버퍼로 돌리는 동안의 출력은 결과 XML에 가지 않아 빌드의 누출 스캔이 보지
 * 못한다. 그래서 버퍼를 여기서 센티널 14종({@link PiiSentinels}) + 그 시험들이 실제로 등록하는 가상 고객 파일의 값(이름·번호·숫자만 번호·생년월일)으로 본다.
 * 찾은 값은 메시지에 싣지 않는다(개수만).
 */
public final class CliOutputScan {

    private static final List<String> FORBIDDEN = forbidden();

    private CliOutputScan() {
    }

    /** 금지 값이 하나라도 있으면 {@link AssertionError}(값 없이 개수만). */
    public static void assertClean(String output) {
        long hits = FORBIDDEN.stream().filter(output::contains).count();
        if (hits > 0) {
            throw new AssertionError("CLI output carries " + hits + " plaintext customer value(s) (sentinels or demo customers)");
        }
    }

    private static List<String> forbidden() {
        Set<String> out = new LinkedHashSet<>(PiiSentinels.forbidden());
        Path file = Path.of(System.getProperty("ga.repoRoot")).resolve("disclosure-demo/src/main/resources/customers.json");
        try {
            JsonNode customers = Canonicalizer.parseStrict(Files.readString(file)).get("customers");
            for (JsonNode c : customers) {
                out.add(c.get("name").asString());
                if (c.hasNonNull("phone")) {
                    out.add(c.get("phone").asString());
                    out.add(c.get("phone").asString().replace("-", ""));
                }
                if (c.hasNonNull("birthDate")) {
                    out.add(c.get("birthDate").asString());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(new ArrayList<>(out));
    }
}
