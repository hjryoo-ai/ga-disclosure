package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V8 이관 경로: 별도 DB를 V7까지 올리고 옛 형식 행(무효 사유 원문, 사유 없는 정정, 적용 기한 없는 보존 기록, 사규의
 * {@code gateRequiresManager})을 넣은 뒤 V8을 적용한다. 공유 DB는 이미 최신이라 이 경로를 지나지 않는다.
 * 이관은 FORCE RLS 아래 소유 롤이 테넌트 단위 조인으로 쓰므로, FORCE를 풀지 않으면 행이 조용히 건너뛰어진다 — 두 테넌트로 확인한다.
 */
class V8MigrationIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String[] TENANTS = {"MIG_A", "MIG_B"};

    private static DataSource migrator;
    private static final List<UUID> preSealVoid = new ArrayList<>();
    private static final List<UUID> sealedVoid = new ArrayList<>();
    private static final List<UUID> superseded = new ArrayList<>();
    private static final List<UUID> retained = new ArrayList<>();

    @BeforeAll
    static void migrateV7SeedLegacyRowsThenV8() throws SQLException {
        migrator = DB.emptyDatabase("v8_upgrade");
        PostgresHarness.migrate(migrator, "7");
        for (String t : TENANTS) {
            inTenant(t, c -> {
                SeedData.tenant(c, t);
                SeedData.exec(c, "UPDATE tenant SET params = '{\"gateRequiresManager\": true, \"keep\": 1}'::jsonb WHERE tenant_id = ?", t);

                UUID draft = v7Draft(c, t);
                SeedData.exec(c, """
                        UPDATE disclosure SET status = 'VOID', voided_at = TIMESTAMPTZ '2026-09-24 09:00:00+09', void_reason = '상담 취소'
                         WHERE tenant_id = ? AND disclosure_id = ?
                        """, t, draft);
                preSealVoid.add(draft);

                UUID v = v7Sealed(c, t, 'c');
                SeedData.exec(c, """
                        UPDATE disclosure SET status = 'VOID', voided_at = TIMESTAMPTZ '2026-09-24 09:00:00+09', void_reason = '오기'
                         WHERE tenant_id = ? AND disclosure_id = ?
                        """, t, v);
                sealedVoid.add(v);

                UUID s = v7Sealed(c, t, 'd');
                SeedData.exec(c, "UPDATE disclosure SET status = 'SUPERSEDED', superseded_by_id = gen_random_uuid() WHERE tenant_id = ? AND disclosure_id = ?",
                        t, s);
                superseded.add(s);

                UUID r = v7Sealed(c, t, 'e');
                SeedData.artifact(c, t, r, "PDF");
                SeedData.exec(c, "UPDATE document_artifact SET retention_applied_at = TIMESTAMPTZ '2026-09-23 10:00:05+09' WHERE tenant_id = ? AND disclosure_id = ?",
                        t, r);
                retained.add(r);
            });
        }
        PostgresHarness.migrate(migrator, "8");
    }

    @Test
    void legacyVoidReasonMovesToTextUnderAMigrationCode() throws SQLException {
        for (int i = 0; i < TENANTS.length; i++) {
            for (UUID id : List.of(preSealVoid.get(i), sealedVoid.get(i))) {
                assertThat(row(TENANTS[i], "SELECT void_reason_code, void_reason_text IS NOT NULL, void_reason IS NULL FROM disclosure"
                        + " WHERE tenant_id = ? AND disclosure_id = ?", id))
                        .containsExactly("MIGRATED_V8", "t", "t");
            }
        }
    }

    @Test
    void supersededRowsGetTheMigrationCodeAndNoText() throws SQLException {
        for (int i = 0; i < TENANTS.length; i++) {
            assertThat(row(TENANTS[i], "SELECT supersede_reason_code, supersede_reason_text IS NULL FROM disclosure"
                    + " WHERE tenant_id = ? AND disclosure_id = ?", superseded.get(i)))
                    .containsExactly("MIGRATED_V8", "t");
        }
    }

    @Test
    void appliedRetentionGetsItsUntilFromTheDisclosure() throws SQLException {
        for (int i = 0; i < TENANTS.length; i++) {
            assertThat(row(TENANTS[i], "SELECT retention_applied_until::text FROM document_artifact WHERE tenant_id = ? AND disclosure_id = ?",
                    retained.get(i)))
                    .containsExactly(SeedData.RETENTION_UNTIL);
        }
    }

    @Test
    void gateRequiresManagerLeavesTenantParamsOnly() throws SQLException {
        for (String t : TENANTS) {
            assertThat(row(t, "SELECT params::text FROM tenant WHERE tenant_id = ?"))
                    .containsExactly("{\"keep\": 1}");
        }
    }

    @Test
    void forceRowLevelSecurityIsBackOnEveryTouchedTable() throws SQLException {
        try (Connection c = migrator.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT relname FROM pg_class
                 WHERE relname IN ('tenant', 'disclosure', 'document_artifact') AND NOT relforcerowsecurity
                """); ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).as("table left without FORCE RLS").isFalse();
        }
    }

    // ------------------------------------------------------------------ V7 형식 쓰기(현행 SeedData는 V8 형식이라 쓰지 않는다)

    private static UUID v7Draft(Connection c, String t) throws SQLException {
        UUID id = UUID.randomUUID();
        SeedData.exec(c, """
                INSERT INTO disclosure (tenant_id, disclosure_id, agent_id, customer_ref, group_code, template_id, template_version,
                                        rule_version_id, issuer_mode, status, consult_date)
                VALUES (?, ?, 'AGENT-1', 'C-0001', 'PG-HEALTH', 'STANDARD', 1, 'DISC-2026-07', 'SELF', 'DRAFT', DATE '2026-09-23')
                """, t, id);
        return id;
    }

    /** V7 봉인 경로(카운터 → 체인 머리 → 봉인 컬럼 → 머리 이동) — 3B 시드와 같은 순서. {@code canonical}은 hex 한 글자(64번 반복). */
    private static UUID v7Sealed(Connection c, String t, char canonical) throws SQLException {
        UUID id = UUID.randomUUID();
        SeedData.exec(c, """
                INSERT INTO disclosure (tenant_id, disclosure_id, agent_id, customer_ref, group_code, template_id, template_version,
                                        rule_version_id, issuer_mode, status, consult_date, grade_snapshot_id, grading_policy_version_id,
                                        ranking_policy_version_id, tie_break, grade_basis, snapshot_generated_at)
                VALUES (?, ?, 'AGENT-1', 'C-0001', 'PG-HEALTH', 'STANDARD', 1, 'DISC-2026-07', 'SELF', 'REASONED', DATE '2026-09-23',
                        'GRD-1', 'GRADING-2026-07', 'RANK-2026-07', 'SHARED_RANK', '{"period": "2026Q2"}'::jsonb,
                        TIMESTAMPTZ '2026-09-23 09:30:00+09')
                """, t, id);
        long seq = SeedData.longValue(c, """
                INSERT INTO disclosure_counter (tenant_id, year, seq) VALUES (?, 2026, 1)
                ON CONFLICT (tenant_id, year) DO UPDATE SET seq = disclosure_counter.seq + 1
                RETURNING seq
                """, t);
        long headSeq = SeedData.longValue(c, "SELECT coalesce(max(chain_seq), 0) FROM disclosure_chain_head WHERE tenant_id = ?", t);
        String headHash = headSeq == 0 ? SeedData.hash('0') : text(c, "SELECT chain_hash FROM disclosure_chain_head WHERE tenant_id = ?", t);
        String canonicalHash = SeedData.hash(canonical);
        String chainHash = SeedData.chainHash(headHash, canonicalHash, SeedData.PDF_HASH);
        SeedData.exec(c, """
                UPDATE disclosure SET status = 'SEALED', disclosure_no = ?, sealed_at = CAST(? AS timestamptz), canonical_hash = ?,
                                      pdf_hash = ?, chain_hash = ?, chain_seq = ?, retention_until = CAST(? AS date)
                 WHERE tenant_id = ? AND disclosure_id = ?
                """, t + "-2026-" + String.format("%06d", seq), SeedData.SEALED_AT, canonicalHash, SeedData.PDF_HASH, chainHash, headSeq + 1,
                SeedData.RETENTION_UNTIL, t, id);
        if (headSeq == 0) {
            SeedData.exec(c, "INSERT INTO disclosure_chain_head (tenant_id, chain_seq, chain_hash) VALUES (?, 1, ?)", t, chainHash);
        } else {
            SeedData.exec(c, "UPDATE disclosure_chain_head SET chain_seq = ?, chain_hash = ? WHERE tenant_id = ?", headSeq + 1, chainHash, t);
        }
        return id;
    }

    // ------------------------------------------------------------------ 이 DB 전용 접근(소유 롤 + 테넌트 바인딩)

    private interface Work {
        void run(Connection c) throws SQLException;
    }

    private static void inTenant(String tenant, Work work) throws SQLException {
        try (Connection c = migrator.getConnection()) {
            c.setAutoCommit(false);
            PostgresHarness.setTenant(c, tenant);
            work.run(c);
            c.commit();
        }
    }

    private static String text(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    /** 첫 자리표시자는 테넌트(문장은 언제나 {@code tenant_id = ?}로 시작한다), 나머지는 {@code params}. */
    private static List<String> row(String tenant, String sql, Object... params) throws SQLException {
        List<String> out = new ArrayList<>();
        inTenant(tenant, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, tenant);
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 2, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("row visible to tenant %s", tenant).isTrue();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        out.add(rs.getString(i));
                    }
                }
            }
        });
        return out;
    }
}
