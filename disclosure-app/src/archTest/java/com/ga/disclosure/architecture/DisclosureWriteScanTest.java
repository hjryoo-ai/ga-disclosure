package com.ga.disclosure.architecture;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3A: 확인서 상태·본문은 애그리게이트 → 저장소 {@code save}로만 바뀐다(지시문 "애그리게이트 밖에서 상태 컬럼을 바꾸는 저장소 메서드 금지").
 * 전 모듈 운영 소스의 SQL 문자열을 <b>들어 있는 메서드</b>와 함께 찾아, {@code disclosure}·{@code disclosure_item}·{@code recommendation}을
 * 쓰는 문장이 아래 허용 목록(FQN#메서드 열거, 사유 포함)의 메서드에만 있는지 검사한다. 허용 목록의 항목이 실제로 쓰이지 않으면(폐기 항목) 실패한다.
 * 거짓 양성이 나면 목록을 넓히지 않고 SQL을 허용 메서드로 옮긴다(CLAUDE.md 작업 방식).
 *
 * <p>Phase 4(4 계획 §10 7항): 같은 방식으로 서명 세션({@code sign_session} — 발급 INSERT·가변 컬럼 UPDATE), 서명({@code signature} — append-only
 * INSERT), 아웃박스({@code outbox_event}·{@code outbox_head} — 적재 한 메서드)를 쓰는 문장도 허용 메서드에만 있어야 한다. DELETE는 어느 것도 없다.
 */
class DisclosureWriteScanTest {

    /** 문장 종류 → 정규식(소문자·공백 정규화한 SQL 문자열에 적용). */
    static final Map<String, Pattern> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("UPDATE disclosure", Pattern.compile("\\bupdate\\s+(?:only\\s+)?disclosure\\b(?!_)"));
        KINDS.put("INSERT disclosure", Pattern.compile("\\binsert\\s+into\\s+disclosure\\b(?!_)"));
        KINDS.put("DELETE disclosure", Pattern.compile("\\bdelete\\s+from\\s+(?:only\\s+)?disclosure\\b(?!_)"));
        KINDS.put("UPDATE child", Pattern.compile("\\bupdate\\s+(?:only\\s+)?(?:disclosure_item|recommendation)\\b"));
        KINDS.put("INSERT child", Pattern.compile("\\binsert\\s+into\\s+(?:disclosure_item|recommendation)\\b"));
        KINDS.put("DELETE child", Pattern.compile("\\bdelete\\s+from\\s+(?:only\\s+)?(?:disclosure_item|recommendation)\\b"));
        for (String table : List.of("sign_session", "signature", "outbox_event", "outbox_head")) {
            KINDS.put("INSERT " + table, Pattern.compile("\\binsert\\s+into\\s+" + table + "\\b(?!_)"));
            KINDS.put("UPDATE " + table, Pattern.compile("\\bupdate\\s+(?:only\\s+)?" + table + "\\b(?!_)"));
            KINDS.put("DELETE " + table, Pattern.compile("\\bdelete\\s+from\\s+(?:only\\s+)?" + table + "\\b(?!_)"));
        }
    }

    private static final String REPO = "com.ga.disclosure.infra.persistence.DisclosureRepository";
    private static final String SESSIONS = "com.ga.disclosure.infra.persistence.SignSessionRepository";
    private static final String SIGNATURES = "com.ga.disclosure.infra.persistence.SignatureRepository";
    private static final String OUTBOX = "com.ga.disclosure.infra.outbox.OutboxRepository";

    /** 허용 목록: FQN#메서드 → 허용 문장 종류(사유). */
    static final Map<String, Set<String>> ALLOWED = Map.of(
            REPO + "#insert", Set.of("INSERT disclosure"),          // 새 초안 헤더(DRAFT) — 상태를 정하는 유일한 INSERT
            REPO + "#save", Set.of("UPDATE disclosure", "DELETE child"),  // 애그리게이트 상태 저장: 헤더 상태·스냅샷, 자식 전부 교체
            REPO + "#writeChildren", Set.of("INSERT child"),         // insert·save가 부르는 자식 행 쓰기
            SESSIONS + "#insert", Set.of("INSERT sign_session"),     // 세션 발급(OPEN, 두 해시 고정 — GD101)
            SESSIONS + "#update", Set.of("UPDATE sign_session"),     // 상태표를 거친 가변 컬럼만(GD101이 다시 지킨다)
            SIGNATURES + "#insert", Set.of("INSERT signature"),      // 서명 1건(append-only, GD021·022·102~104)
            OUTBOX + "#append", Set.of("INSERT outbox_event", "INSERT outbox_head", "UPDATE outbox_head"));  // 갭 없는 seq 적재(GD106)

    private static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));

    record Found(String method, String kind, String sql) {
    }

    private static List<Found> found;

    @BeforeAll
    static void scan() throws IOException {
        List<Found> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> f.toString().contains("/src/main/java/"))
                    .filter(f -> !f.toString().contains("/build/"))
                    .sorted().toList()) {
                String fqn = ROOT.relativize(p).toString().replaceAll("^.*/src/main/java/", "").replace(".java", "").replace('/', '.');
                out.addAll(scanSource(fqn, Files.readString(p, StandardCharsets.UTF_8)));
            }
        }
        found = out;
    }

    /** 자바 소스의 SQL 문자열 → (클래스#메서드, 종류). 메서드 밖(필드 등)의 문자열은 {@code #<class>}로 적는다. */
    static List<Found> scanSource(String fqn, String java) {
        List<Found> out = new ArrayList<>();
        for (Block b : methodBlocks(java)) {
            for (String literal : SqlTenantScanner.sqlLiteralsInJava(java.substring(b.start(), b.end()))) {
                String sql = literal.toLowerCase().replaceAll("\\s+", " ");
                KINDS.forEach((kind, pattern) -> {
                    if (pattern.matcher(sql).find()) {
                        out.add(new Found(fqn + "#" + b.name(), kind, sql.strip()));
                    }
                });
            }
        }
        return out;
    }

    record Block(String name, int start, int end) {
    }

    private static final Pattern METHOD_HEADER = Pattern.compile("(\\w+)\\s*\\([^()]*(?:\\([^()]*\\)[^()]*)*\\)\\s*(?:throws\\s+[\\w.,\\s]+)?$");
    private static final Set<String> NOT_METHODS = Set.of("if", "for", "while", "switch", "catch", "synchronized", "try", "return", "new");

    /**
     * 이름 있는 최외곽 메서드 블록들(람다·제어문·중첩 블록은 감싼 메서드에 속한다). 문자열·주석·텍스트 블록 안의 중괄호는 무시한다.
     * 메서드가 아닌 영역(클래스 본문)은 이름 {@code <class>}로 전체 범위를 덮는 블록 하나로 돌려준다.
     */
    static List<Block> methodBlocks(String java) {
        List<Block> blocks = new ArrayList<>();
        Deque<String> names = new ArrayDeque<>();
        Deque<Integer> starts = new ArrayDeque<>();
        StringBuilder code = new StringBuilder();   // 마지막 ; { } 뒤의 코드(메서드 머리 판별용)
        int n = java.length();
        int i = 0;
        List<int[]> named = new ArrayList<>();
        while (i < n) {
            char c = java.charAt(i);
            if (java.startsWith("//", i)) {
                i = Math.max(i + 1, java.indexOf('\n', i));
                if (i < 0) {
                    break;
                }
                continue;
            }
            if (java.startsWith("/*", i)) {
                int end = java.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (java.startsWith("\"\"\"", i)) {
                int end = java.indexOf("\"\"\"", i + 3);
                i = end < 0 ? n : end + 3;
                code.append("\"\"");
                continue;
            }
            if (c == '"') {
                i++;
                while (i < n && java.charAt(i) != '"') {
                    i += java.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                code.append("\"\"");
                continue;
            }
            if (c == '\'') {
                int end = java.indexOf('\'', i + (i + 1 < n && java.charAt(i + 1) == '\\' ? 3 : 2));
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == '{') {
                Matcher m = METHOD_HEADER.matcher(code.toString().strip());
                String name = m.find() && !NOT_METHODS.contains(m.group(1)) && !code.toString().contains("->") ? m.group(1) : null;
                names.push(name == null ? "" : name);
                starts.push(i);
                code.setLength(0);
            } else if (c == '}') {
                String name = names.isEmpty() ? "" : names.pop();
                int start = starts.isEmpty() ? 0 : starts.pop();
                if (!name.isEmpty() && names.stream().noneMatch(x -> !x.isEmpty())) {
                    named.add(new int[] {start, i + 1});
                    blocks.add(new Block(name, start, i + 1));
                }
                code.setLength(0);
            } else if (c == ';') {
                code.setLength(0);
            } else {
                code.append(c);
            }
            i++;
        }
        // 메서드 밖 영역: 전체에서 메서드 범위를 뺀 부분을 <class> 블록들로
        int cursor = 0;
        named.sort((a, b) -> Integer.compare(a[0], b[0]));
        for (int[] r : named) {
            if (r[0] > cursor) {
                blocks.add(new Block("<class>", cursor, r[0]));
            }
            cursor = Math.max(cursor, r[1]);
        }
        if (cursor < n) {
            blocks.add(new Block("<class>", cursor, n));
        }
        return blocks;
    }

    @Test
    void scanFindsTheRepositoryWrites() {
        assertThat(found).extracting(Found::method).contains(REPO + "#insert", REPO + "#save", REPO + "#writeChildren");
    }

    @Test
    void disclosureTablesAreWrittenOnlyByTheAllowedRepositoryMethods() {
        List<String> violations = found.stream()
                .filter(f -> !ALLOWED.getOrDefault(f.method(), Set.of()).contains(f.kind()))
                .map(f -> f.method() + " — " + f.kind() + ": " + f.sql())
                .toList();
        assertThat(violations).as("확인서·서명·아웃박스 테이블을 쓰는 SQL은 허용 메서드에만").isEmpty();
    }

    @Test
    void allowlistHasNoStaleEntries() {
        Set<String> used = new LinkedHashSet<>();
        found.forEach(f -> used.add(f.method() + "|" + f.kind()));
        ALLOWED.forEach((method, kinds) -> kinds.forEach(kind ->
                assertThat(used).as("쓰이지 않는 허용 항목(제거할 것): %s %s", method, kind).contains(method + "|" + kind)));
    }

    // ------------------------------------------------------------------ 스캐너 자체의 음성·양성 테스트(영구 보존)

    @Test
    void scannerAttributesSqlToItsEnclosingMethod() {
        String java = """
                class R {
                    static final String F = "UPDATE disclosure SET status = 'X' WHERE tenant_id = :tenantId";
                    void save(Object d) {
                        run(() -> { update(\"""
                                UPDATE disclosure SET status = :s WHERE tenant_id = :tenantId
                                \"""); });
                        if (d != null) { update("DELETE FROM disclosure_item WHERE tenant_id = :tenantId"); }
                    }
                    void markSealed(String id) {
                        // "UPDATE disclosure" in a comment is ignored
                        update("update  disclosure set status = 'SEALED' where tenant_id = :tenantId");
                        char q = '"';
                        update("INSERT INTO recommendation (tenant_id) VALUES (:tenantId)");
                    }
                    void read() { query("SELECT status FROM disclosure WHERE tenant_id = :tenantId"); }
                }
                """;
        assertThat(scanSource("x.R", java)).extracting(f -> f.method() + " " + f.kind()).containsExactlyInAnyOrder(
                "x.R#<class> UPDATE disclosure",
                "x.R#save UPDATE disclosure",
                "x.R#save DELETE child",
                "x.R#markSealed UPDATE disclosure",
                "x.R#markSealed INSERT child");
    }

    static String read(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
