package com.ga.disclosure.infra;

import com.ga.disclosure.infra.perf.PerfQueries;
import com.ga.disclosure.infra.testing.PostgresHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V24(Phase 8 계획 ③-x-2): 확인서 목록·플래그 큐의 정렬 인덱스. 실측(10만 행, {@code queryPlanReport} — 보고서·{@code verification/query-plans})은 수동이고,
 * 이 시험은 CI에서 계획의 모양을 지킨다: 별도 데이터베이스(최신 스키마)에 실측과 같은 시드 고정 허구 데이터(테넌트 하나에 확인서 10만·플래그 3만, 잡음
 * 테넌트 2만·6천 — 감사 없음, 생성 약 10초)를 넣고 ANALYZE한 뒤, 실측 대상 문장 중 두 목록의 계획이 새 인덱스를 쓰고 정렬 노드가 없는지(앱 롤·테넌트
 * 바인딩·RLS 아래). 규모를 줄이면(2만) 관리자 조직 플래그 큐는 해시 조인 + 정렬이 더 싸다고 플래너가 고른다 — 실측 규모의 계획을 지키려고 같은 규모다.
 */
class V24ListIndexIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static DataSource app;
    private static DataSource superuser;

    @BeforeAll
    static void data() {
        String name = "v24_" + Long.toHexString(System.nanoTime());
        DataSource migrator = DB.emptyDatabase(name);
        PostgresHarness.migrate(migrator, "latest");
        superuser = DB.superuserDataSource(name);
        app = DB.appDataSource(name);
        PerfQueries.generate(migrator, superuser, 100_000, 30_000);
    }

    @Test
    void theIndexesAreTheSortKeysWithTheTenantFirst() {
        assertThat(PerfQueries.scalar(superuser, "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_disclosure_list_order'"))
                .endsWith("(tenant_id, consult_date DESC, disclosure_id DESC)");
        assertThat(PerfQueries.scalar(superuser, "SELECT indexdef FROM pg_indexes WHERE indexname = 'ix_compliance_flag_list_order'"))
                .endsWith("(tenant_id, raised_at DESC, flag_id DESC)");
    }

    @Test
    void bothListsWalkTheirIndexWithoutSorting() {
        List<String> wrong = new ArrayList<>();
        for (PerfQueries.Planned p : PerfQueries.listPlans(app)) {
            String expected = p.id().startsWith("D") ? "ix_disclosure_list_order" : "ix_compliance_flag_list_order";
            if (!p.plan().contains(expected) || p.plan().contains("Sort")) {
                wrong.add(p.id() + ": " + p.plan());
            }
        }
        assertThat(wrong).isEmpty();
    }
}
