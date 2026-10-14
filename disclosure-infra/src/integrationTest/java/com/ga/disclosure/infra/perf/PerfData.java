package com.ga.disclosure.infra.perf;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.infra.persistence.AuditLogRepository;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.platform.spring.jdbc.TenantSessionBinder;
import com.ga.disclosure.infra.tx.TenantTransactionTemplate;
import com.ga.platform.core.testing.SeededCases;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 질의 계획 실측의 허구 데이터(Phase 8 계획 ③-x-2). 시드 고정 생성기({@link SeededCases#generator})라 같은 시드면 같은 데이터다. 개인정보 0 — 고객 참조는
 * 자리값 하나, 이름·연락처 없음.
 * <ul>
 *   <li>확인서: 설계사 200명(조직 20개 {@code /HQ/B00}~{@code /HQ/B19}), 상담일은 기준일 전 730일에 고르게, 상태는 봉인 전 넷(DRAFT 40·COMPARED 20·GRADED
 *       15·REASONED 25%) — 봉인 상태는 번호·해시·체인 컬럼이 필요해 대량 생성에서 뺐다(목록 질의의 모양은 상태와 무관하다).</li>
 *   <li>플래그: 유형 11종 고르게, 25% 열림, 담당 준법 80%·관리자 20%, 같은 테넌트의 확인서에 걸림, 열린 시각은 730일에 고르게.</li>
 *   <li>확인서·플래그는 superuser가 트리거를 끄고(복제 역할) 넣는다 — 상태 가드는 이 시험의 관심이 아니다(V20AuditIndexIT와 같다).</li>
 *   <li>감사: 체인을 지키는 실제 경로({@link AuditLogRepository#append} — 앱 롤, 테넌트 바인딩, 해시 체인 트리거). 2,000행마다 커밋.</li>
 * </ul>
 */
public final class PerfData {

    static final String[] STATUSES = {"DRAFT", "DRAFT", "DRAFT", "DRAFT", "DRAFT", "DRAFT", "DRAFT", "DRAFT",
            "COMPARED", "COMPARED", "COMPARED", "COMPARED", "GRADED", "GRADED", "GRADED", "REASONED", "REASONED", "REASONED", "REASONED", "REASONED"};
    static final String[] FLAG_TYPES = {"GRADE_INCONSISTENT", "VALIDATION_OVERRIDE", "RULE_SUPERSEDED_DRAFT", "IDENTITY_FAILED", "SIGNATURE_DEVICE_REUSE",
            "PAPER_SCAN_REVIEW", "SIGN_EXPIRED", "CHAIN_BROKEN", "NOTIFY_FAILED", "RULE_DRIFT", "RULE_ACTIVATION_MISSED"};
    static final AuditAction[] ACTIONS = {AuditAction.DISCLOSURE_CREATE, AuditAction.DISCLOSURE_VIEW, AuditAction.DISCLOSURE_VIEW, AuditAction.ARTIFACT_VIEW,
            AuditAction.DISCLOSURE_SEAL};
    static final int AGENTS = 200;
    static final int ORGS = 20;
    static final LocalDate AS_OF = LocalDate.parse("2026-10-10");

    private PerfData() {
    }

    static String agent(int i) {
        return "AG-" + String.format("%03d", i);
    }

    static String org(int agent) {
        return "/HQ/B" + String.format("%02d", agent % ORGS);
    }

    /** 확인서 {@code count}건(돌려주는 목록 = 만든 ID, 플래그·감사가 건다). */
    static List<UUID> disclosures(DataSource superuser, String tenant, int count, long seed) {
        RandomGenerator r = SeededCases.generator(seed);
        List<UUID> ids = new ArrayList<>(count);
        replica(superuser, c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO disclosure (tenant_id, disclosure_id, org_path, agent_id, customer_ref, group_code, template_id, template_version,
                                            rule_version_id, issuer_mode, status, consult_date)
                    VALUES (?, ?, ?, ?, 'C-0001', 'PG-HEALTH', 'STANDARD', 1, 'DISC-2026-07', 'SELF', ?, ?)""")) {
                for (int i = 0; i < count; i++) {
                    UUID id = new UUID(r.nextLong(), r.nextLong());
                    int a = r.nextInt(AGENTS);
                    ps.setString(1, tenant);
                    ps.setObject(2, id);
                    ps.setString(3, org(a));
                    ps.setString(4, agent(a));
                    ps.setString(5, STATUSES[r.nextInt(STATUSES.length)]);
                    ps.setDate(6, Date.valueOf(AS_OF.minusDays(1 + r.nextInt(730))));
                    ps.addBatch();
                    ids.add(id);
                    if (i % 5_000 == 4_999) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
        });
        return ids;
    }

    static void flags(DataSource superuser, String tenant, List<UUID> disclosures, int count, long seed) {
        RandomGenerator r = SeededCases.generator(seed);
        Instant base = AS_OF.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        replica(superuser, c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO compliance_flag (tenant_id, flag_id, type, disclosure_id, severity, raised_at, resolved_at, resolved_by, resolution,
                                                 resolution_code, assigned_role, visible_to_agent)
                    VALUES (?, ?, ?, ?, 'HIGH', ?, ?, ?, ?, ?, ?, ?)""")) {
                for (int i = 0; i < count; i++) {
                    Instant raised = base.minusSeconds(60L + r.nextLong(730L * 86_400));
                    boolean open = r.nextInt(4) == 0;
                    ps.setString(1, tenant);
                    ps.setObject(2, new UUID(r.nextLong(), r.nextLong()));
                    ps.setString(3, FLAG_TYPES[r.nextInt(FLAG_TYPES.length)]);
                    ps.setObject(4, disclosures.get(r.nextInt(disclosures.size())));
                    ps.setTimestamp(5, Timestamp.from(raised));
                    ps.setTimestamp(6, open ? null : Timestamp.from(raised.plusSeconds(3_600)));
                    ps.setString(7, open ? null : "compliance-1");
                    ps.setString(8, open ? null : "MANUAL");
                    ps.setString(9, open ? null : "FALSE_POSITIVE");
                    ps.setString(10, r.nextInt(5) == 0 ? "MANAGER" : "COMPLIANCE");
                    ps.setBoolean(11, r.nextBoolean());
                    ps.addBatch();
                    if (i % 5_000 == 4_999) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
        });
    }

    /** 감사 {@code count}행 — 실제 경로(체인 트리거가 해시·순번을 검사한다). */
    static void audit(DataSource app, String tenant, List<UUID> disclosures, int count, long seed) {
        RandomGenerator r = SeededCases.generator(seed);
        TenantJdbcGateway gateway = new TenantJdbcGateway(app);
        TenantTransactionTemplate tx = new TenantTransactionTemplate(new TenantSessionBinder(app));
        AuditLogRepository audit = new AuditLogRepository(gateway);
        var detail = JsonMapper.builder().build().createObjectNode();
        Instant at = AS_OF.minusDays(730).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        int done = 0;
        while (done < count) {
            int batch = Math.min(2_000, count - done);
            Instant start = at;
            List<AuditEntry> entries = new ArrayList<>(batch);
            for (int i = 0; i < batch; i++) {
                start = start.plusSeconds(1 + r.nextInt(600));
                int a = r.nextInt(AGENTS);
                entries.add(new AuditEntry(start, "agent-" + a, "AGENT", ACTIONS[r.nextInt(ACTIONS.length)], "DISCLOSURE",
                        disclosures.get(r.nextInt(disclosures.size())).toString(), detail));
            }
            at = start;
            tx.inTenant(TenantId.of(tenant), () -> {
                entries.forEach(audit::append);
                return null;
            });
            done += batch;
        }
    }

    static void tenant(DataSource migrator, String tenant) {
        try (Connection c = migrator.getConnection()) {
            c.setAutoCommit(false);
            com.ga.disclosure.infra.testing.PostgresHarness.setTenant(c, tenant);
            SeedData.tenant(c, tenant);
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static void analyze(DataSource superuser) {
        try (Connection c = superuser.getConnection(); Statement s = c.createStatement()) {
            s.execute("ANALYZE");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    interface SqlWork {
        void run(Connection c) throws SQLException;
    }

    private static void replica(DataSource superuser, SqlWork work) {
        try (Connection c = superuser.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET LOCAL session_replication_role = replica");
            }
            work.run(c);
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
