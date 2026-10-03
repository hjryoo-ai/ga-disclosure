package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestPlan;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * B3(5 계획 승인 B3): 통합 테스트 실행이 끝나면 공유 DB 전체에서 {@code ARTIFACT_RETAIN.detail.reason = RETENTION_ALREADY_ELAPSED} 감사를 찾는다.
 * 보존이 이미 끝난 잠금 건너뜀은 짧은 보존 룰 변형({@link RetentionSetup#ELAPSED_TENANTS})에서만 일어나야 한다 — 다른 테넌트에서 나오면 과거 시계가
 * 실제 잠금 경로를 조용히 우회한 것이다. 대조군: 보존 종료 뒤 재적용을 부른 테넌트({@link RetentionSetup#RECONCILED_TENANTS})는 모두 발생이 보여야 한다
 * (스캔이 실제로 무언가를 본다는 증거). 결과는 파일로 남기고, Gradle {@code verifyElapsedRetentionScan}이 판정한다(파일이 없어도 실패).
 */
public final class ElapsedRetentionScan implements TestExecutionListener {

    static final String REPORT_PROPERTY = "ga.elapsedRetentionScanReport";

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        String target = System.getProperty(REPORT_PROPERTY);
        if (target == null) {
            return;                                            // Gradle 밖(IDE 단건 실행) — 판정 태스크도 없다
        }
        List<String> lines = new ArrayList<>();
        Optional<PostgresHarness> harness = PostgresHarness.ifStarted();
        if (harness.isEmpty()) {
            lines.add("harness=not-started");
        } else {
            Set<String> hits = hits(harness.get());
            lines.add("harness=started");
            lines.add("hits=" + hits.size());
            lines.add("allowed=" + RetentionSetup.ELAPSED_TENANTS.size());
            lines.add("controls=" + RetentionSetup.RECONCILED_TENANTS.size());
            for (String tenant : hits) {
                if (!RetentionSetup.ELAPSED_TENANTS.contains(tenant)) {
                    lines.add("VIOLATION elapsed retention outside the short-retention tenants: " + tenant);
                }
            }
            for (String control : new TreeSet<>(RetentionSetup.RECONCILED_TENANTS)) {
                if (!hits.contains(control)) {
                    lines.add("VIOLATION control tenant reconciled after retention but the scan saw nothing: " + control);
                }
            }
        }
        try {
            Path file = Path.of(target);
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 감사 행을 가진 테넌트(슈퍼유저 — 테넌트 경계를 넘는 테스트 전용 스캔). */
    private static Set<String> hits(PostgresHarness db) {
        Set<String> out = new TreeSet<>();
        try (Connection c = db.superuserDataSource().getConnection();
             var st = c.createStatement();
             var rs = st.executeQuery("""
                     SELECT DISTINCT tenant_id FROM audit_log
                      WHERE action = 'ARTIFACT_RETAIN' AND detail ->> 'reason' = 'RETENTION_ALREADY_ELAPSED'
                     """)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}
