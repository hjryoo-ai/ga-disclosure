package com.ga.disclosure.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 12단계(지시문 §4·계획 ⑦ — 포트폴리오 문서): 처음 보는 엔지니어가 읽는 문서(README·ARCHITECTURE·EVIDENCE·DECISIONS·DEVELOPMENT·운영 문서)가
 * ① 형용사 금지 목록을 쓰지 않고 ② "법령 요건 충족"을 단언하지 않으며(면책 문장 안에서만) ③ 상대 링크가 있는 파일을 가리키고 ④ `docs/…md:줄` 인용이 그
 * 파일 길이 안이며 ⑤ 이름을 댄 시험 클래스(`…Test`·`…IT`, `클래스.메서드`면 메서드까지)가 저장소에 있고 ⑥ README 첫 화면(첫 절)이 알려진 한계라는 것.
 * 문서가 코드를 따라 낡으면(시험 이름이 바뀌면) 여기서 실패한다.
 */
class PortfolioDocsTest {

    static final List<String> BANNED = List.of("강력한", "강력하게", "완벽한", "완벽하게", "안전한", "안전하게", "획기적", "혁신적", "최고의", "최첨단",
            "손쉽게", "간편한", "robust", "seamless");
    private static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));
    private static final Set<String> SKIP_DIRS = Set.of(".git", "build", "node_modules", ".gradle", ".idea", "test-results", "playwright-report");
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final Pattern DOC_LINE = Pattern.compile("(docs/[^`\\s:)(]+\\.md):(\\d+)(?:-(\\d+))?");
    private static final Pattern TEST_NAME = Pattern.compile("`([A-Z][A-Za-z0-9]*(?:Test|IT))(?:\\.([a-z][A-Za-z0-9_]*))?`");

    static List<Path> documents() {
        List<Path> docs = new ArrayList<>(List.of(ROOT.resolve("README.md"), ROOT.resolve("docs/ARCHITECTURE.md"), ROOT.resolve("docs/EVIDENCE.md"),
                ROOT.resolve("docs/DECISIONS.md"), ROOT.resolve("docs/DEVELOPMENT.md")));
        try (Stream<Path> ops = Files.list(ROOT.resolve("docs/operations"))) {
            ops.filter(p -> p.toString().endsWith(".md")).sorted().forEach(docs::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return docs;
    }

    @Test
    void portfolioDocumentsUseNoBannedAdjectiveAndAssertNoLegalCompliance() {
        List<String> problems = new ArrayList<>();
        for (Path doc : documents()) {
            problems.addAll(wording(ROOT.relativize(doc).toString(), read(doc)));
        }
        assertThat(problems).as("형용사 금지 목록·법령 요건 충족 단언").isEmpty();
    }

    @Test
    void linksLineCitationsAndNamedTestsPointAtThingsThatExist() {
        Map<String, Path> sources = sourceIndex();
        List<String> problems = new ArrayList<>();
        for (Path doc : documents()) {
            problems.addAll(references(doc, read(doc), sources));
        }
        assertThat(problems).as("깨진 링크·줄 인용·시험 이름").isEmpty();
    }

    @Test
    void theReadmeOpensWithTheKnownLimitations() {
        List<String> headings = read(ROOT.resolve("README.md")).lines().filter(l -> l.startsWith("## ")).toList();
        assertThat(headings).as("README 첫 절").first().asString().startsWith("## 알려진 한계");
    }

    /** 검사가 일하는지(공회전 방지): 금지어·단언·깨진 링크·없는 줄·없는 시험을 심은 문서는 각각 잡힌다. */
    @Test
    void theChecksFindPlantedProblems() throws IOException {
        Path dir = Files.createTempDirectory("docs-check");
        Path doc = dir.resolve("planted.md");
        String text = """
                이 시스템은 강력한 증거를 남긴다. 이 화면은 법령 요건 충족을 보장한다.
                "법령 요건 충족"을 시스템이 단언하지 않는다.
                [없는 파일](nowhere.md) · `docs/설계서.md:999999` · `NoSuchThingIT` · `GlobalKekPathScanTest.noSuchMethod`
                """;
        Files.writeString(doc, text);
        assertThat(wording("planted.md", text)).containsExactly("planted.md:1 금지어 강력한", "planted.md:1 법령 요건 충족 단언");
        assertThat(references(doc, text, sourceIndex())).containsExactly(
                "planted.md:3 링크 nowhere.md", "planted.md:3 줄 인용 docs/설계서.md:999999", "planted.md:3 시험 NoSuchThingIT",
                "planted.md:3 메서드 GlobalKekPathScanTest.noSuchMethod");
    }

    static List<String> wording(String name, String text) {
        List<String> out = new ArrayList<>();
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String word : BANNED) {
                if (line.contains(word)) {
                    out.add(name + ":" + (i + 1) + " 금지어 " + word);
                }
            }
            if (line.contains("법령 요건") && !line.contains("단언")) {
                out.add(name + ":" + (i + 1) + " 법령 요건 충족 단언");
            }
        }
        return out;
    }

    static List<String> references(Path doc, String text, Map<String, Path> sources) {
        List<String> out = new ArrayList<>();
        String name = doc.getFileName().toString().equals("README.md") ? "README.md" : doc.getFileName().toString();
        List<String> lines = text.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String where = name + ":" + (i + 1);
            Matcher link = LINK.matcher(line);
            while (link.find()) {
                String target = link.group(1);
                if (target.startsWith("http") || target.startsWith("#") || target.startsWith("mailto:")) {
                    continue;
                }
                String path = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
                if (!Files.exists(doc.getParent().resolve(path))) {
                    out.add(where + " 링크 " + target);
                }
            }
            Matcher cite = DOC_LINE.matcher(line);
            while (cite.find()) {
                Path file = ROOT.resolve(cite.group(1));
                int last = Integer.parseInt(cite.group(3) != null ? cite.group(3) : cite.group(2));
                if (!Files.isRegularFile(file) || read(file).lines().count() < last) {
                    out.add(where + " 줄 인용 " + cite.group());
                }
            }
            Matcher test = TEST_NAME.matcher(line);
            while (test.find()) {
                Path source = sources.get(test.group(1));
                if (source == null) {
                    out.add(where + " 시험 " + test.group(1));
                } else if (test.group(2) != null && !Pattern.compile("\\b" + test.group(2) + "\\s*\\(").matcher(read(source)).find()) {
                    out.add(where + " 메서드 " + test.group(1) + "." + test.group(2));
                }
            }
        }
        return out;
    }

    /** 저장소의 Java·TypeScript 원천: 파일 이름(확장자 뺀) → 경로. */
    static Map<String, Path> sourceIndex() {
        Map<String, Path> index = new HashMap<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> !skipped(ROOT.relativize(p)))
                    .filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".ts") || p.toString().endsWith(".tsx"))
                    .forEach(p -> index.putIfAbsent(p.getFileName().toString().replaceFirst("\\.(java|tsx?)$", "").replaceFirst("\\.test$", ""), p));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return index;
    }

    private static boolean skipped(Path relative) {
        for (Path part : relative) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
