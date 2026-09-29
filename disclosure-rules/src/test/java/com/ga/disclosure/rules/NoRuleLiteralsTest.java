package com.ga.disclosure.rules;

import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.rules.testing.Bundles;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 C9: disclosure-rules 메인 소스의 문자열 리터럴에 부록 D의 사유 코드와 서명자 역할 이름이 0건이다(룰은 데이터 —
 * CLAUDE.md 절대 규칙 4). 스키마·번들 파일과 테스트는 제외한다. 금지 목록은 정본 번들과 {@link SignerRole}에서 읽고,
 * 부록 D의 값을 고정 목록으로도 한 번 더 둔다(데이터가 바뀌어도 규칙이 약해지지 않게).
 */
class NoRuleLiteralsTest {

    private static final Path MAIN = Path.of(System.getProperty("ga.repoRoot"), "disclosure-rules/src/main/java");
    private static final Set<String> APPENDIX_D = Set.of("COVERAGE", "PREMIUM", "PURPOSE", "OTHER", "CUSTOMER_REQUEST",
            "CUSTOMER", "AGENT", "MANAGER");
    private static final Pattern TEXT_BLOCK = Pattern.compile("\"\"\"(.*?)\"\"\"", Pattern.DOTALL);
    private static final Pattern STRING = Pattern.compile("\"((?:\\\\.|[^\"\\\\\\n])*)\"");
    private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    static Set<String> forbidden() {
        Set<String> out = new LinkedHashSet<>(APPENDIX_D);
        JsonNode body = JsonMapper.builder().build().readTree(Bundles.text(Bundles.DISC_2026_07)).get("body");
        body.get("reasonCodes").forEach(c -> out.add(c.get("code").asString()));
        Arrays.stream(SignerRole.values()).forEach(r -> out.add(r.name()));
        return out;
    }

    /** 주석을 지운 소스의 문자열 리터럴(텍스트 블록 포함). */
    static List<String> literals(String source) {
        String code = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        code = LINE_COMMENT.matcher(code).replaceAll("");
        List<String> out = new ArrayList<>();
        Matcher blocks = TEXT_BLOCK.matcher(code);
        StringBuilder rest = new StringBuilder();
        while (blocks.find()) {
            out.add(blocks.group(1));
            blocks.appendReplacement(rest, "\"\"");
        }
        blocks.appendTail(rest);
        Matcher strings = STRING.matcher(rest);
        while (strings.find()) {
            out.add(strings.group(1));
        }
        return out;
    }

    static List<String> violations(Path root, Set<String> tokens) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                for (String literal : literals(Files.readString(file))) {
                    for (String token : tokens) {
                        if (Pattern.compile("(?<![A-Z0-9_])" + Pattern.quote(token) + "(?![A-Z0-9_])").matcher(literal).find()) {
                            out.add(root.relativize(file) + ": \"" + literal.strip() + "\" contains " + token);
                        }
                    }
                }
            }
        }
        return out;
    }

    @Test
    void mainSourcesContainNoReasonCodeOrSignerRoleLiterals() throws IOException {
        assertThat(Files.isDirectory(MAIN)).isTrue();
        assertThat(forbidden()).contains("CUSTOMER_REQUEST", "MANAGER");
        assertThat(violations(MAIN, forbidden())).isEmpty();
    }

    @Test
    void scannerFindsLiteralsButNotIdentifiersOrComments() {
        String source = """
                class X {
                    // "MANAGER" in a comment is fine
                    /* "COVERAGE" too */
                    SignerRole r = SignerRole.MANAGER;           // identifier, not a literal
                    String a = "role MANAGER";
                    String b = \"""
                        PREMIUM
                        \""";
                    String c = "MANAGERIAL";
                }
                """;
        List<String> found = literals(source);
        assertThat(found).anyMatch(l -> l.contains("role MANAGER")).anyMatch(l -> l.contains("PREMIUM"));
        assertThat(found).noneMatch(l -> l.contains("in a comment")).noneMatch(l -> l.contains("too"));
    }
}
