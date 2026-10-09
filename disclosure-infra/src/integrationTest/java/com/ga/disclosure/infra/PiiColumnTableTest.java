package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G13(5 계획 §5.6, 승인 Q4): 설계서 §9 {@code pii-columns} 블록이 정본이고 세 파기 함수·폐기 함수(6B G7 — 네 번째)·DB 카탈로그가 따라간다.
 * <ul>
 *   <li>함수 → 블록: {@code pg_proc} 소스의 {@code UPDATE t SET c = NULL}(JSONB는 {@code = '{}'::jsonb}) 전부가 같은 함수 이름으로 블록에 있다. 블록 → 함수:
 *       그 반대.</li>
 *   <li>{@code PURGED} 행(6B — 보고 행을 룰 기간 뒤 행째 지우는 표)은 감사 표현이 없고, 그 표는 {@link #PURGE_TABLES}와 양방향으로 같으며, 앱 롤이 DELETE
 *       권한을 갖고 DELETE를 막는 트리거가 없다.</li>
 *   <li>카탈로그 → 블록: BYTEA 컬럼, {@code *_enc}·{@code wrapped_*} 이름, 암호문 형식 CHECK가 걸린 컬럼은 블록 또는 {@link #NON_PII}에 있다.
 *       {@code customer_ref} 가명 컬럼과 감사·아웃박스 JSON의 {@code customerRef} 키는 블록의 PSEUDONYM 행이다.</li>
 *   <li>블록의 모든 행은 실재 컬럼이고, {@link #NON_PII}에 폐기 항목이 남으면 실패한다.</li>
 *   <li>블록에서 어느 한 줄을 빼도 위 대조가 실패한다(행마다 확인 — 표의 모든 줄이 무언가에 묶여 있다).</li>
 * </ul>
 */
class PiiColumnTableTest {

    /** 개인정보가 아닌 BYTEA·키 이름 컬럼(닫힌 {@code table.column} 열거). */
    static final Set<String> NON_PII = Set.of("anchor_receipt.tsa_token");

    /** 개인정보 컬럼을 행째 지워 파기하는 표 → 그 정리 작업(닫힌 열거, 6B). 블록의 {@code PURGED} 행과 양방향으로 같다. */
    static final Map<String, String> PURGE_TABLES = Map.of("contract_link_unmatched", "CONTRACT_LINK_UNMATCHED_PURGE");

    static final Set<String> FUNCTIONS = Set.of("ga_document_key_shred", "ga_disclosure_destroy", "ga_customer_ref_destroy", "ga_draft_abandon");
    static final Set<String> KINDS = Set.of("ENCRYPTED", "EXTERNAL_ID", "KEY", "FREE_TEXT", "DEVICE", "NETWORK", "BEHAVIOR", "PSEUDONYM");
    static final Set<String> REPRS = Set.of("sha256-stored", "sha256-utf8", "sha256-jcs", "presence", "presence-family", "-");
    private static final Pattern UPDATE = Pattern.compile("UPDATE\\s+(\\w+)\\s+SET\\s+(.+?)\\s+WHERE", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NULLED = Pattern.compile("(\\w+)\\s*=\\s*(?:NULL\\b|'\\{\\}'::jsonb)", Pattern.CASE_INSENSITIVE);

    record Row(String table, String column, String kind, Set<String> erasedBy, String repr, String keeps) {
        String key() {
            return table + "." + column;
        }

        boolean retained() {
            return erasedBy.equals(Set.of("RETAINED"));
        }

        boolean purged() {
            return erasedBy.equals(Set.of("PURGED"));
        }
    }

    /** DB에서 읽은 것(블록과 무관). */
    record Catalog(Set<String> columns, Map<String, Set<String>> nulledByFunction, Set<String> encrypted, Set<String> pseudonymColumns,
                   Set<String> pseudonymJsonKeys, Set<String> appDeletableTables) {
    }

    private static WorkflowSetup w;
    private static Catalog catalog;

    @BeforeAll
    static void readCatalog() {
        w = new WorkflowSetup();
        w.draft();                                             // 감사·아웃박스에 customerRef 키를 남기는 실제 경로
        catalog = catalog(w.tenant.value());
    }

    @AfterAll
    static void close() {
        w.close();
    }

    static List<Row> block() {
        Path doc = Path.of(System.getProperty("ga.repoRoot"), "docs", "설계서.md");
        String text;
        try {
            text = Files.readString(doc, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int start = text.indexOf("```pii-columns\n");
        assertThat(start).as("설계서 §9 pii-columns 블록").isNotNegative();
        assertThat(text.indexOf("```pii-columns\n", start + 1)).as("블록은 하나").isNegative();
        String body = text.substring(start + "```pii-columns\n".length(), text.indexOf("```", start + 3));
        List<String> lines = body.lines().filter(l -> !l.isBlank()).toList();
        assertThat(lines.getFirst()).isEqualTo("table,column,kind,erasedBy,auditRepr,keeps");
        List<Row> rows = new ArrayList<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",", 6);
            assertThat(f).as(line).hasSize(6);
            rows.add(new Row(f[0], f[1], f[2], Set.of(f[3].split("\\|")), f[4], f[5]));
        }
        return rows;
    }

    @Test
    void theBlockIsWellFormed() {
        List<Row> rows = block();
        assertThat(rows).extracting(Row::key).doesNotHaveDuplicates();
        for (Row r : rows) {
            assertThat(KINDS).as(r.key()).contains(r.kind());
            assertThat(REPRS).as(r.key()).contains(r.repr());
            assertThat(r.keeps()).as(r.key()).isNotBlank();
            if (r.retained() || r.purged()) {
                assertThat(r.repr()).as(r.key() + " 남기는 행·행째 지우는 행은 감사 표현이 없다").isEqualTo("-");
            } else {
                assertThat(FUNCTIONS).as(r.key()).containsAll(r.erasedBy());
                assertThat(r.repr()).as(r.key()).isNotEqualTo("-");
            }
        }
    }

    @Test
    void theBlockAndTheDatabaseAgreeBothWays() {
        assertThat(violations(block(), catalog)).isEmpty();
        assertThat(catalog.nulledByFunction().keySet()).as("네 함수 모두 소스를 읽었다").isEqualTo(FUNCTIONS);
        assertThat(catalog.encrypted()).as("카탈로그 탐지가 무언가를 찾는다")
                .contains("customer_ref.name_enc", "document_key.wrapped_dek", "customer_data_key.wrapped_key", "anchor_receipt.tsa_token");
        assertThat(catalog.pseudonymJsonKeys()).contains("audit_log.detail.customerRef", "outbox_event.payload.customerRef");
    }

    /** 블록에서 어느 한 줄을 빼도 대조가 실패한다. */
    @Test
    void removingAnyLineFromTheBlockFails() {
        List<Row> rows = block();
        for (int i = 0; i < rows.size(); i++) {
            List<Row> without = new ArrayList<>(rows);
            Row removed = without.remove(i);
            assertThat(violations(without, catalog)).as("without " + removed.key()).isNotEmpty();
        }
    }

    @Test
    void aStaleNonPiiEntryOrAnUnknownFunctionColumnFails() {
        assertThat(violations(block(), catalog, Set.of("anchor_receipt.tsa_token", "gone_table.gone_column"))).anySatisfy(v -> assertThat(v)
                .contains("gone_table.gone_column"));
        Map<String, Set<String>> extra = new LinkedHashMap<>(catalog.nulledByFunction());
        extra.put("ga_disclosure_destroy", union(extra.get("ga_disclosure_destroy"), Set.of("signature.channel")));
        assertThat(violations(block(), new Catalog(catalog.columns(), extra, catalog.encrypted(), catalog.pseudonymColumns(),
                catalog.pseudonymJsonKeys(), catalog.appDeletableTables()))).anySatisfy(v -> assertThat(v).contains("signature.channel"));
    }

    static List<String> violations(List<Row> rows, Catalog c) {
        return violations(rows, c, NON_PII);
    }

    static List<String> violations(List<Row> rows, Catalog c, Set<String> nonPii) {
        List<String> out = new ArrayList<>();
        Map<String, Row> byKey = new LinkedHashMap<>();
        rows.forEach(r -> byKey.put(r.key(), r));
        for (String fn : FUNCTIONS) {
            Set<String> fromTable = new TreeSet<>();
            rows.stream().filter(r -> r.erasedBy().contains(fn)).forEach(r -> fromTable.add(r.key()));
            Set<String> fromFunction = c.nulledByFunction().getOrDefault(fn, Set.of());
            for (String k : fromFunction) {
                if (!fromTable.contains(k)) {
                    out.add(fn + " nulls " + k + " which the block does not list for it");
                }
            }
            for (String k : fromTable) {
                if (!fromFunction.contains(k)) {
                    out.add("block lists " + k + " for " + fn + " but the function does not null it");
                }
            }
        }
        for (String k : c.encrypted()) {
            if (!byKey.containsKey(k) && !nonPii.contains(k)) {
                out.add("encrypted or key column " + k + " is neither in the block nor in NON_PII");
            }
        }
        for (String k : nonPii) {
            if (!c.columns().contains(k)) {
                out.add("stale NON_PII entry " + k);
            }
            if (byKey.containsKey(k)) {
                out.add(k + " is both in the block and in NON_PII");
            }
        }
        for (String k : c.pseudonymColumns()) {
            Row r = byKey.get(k);
            if (r == null || !r.kind().equals("PSEUDONYM")) {
                out.add("pseudonym column " + k + " is not a PSEUDONYM row");
            }
        }
        for (String k : c.pseudonymJsonKeys()) {
            Row r = byKey.get(k);
            if (r == null || !r.kind().equals("PSEUDONYM")) {
                out.add("JSON key " + k + " is not a PSEUDONYM row");
            }
        }
        Set<String> purgedTables = new TreeSet<>();
        rows.stream().filter(Row::purged).forEach(r -> purgedTables.add(r.table()));
        if (!purgedTables.equals(new TreeSet<>(PURGE_TABLES.keySet()))) {
            out.add("PURGED rows " + purgedTables + " differ from the purge tables " + new TreeSet<>(PURGE_TABLES.keySet()));
        }
        for (String t : PURGE_TABLES.keySet()) {
            if (!c.appDeletableTables().contains(t)) {
                out.add("purge table " + t + " cannot be deleted from by the app role");
            }
        }
        for (Row r : rows) {
            String column = r.column().contains(".") ? r.table() + "." + r.column().substring(0, r.column().indexOf('.')) : r.key();
            if (!c.columns().contains(column)) {
                out.add("block row " + r.key() + " names no column");
            }
            boolean bound = c.encrypted().contains(r.key()) || c.pseudonymColumns().contains(r.key()) || c.pseudonymJsonKeys().contains(r.key())
                    || c.nulledByFunction().values().stream().anyMatch(s -> s.contains(r.key()))
                    || (r.purged() && c.appDeletableTables().contains(r.table()));
            if (!bound) {
                out.add("block row " + r.key() + " is bound to nothing in the database");
            }
        }
        return out;
    }

    private static Catalog catalog(String tenant) {
        PostgresHarness db = PostgresHarness.get();
        try (Connection c = db.superuserDataSource().getConnection()) {
            Set<String> columns = strings(c, """
                    SELECT c.table_name || '.' || c.column_name
                      FROM information_schema.columns c JOIN information_schema.tables t USING (table_schema, table_name)
                     WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE'
                    """);
            Set<String> encrypted = strings(c, """
                    SELECT c.table_name || '.' || c.column_name
                      FROM information_schema.columns c JOIN information_schema.tables t USING (table_schema, table_name)
                     WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE'
                       AND (c.data_type = 'bytea' OR c.column_name LIKE '%\\_enc' OR c.column_name LIKE 'wrapped\\_%')
                    UNION
                    SELECT r.relname || '.' || a.attname
                      FROM pg_constraint k
                      JOIN pg_class r ON r.oid = k.conrelid
                      JOIN pg_namespace n ON n.oid = r.relnamespace AND n.nspname = 'public'
                      JOIN pg_attribute a ON a.attrelid = r.oid AND a.attnum = ANY (k.conkey)
                     WHERE k.contype = 'c' AND (pg_get_constraintdef(k.oid) ILIKE '%get_byte(%' OR pg_get_constraintdef(k.oid) ILIKE '%octet_length(%')
                    """);
            Set<String> pseudonymColumns = strings(c, """
                    SELECT c.table_name || '.' || c.column_name
                      FROM information_schema.columns c JOIN information_schema.tables t USING (table_schema, table_name)
                     WHERE c.table_schema = 'public' AND t.table_type = 'BASE TABLE'
                       AND c.column_name = 'customer_ref' AND c.table_name <> 'customer_ref'
                    """);
            Set<String> jsonKeys = new TreeSet<>();
            try (var ps = c.prepareStatement("""
                    SELECT (SELECT count(*) FROM audit_log WHERE tenant_id = ? AND detail ? 'customerRef'),
                           (SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND payload ? 'customerRef')
                    """.replace("detail ? ", "detail ?? ").replace("payload ? ", "payload ?? "))) {
                ps.setString(1, tenant);
                ps.setString(2, tenant);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    if (rs.getLong(1) > 0) {
                        jsonKeys.add("audit_log.detail.customerRef");
                    }
                    if (rs.getLong(2) > 0) {
                        jsonKeys.add("outbox_event.payload.customerRef");
                    }
                }
            }
            Map<String, Set<String>> nulled = new LinkedHashMap<>();
            try (var ps = c.prepareStatement("SELECT proname, prosrc FROM pg_proc WHERE proname = ANY (?)")) {
                ps.setArray(1, c.createArrayOf("text", FUNCTIONS.toArray()));
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Set<String> cols = new TreeSet<>();
                        Matcher u = UPDATE.matcher(rs.getString(2));
                        while (u.find()) {
                            Matcher n = NULLED.matcher(u.group(2));
                            while (n.find()) {
                                cols.add(u.group(1) + "." + n.group(1));
                            }
                        }
                        assertThat(nulled.put(rs.getString(1), cols)).as("함수 이름은 하나씩").isNull();
                    }
                }
            }
            Set<String> deletable = strings(c, """
                    SELECT c.relname FROM pg_class c
                     WHERE c.relnamespace = 'public'::regnamespace AND c.relkind = 'r' AND has_table_privilege('disclosure_app', c.oid, 'DELETE')
                       AND NOT EXISTS (SELECT 1 FROM pg_trigger g WHERE g.tgrelid = c.oid AND NOT g.tgisinternal AND (g.tgtype & 8) <> 0)
                    """);
            return new Catalog(columns, nulled, encrypted, pseudonymColumns, jsonKeys, deletable);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Set<String> strings(Connection c, String sql) throws SQLException {
        Set<String> out = new TreeSet<>();
        try (var st = c.createStatement(); var rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new TreeSet<>(a);
        out.addAll(b);
        return out;
    }
}
