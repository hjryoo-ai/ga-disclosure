package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설계서 §6.10의 {@code job-states} 블록(정본)과 V12 가드(GD121)를 양방향으로 대조한다 — 앱 롤로, 시도마다 롤백되는 트랜잭션에서:
 * <ul>
 *   <li>표의 전이는 {@code sets} 컬럼만 바꿔서 통과한다.</li>
 *   <li>표의 전이라도 {@code sets} 밖의 가변 컬럼을 함께 바꾸면 GD121이다.</li>
 *   <li>표에 없는 상태 쌍(종단 상태에서 나가는 것 포함)은 GD121이다.</li>
 *   <li>INSERT는 QUEUED만(표의 {@code -} 행).</li>
 * </ul>
 */
class JobStateTableTest {

    static final PostgresHarness DB = PostgresHarness.get();
    static final Path DESIGN = Path.of(System.getProperty("ga.repoRoot"), "docs", "설계서.md");
    static final Pattern BLOCK = Pattern.compile("```job-states\\n(.*?)\\n```", Pattern.DOTALL);
    static final List<String> STATUSES = List.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED");
    /** 상태 외에 전이가 바꿀 수 있는 컬럼과 시험 값(SQL 식 — {@code t}·{@code j}는 테넌트·작업 ID 자리). */
    static final Map<String, String> MUTABLE = new LinkedHashMap<>();

    static {
        MUTABLE.put("started_at", "requested_at + interval '1 minute'");
        MUTABLE.put("finished_at", "requested_at + interval '2 minutes'");
        MUTABLE.put("result_ref", "tenant_id || '/reports/' || job_id");
        MUTABLE.put("report_sha256", "repeat('a', 64)");
        MUTABLE.put("report_key_wrapped", "'\\x01'::bytea");
        MUTABLE.put("report_kek_id", "'KEK-TEST'");
        MUTABLE.put("error_code", "'INTERRUPTED'");
    }

    /** "함께 바꾸기" 시도의 값 — 행에 이미 있는 값과 달라야 실제 변경이다(같은 값은 변경이 아니다). */
    static final Map<String, String> OTHER = Map.of(
            "started_at", "requested_at + interval '5 minutes'",
            "finished_at", "requested_at + interval '6 minutes'",
            "result_ref", "tenant_id || '/reports/' || job_id",
            "report_sha256", "repeat('b', 64)",
            "report_key_wrapped", "'\\x02'::bytea",
            "report_kek_id", "'KEK-OTHER'",
            "error_code", "'LOCK_LOST'");

    record Transition(String from, String to, Set<String> sets) {
    }

    static List<Transition> table;

    /** 시험 행 하나(활성 행은 테넌트·잠금 키마다 하나뿐이라 — {@code ux_async_job_active} — 행마다 새 테넌트). */
    record Row(String tenant, UUID id) {
    }

    @BeforeAll
    static void parse() throws IOException {
        Matcher m = BLOCK.matcher(Files.readString(DESIGN, StandardCharsets.UTF_8));
        assertThat(m.find()).as("설계서에 job-states 블록이 있다").isTrue();
        String[] lines = m.group(1).split("\n");
        assertThat(lines[0]).isEqualTo("from,to,trigger,sets");
        table = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split(",", -1);
            assertThat(cells).as(lines[i]).hasSize(4);
            table.add(new Transition(cells[0], cells[1], new LinkedHashSet<>(List.of(cells[3].split("·")))));
        }
    }

    /** {@code from} 상태의 행을 소유 롤로 만든다(표의 경로를 따라 — 가드를 지난다). */
    static Row rowIn(String status) {
        UUID id = UUID.randomUUID();
        String tenant = SeedData.uniqueTenant("JST");
        DB.seed(tenant, c -> {
            SeedData.tenant(c, tenant);
            SeedData.exec(c, """
                    INSERT INTO async_job (tenant_id, job_id, kind, status, requested_by, channel, requested_at)
                    VALUES (?, ?, 'EXPIRE', 'QUEUED', 'jst@test', 'CLI', TIMESTAMPTZ '2026-09-23 10:00:00+09')
                    """, tenant, id);
            if (status.equals("QUEUED")) {
                return;
            }
            if (status.equals("FAILED")) {
                apply(c, tenant, id, "FAILED", Set.of("finished_at", "error_code"));
                return;
            }
            apply(c, tenant, id, "RUNNING", Set.of("started_at"));
            if (status.equals("SUCCEEDED")) {
                apply(c, tenant, id, "SUCCEEDED", Set.of("finished_at", "result_ref", "report_sha256", "report_key_wrapped", "report_kek_id"));
            }
        });
        return new Row(tenant, id);
    }

    static void apply(Connection c, String tenant, UUID id, String to, Set<String> columns) throws SQLException {
        apply(c, tenant, id, to, columns, null);
    }

    static void apply(Connection c, String tenant, UUID id, String to, Set<String> columns, String extraOrNull) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE async_job SET status = '" + to + "'");
        for (String col : columns) {
            sql.append(", ").append(col).append(" = ").append(MUTABLE.get(col));
        }
        if (extraOrNull != null) {
            sql.append(", ").append(extraOrNull).append(" = ").append(OTHER.get(extraOrNull));
        }
        sql.append(" WHERE tenant_id = ? AND job_id = ?");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            ps.setString(1, tenant);
            ps.setObject(2, id);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
    }

    /** 앱 롤로 시도하고 롤백한다. 통과면 null, 아니면 SQLSTATE. */
    static String attempt(Row row, String to, Set<String> columns) {
        return attempt(row, to, columns, null);
    }

    static String attempt(Row row, String to, Set<String> columns, String extraOrNull) {
        return DB.asApp(row.tenant(), c -> {
            try {
                apply(c, row.tenant(), row.id(), to, columns, extraOrNull);
                return null;
            } catch (SQLException e) {
                return e.getSQLState();
            }
        });
    }

    @Test
    void theTableIsTheGuardsAllowList() {
        assertThat(table).extracting(Transition::from).contains("-");
        for (String from : STATUSES) {
            for (String to : STATUSES) {
                if (from.equals(to)) {
                    continue;
                }
                Transition t = table.stream().filter(x -> x.from().equals(from) && x.to().equals(to)).findFirst().orElse(null);
                if (t == null) {
                    Set<String> any = table.stream().filter(x -> x.to().equals(to)).findFirst().map(Transition::sets).orElse(Set.of());
                    assertThat(attempt(rowIn(from), to, any)).as("%s → %s is not in the table", from, to).isEqualTo("GD121");
                    continue;
                }
                assertThat(attempt(rowIn(from), to, t.sets())).as("%s → %s with exactly %s", from, to, t.sets()).isNull();
                for (String extra : OTHER.keySet()) {
                    if (t.sets().contains(extra)) {
                        continue;
                    }
                    assertThat(attempt(rowIn(from), to, t.sets(), extra)).as("%s → %s must not also move %s", from, to, extra).isEqualTo("GD121");
                }
            }
        }
    }

    /** 표의 {@code sets}는 가드가 아는 가변 컬럼뿐이다(오타·빠진 컬럼을 잡는다). */
    @Test
    void everySetsColumnIsKnown() {
        table.stream().filter(t -> !t.from().equals("-")).forEach(t -> assertThat(MUTABLE.keySet()).as(t.toString()).containsAll(t.sets()));
        assertThat(table).filteredOn(t -> t.from().equals("-")).singleElement().satisfies(t -> assertThat(t.to()).isEqualTo("QUEUED"));
    }

    /** 표의 {@code -} 행: INSERT는 QUEUED만, 종단 행은 지울 수 없다. */
    @Test
    void insertsStartQueuedAndRowsAreNeverRemoved() throws SQLException {
        String tenant = rowIn("SUCCEEDED").tenant();
        for (String status : List.of("RUNNING", "SUCCEEDED", "FAILED")) {
            String state = DB.asApp(tenant, c -> {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO async_job (tenant_id, job_id, kind, status, requested_by, channel, requested_at, started_at, finished_at, error_code)
                        VALUES (?, ?, 'EXPIRE', ?, 'jst@test', 'CLI', now(), now(), now(), 'INTERRUPTED')
                        """)) {
                    ps.setString(1, tenant);
                    ps.setObject(2, UUID.randomUUID());
                    ps.setString(3, status);
                    ps.executeUpdate();
                    return null;
                } catch (SQLException e) {
                    return e.getSQLState();
                }
            });
            assertThat(state).as("INSERT %s", status).isEqualTo("GD121");
        }
        Row done = rowIn("SUCCEEDED");
        String deleted;
        try (Connection c = DB.superuserDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM async_job WHERE tenant_id = ? AND job_id = ?")) {
            ps.setString(1, done.tenant());
            ps.setObject(2, done.id());
            ps.executeUpdate();
            deleted = null;
        } catch (SQLException e) {
            deleted = e.getSQLState();
        }
        assertThat(deleted).as("not even a superuser deletes a job row").isEqualTo("GD121");
    }
}
