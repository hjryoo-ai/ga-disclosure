package com.ga.disclosure.architecture;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL 텍스트에서 테넌트 테이블 접근 문장을 찾아 {@code tenant_id} 조건(또는 컬럼 지정)이 있는지 검사한다.
 *
 * <p>SQL 파서를 쓰지 않는 보수적 스캐너다(PostgreSQL DDL·PL/pgSQL을 모두 이해하는 파서가 없다).
 * <ul>
 *   <li>주석({@code --}, {@code /* *\/})을 지우고, 문자열 리터럴은 빈 리터럴로 바꾼다(문자열 속 SQL은 동적 SQL이라 스캔 대상이 아니다).</li>
 *   <li>달러 인용 본문({@code $$ … $$}, {@code $tag$ … $tag$})은 꺼내서 재귀적으로 같은 규칙으로 검사한다(함수·DO 블록).</li>
 *   <li>최상위와 본문 모두 {@code ;} 단위로 나눈다. PL/pgSQL 조각은 첫 키워드가 IF·BEGIN일 수 있으므로
 *       문장 안 어디서든 DML 동사 + 알려진 테이블이 나오면 검사 대상이다.</li>
 * </ul>
 * 규칙
 * <ul>
 *   <li>CREATE TABLE t / CREATE INDEX … ON t: {@code tenant_id} 컬럼 포함.</li>
 *   <li>INSERT INTO t (…): 컬럼 목록에 {@code tenant_id}.</li>
 *   <li>SELECT … FROM/JOIN t, UPDATE t, DELETE FROM t: {@code tenant_id =} 또는 {@code tenant_id IN} 조건.</li>
 * </ul>
 * {@code tenant}(자기 행 조회는 RLS로 충분)과 {@code flyway_schema_history}는 제외한다.
 */
final class SqlTenantScanner {

    static final Set<String> EXCLUDED_TABLES = Set.of("tenant", "flyway_schema_history");

    private static final Pattern CREATE_TABLE = Pattern.compile("\\bcreate\\s+table\\s+(?:if\\s+not\\s+exists\\s+)?([a-z_][a-z0-9_]*)");
    private static final Pattern CREATE_INDEX = Pattern.compile("\\bcreate\\s+(?:unique\\s+)?index\\s+(?:if\\s+not\\s+exists\\s+)?[a-z0-9_]*\\s*on\\s+(?:only\\s+)?([a-z_][a-z0-9_]*)");
    private static final Pattern INSERT = Pattern.compile("\\binsert\\s+into\\s+([a-z_][a-z0-9_]*)\\s*(\\([^)]*\\))?");
    private static final Pattern UPDATE = Pattern.compile("\\bupdate\\s+(?:only\\s+)?([a-z_][a-z0-9_]*)\\s+(?:as\\s+)?(?:[a-z_][a-z0-9_]*\\s+)?set\\b");
    private static final Pattern DELETE = Pattern.compile("\\bdelete\\s+from\\s+(?:only\\s+)?([a-z_][a-z0-9_]*)");
    private static final Pattern FROM_JOIN = Pattern.compile("\\b(?:from|join)\\s+(?:only\\s+)?([a-z_][a-z0-9_]*)");
    private static final Pattern TENANT_COLUMN = Pattern.compile("\\btenant_id\\b");
    private static final Pattern TENANT_PREDICATE = Pattern.compile("\\btenant_id\\s*(?:=|\\bin\\b)|=\\s*[a-z0-9_.]*\\btenant_id\\b");
    private static final Pattern DOLLAR_TAG = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)?\\$");

    /** 발견한 위반. {@code statement}는 공백 정규화된 문장(허용 목록 키로도 쓴다). */
    record Violation(String source, String statement, String reason) {
        @Override
        public String toString() {
            return source + ": " + reason + "\n    " + statement;
        }
    }

    private SqlTenantScanner() {
    }

    /** 스크립트들에서 CREATE TABLE로 정의된 테이블 이름. */
    static Set<String> tablesDefinedIn(List<String> scripts) {
        Set<String> tables = new LinkedHashSet<>();
        for (String script : scripts) {
            for (String statement : statementsOf(script)) {
                Matcher m = CREATE_TABLE.matcher(statement);
                while (m.find()) {
                    tables.add(m.group(1));
                }
            }
        }
        return tables;
    }

    static List<Violation> scan(String source, String sql, Set<String> knownTables) {
        List<Violation> out = new ArrayList<>();
        for (String statement : statementsOf(sql)) {
            check(source, statement, knownTables, out);
        }
        return out;
    }

    /** 주석 제거·리터럴 비우기·달러 본문 분리 후 {@code ;} 단위 문장(소문자, 공백 정규화). 달러 본문의 문장도 포함한다. */
    static List<String> statementsOf(String sql) {
        List<String> bodies = new ArrayList<>();
        String flat = normalize(sql, bodies);
        List<String> out = new ArrayList<>();
        for (String piece : flat.split(";")) {
            String s = piece.strip().replaceAll("\\s+", " ");
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        for (String body : bodies) {
            out.addAll(statementsOf(body));
        }
        return out;
    }

    private static void check(String source, String statement, Set<String> knownTables, List<Violation> out) {
        Matcher createTable = CREATE_TABLE.matcher(statement);
        if (createTable.find()) {
            if (!EXCLUDED_TABLES.contains(createTable.group(1)) && !TENANT_COLUMN.matcher(statement).find()) {
                out.add(new Violation(source, statement, "CREATE TABLE " + createTable.group(1) + " has no tenant_id column"));
            }
            return;
        }
        Matcher createIndex = CREATE_INDEX.matcher(statement);
        if (createIndex.find()) {
            if (isTenantTable(createIndex.group(1), knownTables) && !TENANT_COLUMN.matcher(statement).find()) {
                out.add(new Violation(source, statement, "index on " + createIndex.group(1) + " does not include tenant_id"));
            }
            return;
        }
        Matcher insert = INSERT.matcher(statement);
        while (insert.find()) {
            if (isTenantTable(insert.group(1), knownTables)
                    && (insert.group(2) == null || !TENANT_COLUMN.matcher(insert.group(2)).find())) {
                out.add(new Violation(source, statement, "INSERT INTO " + insert.group(1) + " does not name tenant_id column"));
            }
        }
        Set<String> filtered = new LinkedHashSet<>();
        for (Pattern p : List.of(UPDATE, DELETE, FROM_JOIN)) {
            Matcher m = p.matcher(statement);
            while (m.find()) {
                if (isTenantTable(m.group(1), knownTables)) {
                    filtered.add(m.group(1));
                }
            }
        }
        if (!filtered.isEmpty() && !TENANT_PREDICATE.matcher(statement).find()) {
            out.add(new Violation(source, statement, "access to " + filtered + " without tenant_id predicate"));
        }
    }

    private static boolean isTenantTable(String table, Set<String> knownTables) {
        return knownTables.contains(table) && !EXCLUDED_TABLES.contains(table);
    }

    /**
     * 주석을 지우고, 작은따옴표 리터럴을 {@code ''}로, 큰따옴표 식별자는 그대로 두고, 달러 인용 본문은 {@code bodies}로 빼낸 뒤
     * 소문자로 돌려준다.
     */
    private static String normalize(String sql, List<String> bodies) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (c == '\'') {
                i++;
                while (i < n) {
                    if (sql.charAt(i) == '\'' && i + 1 < n && sql.charAt(i + 1) == '\'') {
                        i += 2;
                    } else if (sql.charAt(i) == '\'') {
                        i++;
                        break;
                    } else {
                        i++;
                    }
                }
                out.append("''");
            } else if (c == '$') {
                Matcher tag = DOLLAR_TAG.matcher(sql).region(i, n);
                if (tag.lookingAt()) {
                    String delimiter = tag.group();
                    int bodyStart = i + delimiter.length();
                    int end = sql.indexOf(delimiter, bodyStart);
                    if (end < 0) {
                        end = n;
                    }
                    bodies.add(sql.substring(bodyStart, end));
                    out.append(" $body$ ");
                    i = Math.min(n, end + delimiter.length());
                } else {
                    out.append(c);
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** 자바 소스에서 SQL로 보이는 문자열 리터럴·텍스트 블록(SELECT/INSERT/UPDATE/DELETE/WITH/MERGE로 시작)을 뽑는다. */
    static List<String> sqlLiteralsInJava(String java) {
        List<String> literals = new ArrayList<>();
        int i = 0;
        int n = java.length();
        while (i < n) {
            char c = java.charAt(i);
            if (c == '/' && i + 1 < n && java.charAt(i + 1) == '/') {
                while (i < n && java.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && java.charAt(i + 1) == '*') {
                int end = java.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (java.startsWith("\"\"\"", i)) {
                int end = java.indexOf("\"\"\"", i + 3);
                if (end < 0) {
                    end = n;
                }
                literals.add(java.substring(i + 3, end));
                i = Math.min(n, end + 3);
            } else if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < n && java.charAt(i) != '"') {
                    if (java.charAt(i) == '\\' && i + 1 < n) {
                        sb.append(java.charAt(i + 1));
                        i += 2;
                    } else {
                        sb.append(java.charAt(i++));
                    }
                }
                i++;
                literals.add(sb.toString());
            } else if (c == '\'') {
                // 문자 리터럴('"' 포함)을 건너뛴다.
                int end = java.indexOf('\'', i + (i + 1 < n && java.charAt(i + 1) == '\\' ? 3 : 2));
                i = end < 0 ? n : end + 1;
            } else {
                i++;
            }
        }
        Pattern sqlStart = Pattern.compile("(?is)^\\s*(select|insert|update|delete|with|merge)\\b.*");
        return literals.stream().filter(l -> sqlStart.matcher(l).matches()).toList();
    }
}
