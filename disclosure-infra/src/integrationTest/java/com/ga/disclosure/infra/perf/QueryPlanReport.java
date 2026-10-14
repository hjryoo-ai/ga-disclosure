package com.ga.disclosure.infra.perf;

import com.ga.disclosure.infra.testing.PostgresHarness;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 질의 계획 실측({@code ./gradlew :disclosure-infra:queryPlanReport}, 수동 — Phase 8 계획 ③-x-2). 별도 데이터베이스를 V23까지 올려 허구 데이터
 * ({@link PerfData})를 만들고 {@link MeasuredQueries}를 앱 롤·테넌트 바인딩으로 {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)} 7회(첫 2회 버림, 중앙값)
 * 잰 뒤, 최신 마이그레이션을 적용·ANALYZE하고 같은 데이터로 다시 잰다. 결과는 표(markdown)와 JSON — 실행 시간은 이 기계의 값이고(보고서에 환경을 적는다),
 * 판단 근거는 계획의 모양(순차 스캔·정렬 노드·읽은 행)이다.
 */
public final class QueryPlanReport {

    static final String TENANT = "PERF1";
    static final String NOISE = "PERF2";
    static final String BEFORE = "23";
    static final JsonMapper JSON = JsonMapper.builder().build();

    private QueryPlanReport() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        PostgresHarness db = PostgresHarness.get();
        String name = "perf_" + Long.toHexString(System.nanoTime());
        DataSource migrator = db.emptyDatabase(name);
        PostgresHarness.migrate(migrator, BEFORE);
        DataSource superuser = db.superuserDataSource(name);
        DataSource app = db.appDataSource(name);

        long t0 = System.nanoTime();
        PerfData.tenant(migrator, TENANT);
        PerfData.tenant(migrator, NOISE);
        List<UUID> disclosures = PerfData.disclosures(superuser, TENANT, 100_000, 8_001L);
        List<UUID> noise = PerfData.disclosures(superuser, NOISE, 20_000, 8_002L);
        PerfData.flags(superuser, TENANT, disclosures, 30_000, 8_003L);
        PerfData.flags(superuser, NOISE, noise, 5_000, 8_004L);
        long t1 = System.nanoTime();
        PerfData.audit(app, TENANT, disclosures, 100_000, 8_005L);
        long t2 = System.nanoTime();
        PerfData.analyze(superuser);
        String target = disclosures.get(12_345).toString();

        Map<String, ObjectNode> before = measureAll(app, target);
        PostgresHarness.migrate(migrator, "latest");
        PerfData.analyze(superuser);
        Map<String, ObjectNode> after = measureAll(app, target);
        String applied = scalar(superuser, "SELECT max(version::int)::text FROM flyway_schema_history WHERE success");

        ObjectNode report = JSON.createObjectNode();
        report.put("before", "V" + BEFORE).put("after", "V" + applied).put("environment", environment(superuser))
                .put("generateDisclosuresAndFlagsSeconds", (t1 - t0) / 1_000_000_000L).put("generateAuditRealPathSeconds", (t2 - t1) / 1_000_000_000L);
        ObjectNode counts = report.putObject("rows");
        for (String table : List.of("disclosure", "compliance_flag", "audit_log")) {
            counts.put(table, Long.parseLong(scalar(superuser, "SELECT count(*)::text FROM " + table)));
        }
        ArrayNode rows = report.putArray("queries");
        StringBuilder md = new StringBuilder("# 질의 계획 실측 (Phase 8 ③-x-2)\n\n")
                .append("- 환경: ").append(report.get("environment").asString()).append('\n')
                .append("- 데이터: ").append(counts).append(" — 테넌트 ").append(TENANT).append("(확인서 10만·플래그 3만·감사 10만, 감사는 실제 경로 ")
                .append(report.get("generateAuditRealPathSeconds").asLong()).append("초), 잡음 테넌트 ").append(NOISE).append("(확인서 2만·플래그 5천)\n")
                .append("- 전: V").append(BEFORE).append(", 후: V").append(applied).append(" — 같은 데이터, 각 7회 실행 중 뒤 5회의 중앙값(ms)\n\n")
                .append("| 질의 | 출처 | 전 ms | 전 계획 | 후 ms | 후 계획 |\n|---|---|---:|---|---:|---|\n");
        for (MeasuredQueries.Query q : MeasuredQueries.all(target)) {
            ObjectNode b = before.get(q.id());
            ObjectNode a = after.get(q.id());
            rows.addObject().put("id", q.id()).put("source", q.source()).set("before", b);
            ((ObjectNode) rows.get(rows.size() - 1)).set("after", a);
            md.append("| ").append(q.id()).append(" | `").append(q.source()).append("` | ").append(b.get("medianMs").asString()).append(" | ")
                    .append(b.get("shape").asString()).append(" | ").append(a.get("medianMs").asString()).append(" | ").append(a.get("shape").asString())
                    .append(" |\n");
        }
        Files.writeString(out.resolve("query-plans.md"), md.toString(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("query-plans.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n", StandardCharsets.UTF_8);
        System.out.println(md);
    }

    static Map<String, ObjectNode> measureAll(DataSource app, String target) throws SQLException {
        Map<String, ObjectNode> out = new LinkedHashMap<>();
        for (MeasuredQueries.Query q : MeasuredQueries.all(target)) {
            double[] ms = new double[5];
            JsonNode last = null;
            for (int i = 0; i < 7; i++) {
                JsonNode plan = explain(app, q);
                if (i >= 2) {
                    ms[i - 2] = plan.get("Execution Time").asDouble();
                }
                last = plan;
            }
            Arrays.sort(ms);
            ObjectNode m = JSON.createObjectNode();
            m.put("medianMs", String.format(java.util.Locale.ROOT, "%.2f", ms[2]));
            m.put("shape", shape(last.get("Plan")));
            m.put("sharedBlocks", last.get("Plan").path("Shared Hit Blocks").asLong() + last.get("Plan").path("Shared Read Blocks").asLong());
            m.set("plan", last.get("Plan"));
            out.put(q.id(), m);
        }
        return out;
    }

    /** 앱 롤·테넌트 바인딩(RLS)으로 실행 계획 하나. */
    static JsonNode explain(DataSource app, MeasuredQueries.Query q) throws SQLException {
        try (Connection c = app.getConnection()) {
            c.setAutoCommit(false);
            try {
                PostgresHarness.setTenant(c, TENANT);
                try (PreparedStatement ps = c.prepareStatement("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + q.sql())) {
                    for (int i = 1; i <= MeasuredQueries.tenantParams(q); i++) {
                        ps.setString(i, TENANT);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        return JSON.readTree(rs.getString(1)).get(0);
                    }
                }
            } finally {
                c.rollback();
            }
        }
    }

    /** 계획의 모양: 노드 종류(인덱스 이름) 를 깊이 우선으로, 읽은 행이 큰 노드는 행 수를 붙인다. */
    static String shape(JsonNode node) {
        List<String> parts = new ArrayList<>();
        walk(node, parts);
        return String.join(" → ", parts);
    }

    private static void walk(JsonNode n, List<String> parts) {
        StringBuilder s = new StringBuilder(n.get("Node Type").asString());
        if (n.has("Index Name")) {
            s.append('(').append(n.get("Index Name").asString()).append(')');
        }
        long rows = n.path("Actual Rows").asLong() * Math.max(1, n.path("Actual Loops").asLong());
        long removed = n.path("Rows Removed by Filter").asLong();
        if (rows + removed >= 1_000) {
            s.append(" [").append(rows + removed).append(" rows]");
        }
        parts.add(s.toString());
        for (JsonNode child : n.path("Plans")) {
            walk(child, parts);
        }
    }

    static String scalar(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static String environment(DataSource superuser) {
        return scalar(superuser, "SELECT version()").replaceAll(",.*", "") + "; " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + "; Java " + System.getProperty("java.version") + "; " + Runtime.getRuntime().availableProcessors() + " CPUs";
    }
}
