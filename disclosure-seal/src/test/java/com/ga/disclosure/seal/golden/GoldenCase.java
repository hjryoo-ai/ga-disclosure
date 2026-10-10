package com.ga.disclosure.seal.golden;

import com.ga.disclosure.seal.renderer.RendererVersion;
import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.domain.vo.TemplateRef;
import com.ga.disclosure.rules.template.FormTemplate;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.template.TemplateResolver;
import com.ga.disclosure.seal.canonical.CanonicalDocument;
import com.ga.platform.canonical.Canonicalizer;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 골든 사례 1건의 파일(3B 계획 §5): {@code canonical.json}(JCS 바이트), {@code template.json}(서식 본문, JCS), {@code input.properties}
 * (확인서 번호·서식 ID·버전), {@code expected.properties}(canonical·PDF SHA-256과 생성 환경). properties는 정렬된 {@code key=value} 줄만 쓴다
 * ({@code Properties.store}의 날짜 주석이 바이트를 흔들지 않게).
 */
record GoldenCase(String name, Path dir) {

    /** case-04(Phase 8): 서식 v2 × 렌더러 판 2 — 환급금 표. 앞의 셋은 판 1(파일 불변). */
    static final List<String> NAMES = List.of("case-01", "case-02", "case-03", "case-04");

    static Path root() {
        return Path.of(System.getProperty("ga.repoRoot"), "disclosure-seal", "src", "test", "resources", "golden");
    }

    static GoldenCase of(String name) {
        return new GoldenCase(name, root().resolve(name));
    }

    CanonicalDocument canonical() {
        return CanonicalDocument.parse(read("canonical.json"));
    }

    TemplateResolution template() {
        Map<String, String> in = input();
        JsonNode body = Canonicalizer.parseStrict(new String(read("template.json"), StandardCharsets.UTF_8));
        return TemplateResolver.resolution(new FormTemplate(TemplateRef.of(in.get("templateId"), Integer.parseInt(in.get("templateVersion"))),
                TemplateType.STANDARD, LocalDate.parse("2026-07-01"), null, body.get("fields"), body.get("layout"), body.get("pendingConfirmation"),
                null, null));
    }

    String disclosureNo() {
        return input().get("disclosureNo");
    }

    /** 이 사례를 그린 렌더러 판(Phase 8) — {@code input.properties}의 {@code rendererVersion}, 없으면 1(Phase 3B~7 골든은 파일을 바꾸지 않는다). */
    RendererVersion rendererVersion() {
        return RendererVersion.of(Integer.parseInt(input().getOrDefault("rendererVersion", "1")));
    }

    Map<String, String> input() {
        return properties("input.properties");
    }

    Map<String, String> expected() {
        return properties("expected.properties");
    }

    byte[] read(String file) {
        try {
            return Files.readAllBytes(dir.resolve(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Map<String, String> properties(String file) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : new String(read(file), StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                int eq = line.indexOf('=');
                out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return out;
    }

    static String lines(Map<String, String> values) {
        StringBuilder sb = new StringBuilder();
        values.forEach((k, v) -> sb.append(k).append('=').append(v).append('\n'));
        return sb.toString();
    }

    void write(String file, byte[] bytes) {
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve(file), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
