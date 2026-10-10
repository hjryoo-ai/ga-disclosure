package com.ga.disclosure.architecture;

import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7 0단계·G1: 확인서의 라벨은 서식 데이터에서만 온다.
 * <ol>
 *   <li><b>서식 라벨 = 코드 리터럴 0</b> — 모든 서식 번들(운영 {@code contracts/rules/bundles/templates}·시험 {@code rule-as-data/templates})의
 *       항목 라벨·섹션 라벨·문서 제목·서명 페이지 문구·산출불가 표기 중 어느 것도 운영 코드의 문자열 리터럴과 같지 않다.</li>
 *   <li><b>한글 리터럴은 닫힌 목록의 파일에만</b> — 운영 코드에서 한글을 담은 문자열 리터럴이 있는 파일은 아래 {@link #HANGUL_ALLOWED}(파일 → 사유)와
 *       정확히 같다. 새 파일에 한글 문구가 생기면(화면 라벨을 코드에 넣는 등) 실패하고, 목록에서 빠진 파일이 남아 있어도 실패한다.</li>
 * </ol>
 * 화면 코드({@code disclosure-web})의 같은 두 검사는 {@code disclosure-web/src/test/literalScan.test.ts}(TypeScript 구문 트리 판독 — 템플릿 리터럴·JSX 텍스트까지)가
 * 하고, 예외는 {@code messages.ko.json} 하나다(Phase 7 1단계, 승인 Q4).
 */
class LabelLiteralScanTest {

    private static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));
    private static final List<Path> TEMPLATE_DIRS = List.of(ROOT.resolve("contracts/rules/bundles/templates"),
            ROOT.resolve("disclosure-infra/src/integrationTest/resources/rule-as-data/templates"));
    private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7A3\\u1100-\\u11FF\\u3130-\\u318F]");

    /** 한글 문자열 리터럴이 허용되는 운영 파일(저장소 상대 경로) → 사유. 라벨이 아니라 문장·단위다. */
    static final Map<String, String> HANGUL_ALLOWED = Map.ofEntries(
            Map.entry("disclosure-audit/src/main/java/com/ga/disclosure/audit/verify/Statements.java",
                    "검증 보고서 결론 문장(contracts/verify 보고서의 statement 값)"),
            Map.entry("disclosure-rules/src/main/java/com/ga/disclosure/rules/metric/CollectionRates.java",
                    "징구율 정의 표기 '내부 지표 — 규제 정의 없음'(6B 승인 문구, 응답 값)"),
            Map.entry("disclosure-rules/src/main/java/com/ga/disclosure/rules/validation/ValidationSubject.java", "검증 메시지의 임시등록 상품 표기"),
            Map.entry("disclosure-rules/src/main/java/com/ga/disclosure/rules/validation/standard/DistinctInsurer.java", "검증 결과 메시지"),
            Map.entry("disclosure-rules/src/main/java/com/ga/disclosure/rules/validation/standard/FieldRequired.java", "검증 결과 메시지"),
            Map.entry("disclosure-rules/src/main/java/com/ga/disclosure/rules/validation/standard/GradeRequired.java", "검증 결과 메시지"),
            Map.entry("disclosure-rules/src/main/java/com/ga/disclosure/rules/validation/standard/GradeUnavailable.java", "검증 결과 메시지"),
            Map.entry("disclosure-rules/src/main/java/com/ga/disclosure/rules/validation/standard/MinCompare.java", "검증 결과 메시지"),
            Map.entry("disclosure-rules/src/main/java/com/ga/disclosure/rules/validation/standard/Panel.java", "검증 결과 메시지"),
            Map.entry("disclosure-rules/src/main/java/com/ga/disclosure/rules/validation/standard/RankMonotonic.java", "검증 결과 메시지"),
            Map.entry("disclosure-rules/src/main/java/com/ga/disclosure/rules/validation/standard/Reason.java", "검증 결과 메시지"),
            Map.entry("disclosure-rules/src/main/java/com/ga/disclosure/rules/validation/standard/Requested.java", "검증 결과 메시지"),
            Map.entry("disclosure-rules/src/main/java/com/ga/disclosure/rules/validation/standard/SameGroup.java", "검증 결과 메시지"),
            Map.entry("disclosure-rules/src/main/java/com/ga/disclosure/rules/validation/standard/SignerSet.java", "검증 결과 메시지"),
            Map.entry("disclosure-rules/src/main/java/com/ga/disclosure/rules/validation/standard/TempProduct.java", "검증 결과 메시지"),
            Map.entry("platform-core/src/main/java/com/ga/platform/core/arch/ArchRules.java", "규칙 위반 설명 문장(영문 속 'CLAUDE.md 코드 규약')"),
            Map.entry("platform-core/src/main/java/com/ga/platform/core/money/Won.java", "금액 표시 단위 '원'"),
            Map.entry("platform-spring/src/main/java/com/ga/platform/spring/jdbc/MissingTenantPredicateException.java",
                    "예외 설명 문장(영문 속 '절대 규칙 5')"));

    static List<Path> mainSources() {
        try (Stream<Path> modules = Files.list(ROOT)) {
            List<Path> out = new ArrayList<>();
            for (Path module : modules.filter(Files::isDirectory).sorted().toList()) {
                Path src = module.resolve("src/main/java");
                if (Files.isDirectory(src)) {
                    try (Stream<Path> files = Files.walk(src)) {
                        files.filter(f -> f.toString().endsWith(".java")).sorted().forEach(out::add);
                    }
                }
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 자바 원천의 문자열 리터럴(텍스트 블록 포함, 주석·문자 리터럴 제외). 이스케이프는 펼치지 않는다. */
    static List<String> literals(String src) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = src.length();
        while (i < n) {
            if (src.startsWith("//", i)) {
                int j = src.indexOf('\n', i);
                i = j < 0 ? n : j;
            } else if (src.startsWith("/*", i)) {
                int j = src.indexOf("*/", i + 2);
                i = j < 0 ? n : j + 2;
            } else if (src.startsWith("\"\"\"", i)) {
                int j = src.indexOf("\"\"\"", i + 3);
                while (j > 0 && src.charAt(j - 1) == '\\') {
                    j = src.indexOf("\"\"\"", j + 1);
                }
                out.add(src.substring(i + 3, j));
                i = j + 3;
            } else if (src.charAt(i) == '"') {
                StringBuilder b = new StringBuilder();
                int j = i + 1;
                while (src.charAt(j) != '"') {
                    if (src.charAt(j) == '\\') {
                        b.append(src, j, j + 2);
                        j += 2;
                    } else {
                        b.append(src.charAt(j++));
                    }
                }
                out.add(b.toString());
                i = j + 1;
            } else if (src.charAt(i) == '\'') {
                int j = i + 1;
                while (src.charAt(j) != '\'') {
                    j += src.charAt(j) == '\\' ? 2 : 1;
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    /** 서식 번들의 사람에게 보이는 문구 전부: 항목 라벨·산출불가 표기, 문서 제목·섹션 라벨, 서명 페이지의 문자열 값(라벨 참조 표식 제외). */
    static Set<String> templateLabels() {
        Set<String> labels = new TreeSet<>();
        int bundles = 0;
        for (Path dir : TEMPLATE_DIRS) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".bundle.json")).sorted().toList()) {
                    bundles++;
                    JsonNode body = Canonicalizer.parseStrict(Files.readString(f)).get("body");
                    body.get("fields").forEach(field -> {
                        labels.add(field.get("label").asString());
                        JsonNode unavailable = field.path("render").path("unavailableText");
                        if (unavailable.isString()) {
                            labels.add(unavailable.asString());
                        }
                    });
                    JsonNode layout = body.get("layout");
                    labels.add(layout.get("title").asString());
                    layout.get("sections").forEach(s -> {
                        if (s.path("label").isString()) {
                            labels.add(s.get("label").asString());
                        }
                    });
                    collectStrings(layout.get("signaturePage"), labels);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(bundles).as("template bundles scanned").isGreaterThanOrEqualTo(3);
        labels.removeIf(l -> l.startsWith("TODO(confirm#"));
        return labels;
    }

    private static void collectStrings(JsonNode node, Set<String> out) {
        if (node.isString()) {
            out.add(node.asString());
        } else {
            node.forEach(child -> collectStrings(child, out));
        }
    }

    @Test
    void noTemplateLabelIsACodeLiteral() throws IOException {
        Set<String> labels = templateLabels();
        assertThat(labels).as("labels read from template data").hasSizeGreaterThan(30);
        List<String> hits = new ArrayList<>();
        for (Path f : mainSources()) {
            for (String literal : literals(Files.readString(f, StandardCharsets.UTF_8))) {
                if (labels.contains(literal.strip())) {
                    hits.add(ROOT.relativize(f) + ": \"" + literal + "\"");
                }
            }
        }
        assertThat(hits).as("template labels written as code literals").isEmpty();
    }

    @Test
    void hangulLiteralsLiveOnlyInTheClosedListOfFiles() throws IOException {
        Map<String, Integer> found = new TreeMap<>();
        for (Path f : mainSources()) {
            long n = literals(Files.readString(f, StandardCharsets.UTF_8)).stream().filter(l -> HANGUL.matcher(l).find()).count();
            if (n > 0) {
                found.put(ROOT.relativize(f).toString().replace('\\', '/'), (int) n);
            }
        }
        assertThat(found.keySet()).as("files with Hangul string literals (add a reason to HANGUL_ALLOWED, or move the text to data)")
                .containsExactlyInAnyOrderElementsOf(HANGUL_ALLOWED.keySet());
    }

    @Test
    void theLiteralReaderSkipsCommentsAndCharsAndKeepsTextBlocks() {
        assertThat(literals("""
                // "주석"
                /* "블록 주석" */ String a = "가\\"나"; char c = '"'; String b = \"""
                    본문
                    \""";
                """)).containsExactly("가\\\"나", "\n    본문\n    ");
    }
}
