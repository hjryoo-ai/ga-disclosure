package com.ga.disclosure.infra.perf;

import com.ga.disclosure.infra.testing.PostgresHarness;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 플래너 시험(V24ListIndexIT)이 실측 도구와 같은 생성기·같은 문장을 쓰게 하는 입구. */
public final class PerfQueries {

    private PerfQueries() {
    }

    public record Planned(String id, String plan) {
    }

    /** 테넌트 {@link QueryPlanReport#TENANT}에 확인서·플래그, 잡음 테넌트에 그 1/5 — 감사 없음(두 목록의 계획만 본다). 끝에 ANALYZE. */
    public static void generate(DataSource migrator, DataSource superuser, int disclosures, int flags) {
        PerfData.tenant(migrator, QueryPlanReport.TENANT);
        PerfData.tenant(migrator, QueryPlanReport.NOISE);
        List<UUID> ids = PerfData.disclosures(superuser, QueryPlanReport.TENANT, disclosures, 8_001L);
        List<UUID> noise = PerfData.disclosures(superuser, QueryPlanReport.NOISE, disclosures / 5, 8_002L);
        PerfData.flags(superuser, QueryPlanReport.TENANT, ids, flags, 8_003L);
        PerfData.flags(superuser, QueryPlanReport.NOISE, noise, flags / 5, 8_004L);
        PerfData.analyze(superuser);
    }

    /** 두 목록의 실측 문장(설계사 본인 목록 D3 제외 — 기존 인덱스)의 계획 모양(EXPLAIN, 실행하지 않음). */
    public static List<Planned> listPlans(DataSource app) {
        List<Planned> out = new ArrayList<>();
        for (MeasuredQueries.Query q : MeasuredQueries.all(UUID.randomUUID().toString())) {
            boolean list = q.source().equals(MeasuredQueries.SOURCE_DISCLOSURES) || q.source().equals(MeasuredQueries.SOURCE_FLAGS);
            if (!list || q.id().startsWith("D3")) {
                continue;
            }
            try (Connection c = app.getConnection()) {
                c.setAutoCommit(false);
                PostgresHarness.setTenant(c, QueryPlanReport.TENANT);
                try (PreparedStatement ps = c.prepareStatement("EXPLAIN (FORMAT JSON) " + q.sql())) {
                    for (int i = 1; i <= MeasuredQueries.tenantParams(q); i++) {
                        ps.setString(i, QueryPlanReport.TENANT);
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        JsonNode plan = QueryPlanReport.JSON.readTree(rs.getString(1)).get(0).get("Plan");
                        out.add(new Planned(q.id(), QueryPlanReport.shape(plan)));
                    }
                }
                c.rollback();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    public static String scalar(DataSource ds, String sql) {
        return QueryPlanReport.scalar(ds, sql);
    }
}
