package com.ga.disclosure.seal.renderer;

import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3B S13: 렌더러 소스에 서식 항목 코드·섹션 코드가 문자열 리터럴로 없다 — 렌더러는 결속({@code bind})과 배치(layout)로만 값을 찾는다
 * (절대 규칙 4). 코드 목록은 정본 서식 번들과 통합 테스트 픽스처 서식에서 읽는다(손목록이 아니다).
 */
class NoFieldCodeLiteralsTest {

    private static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));
    private static final Path RENDERER = ROOT.resolve("disclosure-seal/src/main/java/com/ga/disclosure/seal/renderer");

    static Set<String> templateCodes() throws IOException {
        Set<String> codes = new TreeSet<>();
        List<Path> bundles = new ArrayList<>();
        for (Path dir : List.of(ROOT.resolve("contracts/rules/bundles/templates"),
                ROOT.resolve("disclosure-infra/src/integrationTest/resources/rule-as-data/templates"))) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.toString().endsWith(".bundle.json")).forEach(bundles::add);
            }
        }
        assertThat(bundles).as("서식 번들을 찾았다").hasSizeGreaterThanOrEqualTo(2);
        for (Path b : bundles) {
            JsonNode body = Canonicalizer.parseStrict(Files.readString(b)).get("body");
            body.path("fields").forEach(f -> codes.add(f.path("code").asString()));
            body.path("layout").path("sections").forEach(s -> codes.add(s.path("code").asString()));
        }
        return codes;
    }

    @Test
    void rendererSourcesNameNoTemplateCode() throws IOException {
        Set<String> codes = templateCodes();
        assertThat(codes).contains("PREMIUM", "COMPARISON", "CUSTOMER_NAME");
        List<String> hits = new ArrayList<>();
        try (Stream<Path> s = Files.walk(RENDERER)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".java")).toList()) {
                String text = Files.readString(p);
                for (String code : codes) {
                    if (text.contains("\"" + code + "\"")) {
                        hits.add(p.getFileName() + ": \"" + code + "\"");
                    }
                }
            }
        }
        assertThat(hits).as("렌더러 소스의 서식 코드 리터럴").isEmpty();
    }
}
