package com.ga.disclosure.architecture;

import com.ga.disclosure.compliance.rules.RuleActivationJob;
import com.ga.disclosure.compliance.rules.RuleBundleReconciler;
import com.ga.disclosure.workflow.disclosure.DisclosureFlagPort;
import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6B 계획 §7: 준법 플래그 유형은 닫힌 목록이고 네 곳이 같아야 한다 — 설계서 §6.8 {@code flag-types} 블록(정본), 마지막 마이그레이션의
 * {@code ck_compliance_flag_type} CHECK, 저장소 안 <b>모든</b> RULE 번들의 {@code complianceQueue.types} 키, 코드가 올리는 유형 상수
 * ({@link DisclosureFlagPort.Type} + 준법 배치의 문자열 상수). 규제 번들(GLOBAL)의 담당 역할·수동 해소 여부는 블록과 같고, 블록의
 * "올리는 곳" 클래스는 그 유형을 실제로 참조한다. 문자열 유형으로 올리는 {@code raiseOpen(...)} 호출은 아래 상수만 쓴다.
 */
class FlagTypeTableTest {

    private static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));
    private static final Path MIGRATIONS = ROOT.resolve("disclosure-infra/src/main/resources/db/migration");
    private static final Pattern BLOCK = Pattern.compile("```flag-types\\n(.*?)\\n```", Pattern.DOTALL);
    private static final Pattern CHECK = Pattern.compile("ck_compliance_flag_type\\s+CHECK\\s*\\(\\s*type\\s+IN\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final Pattern RAISE_OPEN = Pattern.compile("\\.raiseOpen\\(\\s*([^,]+?)\\s*,"); // 호출만(선언은 점 없이 반환형 뒤) — 첫 인자는 모양 무관하게 전부
    private static final Set<String> SKIP_DIRS = Set.of("build", ".git", ".gradle", "node_modules", ".idea", "out");

    /** 문자열로 유형을 올리는 준법 배치의 상수(FQN 열거 — 새 경로는 여기 추가해야 통과). */
    private static final Map<String, String> STRING_CONSTANTS = Map.of(
            "MISSED_FLAG_TYPE", RuleActivationJob.MISSED_FLAG_TYPE,
            "FLAG_TYPE", RuleBundleReconciler.FLAG_TYPE);

    record Row(String type, List<String> raisedBy, String assignedRole, boolean manual) {
    }

    private static Map<String, Row> doc;

    @BeforeAll
    static void parseDoc() {
        doc = parse(read(ROOT.resolve("docs/설계서.md")));
    }

    static Map<String, Row> parse(String markdown) {
        Matcher m = BLOCK.matcher(markdown);
        if (!m.find()) {
            throw new IllegalStateException("설계서에 flag-types 블록이 없다");
        }
        String body = m.group(1);
        if (m.find()) {
            throw new IllegalStateException("flag-types 블록이 둘 이상이다");
        }
        List<String> lines = body.lines().toList();
        if (!lines.getFirst().equals("type,raised_by,assigned_role,manual")) {
            throw new IllegalStateException("flag-types 머리줄이 다르다: " + lines.getFirst());
        }
        Map<String, Row> out = new LinkedHashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] c = line.split(",", -1);
            if (c.length != 4 || !(c[3].equals("yes") || c[3].equals("no"))) {
                throw new IllegalStateException("flag-types 행 형식: " + line);
            }
            if (out.put(c[0], new Row(c[0], List.of(c[1].split("\\|")), c[2], c[3].equals("yes"))) != null) {
                throw new IllegalStateException("flag-types 중복 유형: " + c[0]);
            }
        }
        return out;
    }

    @Test
    void theDatabaseCheckListsExactlyTheDocumentedTypes() {
        assertThat(lastCheck()).containsExactlyInAnyOrderElementsOf(doc.keySet());
    }

    @Test
    void everyRuleBundleConfiguresExactlyTheDocumentedTypes() {
        List<Path> bundles = ruleBundles();
        assertThat(bundles).as("GLOBAL bundles in contracts, demo and test resources").hasSizeGreaterThanOrEqualTo(4);
        int global = 0;
        for (Path p : bundles) {
            JsonNode bundle = Canonicalizer.parseStrict(read(p));
            JsonNode types = bundle.at("/body/complianceQueue/types");
            if (bundle.path("scope").asString().equals("TENANT") && types.isMissingNode()) {
                continue; // 사규가 덮지 않으면 GLOBAL 값을 쓴다
            }
            assertThat(names(types)).as(ROOT.relativize(p).toString()).containsExactlyInAnyOrderElementsOf(doc.keySet());
            if (bundle.path("scope").asString().equals("GLOBAL")) {
                global++;
            }
        }
        assertThat(global).isGreaterThanOrEqualTo(4);
    }

    @Test
    void regulatoryBundlesMatchTheDocumentedRoleAndManualResolution() {
        for (String name : List.of("DISC-2026-07", "DISC-2027-01")) {
            JsonNode types = Canonicalizer.parseStrict(read(ROOT.resolve("contracts/rules/bundles/rules/" + name + ".bundle.json")))
                    .at("/body/complianceQueue/types");
            for (Row row : doc.values()) {
                JsonNode policy = types.get(row.type());
                assertThat(policy.path("assignedRole").asString()).as("%s %s assignedRole", name, row.type()).isEqualTo(row.assignedRole());
                assertThat(!policy.path("resolutionCodes").isEmpty()).as("%s %s manual", name, row.type()).isEqualTo(row.manual());
                assertThat(policy.path("visibleToAgent").asBoolean()).as("6A review §2 ①: no type is agent-visible yet").isFalse();
            }
        }
    }

    @Test
    void theCodeRaisesExactlyTheDocumentedTypes() {
        Set<String> code = new TreeSet<>();
        Arrays.stream(DisclosureFlagPort.Type.values()).map(Enum::name).forEach(code::add);
        code.addAll(STRING_CONSTANTS.values());
        assertThat(code).containsExactlyInAnyOrderElementsOf(doc.keySet());
    }

    @Test
    void stringTypedRaisesUseOnlyTheEnumeratedConstants() {
        List<String> args = new ArrayList<>();
        for (Path p : mainSources()) {
            Matcher m = RAISE_OPEN.matcher(read(p));
            while (m.find()) {
                args.add(m.group(1));
            }
        }
        assertThat(args).isNotEmpty().allSatisfy(a -> assertThat(STRING_CONSTANTS).containsKey(a));
    }

    @Test
    void theDocumentedRaisersReferenceTheirType() {
        Map<String, String> sources = mainSources().stream()
                .collect(Collectors.toMap(p -> p.getFileName().toString().replace(".java", ""), FlagTypeTableTest::read, (a, b) -> a + b));
        for (Row row : doc.values()) {
            String constant = STRING_CONSTANTS.entrySet().stream().filter(e -> e.getValue().equals(row.type())).map(Map.Entry::getKey)
                    .findFirst().orElse("Type." + row.type());
            for (String cls : row.raisedBy()) {
                assertThat(sources).as("raiser class %s", cls).containsKey(cls);
                assertThat(sources.get(cls)).as("%s references %s", cls, row.type()).contains(constant);
            }
        }
    }

    @Test
    void theParserRejectsMalformedBlocks() {
        assertThat(parse("```flag-types\ntype,raised_by,assigned_role,manual\nX,A,COMPLIANCE,yes\n```")).containsOnlyKeys("X");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parse("```flag-types\ntype,raised_by\nX,A\n```")).hasMessageContaining("머리줄");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parse("```flag-types\ntype,raised_by,assigned_role,manual\nX,A,COMPLIANCE,maybe\n```"))
                .hasMessageContaining("행 형식");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> parse("no block")).hasMessageContaining("없다");
    }

    // ------------------------------------------------------------------

    private static Set<String> lastCheck() {
        List<Path> files;
        try (Stream<Path> s = Files.list(MIGRATIONS)) {
            files = s.filter(p -> VERSION.matcher(p.getFileName().toString()).matches())
                    .sorted(Comparator.comparingInt(FlagTypeTableTest::version)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Set<String> last = null;
        for (Path p : files) {
            Matcher m = CHECK.matcher(read(p));
            while (m.find()) {
                last = new TreeSet<>();
                for (String v : m.group(1).split(",")) {
                    last.add(v.trim().replaceAll("^'|'$", ""));
                }
            }
        }
        if (last == null) {
            throw new IllegalStateException("no ck_compliance_flag_type CHECK in migrations");
        }
        return last;
    }

    private static int version(Path p) {
        Matcher m = VERSION.matcher(p.getFileName().toString());
        if (!m.matches()) {
            throw new IllegalStateException(p.toString());
        }
        return Integer.parseInt(m.group(1));
    }

    private static List<Path> ruleBundles() {
        return walk(p -> p.getFileName().toString().endsWith(".bundle.json"))
                .stream().filter(p -> Canonicalizer.parseStrict(read(p)).path("kind").asString().equals("RULE")).toList();
    }

    private static List<Path> mainSources() {
        return walk(p -> p.toString().endsWith(".java") && p.toString().contains("/src/main/java/"));
    }

    private static List<Path> walk(java.util.function.Predicate<Path> keep) {
        List<Path> out = new ArrayList<>();
        try {
            Files.walkFileTree(ROOT, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    return SKIP_DIRS.contains(dir.getFileName() == null ? "" : dir.getFileName().toString()) && !dir.equals(ROOT)
                            ? java.nio.file.FileVisitResult.SKIP_SUBTREE : java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (keep.test(file)) {
                        out.add(file);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static Set<String> names(JsonNode object) {
        Set<String> out = new TreeSet<>();
        object.propertyNames().forEach(out::add);
        return out;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
