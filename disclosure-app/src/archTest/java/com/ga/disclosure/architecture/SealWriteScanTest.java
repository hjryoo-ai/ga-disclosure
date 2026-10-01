package com.ga.disclosure.architecture;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3B(계획 §4 "가드와 쓰기 경로"): 채번 카운터·봉인 체인 머리·문서 키·산출물 테이블을 쓰는 SQL은 각 저장소의 정해진 메서드에만 있다. 전 모듈 운영
 * 소스를 {@link DisclosureWriteScanTest}의 스캐너로 훑는다. 허용 목록 항목이 쓰이지 않으면(폐기) 실패한다. 거짓 양성이면 목록을 넓히지 않고 SQL을
 * 옮긴다. DELETE는 어디에도 없다(키 파기는 DB 함수 {@code ga_shred_document_key}, 행 삭제는 트리거가 거부).
 */
class SealWriteScanTest {

    static final Map<String, Pattern> KINDS = new LinkedHashMap<>();

    static {
        for (String table : List.of("disclosure_counter", "disclosure_chain_head", "document_key", "document_artifact")) {
            KINDS.put("INSERT " + table, Pattern.compile("\\binsert\\s+into\\s+" + table + "\\b"));
            KINDS.put("UPDATE " + table, Pattern.compile("\\bupdate\\s+(?:only\\s+)?" + table + "\\b"));
            KINDS.put("DELETE " + table, Pattern.compile("\\bdelete\\s+from\\s+(?:only\\s+)?" + table + "\\b"));
        }
    }

    private static final String LEDGER = "com.ga.disclosure.infra.persistence.SealLedgerRepository";
    private static final String RECORDS = "com.ga.disclosure.infra.persistence.DocumentRecordRepository";

    /** 허용 목록: FQN#메서드 → 허용 문장 종류. */
    static final Map<String, Set<String>> ALLOWED = Map.of(
            LEDGER + "#issueNumber", Set.of("INSERT disclosure_counter"),                 // INSERT … ON CONFLICT DO UPDATE(+1) RETURNING
            LEDGER + "#advanceChainHead", Set.of("INSERT disclosure_chain_head", "UPDATE disclosure_chain_head"),
            RECORDS + "#insertKey", Set.of("INSERT document_key"),
            RECORDS + "#insertArtifact", Set.of("INSERT document_artifact"),
            RECORDS + "#markRetentionApplied", Set.of("UPDATE document_artifact"));      // retention_applied_at NULL → 값 1회

    private static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));

    private static List<DisclosureWriteScanTest.Found> found;

    @BeforeAll
    static void scan() throws IOException {
        List<DisclosureWriteScanTest.Found> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> f.toString().contains("/src/main/java/"))
                    .filter(f -> !f.toString().contains("/build/"))
                    .sorted().toList()) {
                String fqn = ROOT.relativize(p).toString().replaceAll("^.*/src/main/java/", "").replace(".java", "").replace('/', '.');
                String java = Files.readString(p, StandardCharsets.UTF_8);
                for (DisclosureWriteScanTest.Block b : DisclosureWriteScanTest.methodBlocks(java)) {
                    for (String literal : SqlTenantScanner.sqlLiteralsInJava(java.substring(b.start(), b.end()))) {
                        String sql = literal.toLowerCase().replaceAll("\\s+", " ");
                        KINDS.forEach((kind, pattern) -> {
                            if (pattern.matcher(sql).find()) {
                                out.add(new DisclosureWriteScanTest.Found(fqn + "#" + b.name(), kind, sql.strip()));
                            }
                        });
                    }
                }
            }
        }
        found = out;
    }

    @Test
    void sealTablesAreWrittenOnlyByTheirRepositoryMethods() {
        List<String> violations = found.stream()
                .filter(f -> !ALLOWED.getOrDefault(f.method(), Set.of()).contains(f.kind()))
                .map(f -> f.method() + " — " + f.kind() + ": " + f.sql()).toList();
        assertThat(violations).as("봉인 테이블을 쓰는 SQL은 허용 메서드에만").isEmpty();
    }

    @Test
    void everyAllowlistEntryIsUsed() {
        ALLOWED.forEach((method, kinds) -> kinds.forEach(kind -> assertThat(found).as("쓰이지 않는 허용 항목: %s %s", method, kind)
                .anyMatch(f -> f.method().equals(method) && f.kind().equals(kind))));
        assertThat(found).noneMatch(f -> f.kind().startsWith("DELETE"));
    }
}
