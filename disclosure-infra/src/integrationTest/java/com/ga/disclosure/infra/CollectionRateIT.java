package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.infra.persistence.CollectionRateRepository;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.rules.bundle.BundleLoader;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.authz.AuthorizationDenied;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.disclosure.CommandRejectedException;
import com.ga.disclosure.workflow.rate.CollectionRateService;
import com.ga.disclosure.workflow.rate.CollectionRateStore.Row;
import com.ga.platform.canonical.Sha256;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G5(6B 지시문 §4, 계획 §5·승인 §3·§5): 징구율 — 내부 지표(규제 정의 없음).
 * <ul>
 *   <li>산식은 룰 enum: 기본 번들은 안 A, 소급 GLOBAL 버전이 안 B로 바꾸면 같은 달이 새 룰 버전으로 새 행(정정 경로).</li>
 *   <li>입력은 활성 연결만 — 정정된 연결은 새 계약일의 달에, 다른 확인서로 이전된 연결은 받은 확인서로 한 번. 완료일은 KST 날짜.</li>
 *   <li>파기·{@code ABANDONED}는 분모·분자 양쪽에서 빠진다. 저장 뒤의 파기는 행을 바꾸지 않는다(재조회 동일).</li>
 *   <li>같은 (달, 룰 버전) 재계산은 거부 {@code SNAPSHOT_EXISTS} — 새 행 없음, 감사 1행. {@code inputs_hash}는 번호 집합에서 다시 만들 수 있다.</li>
 *   <li>조회: 준법은 테넌트 전체(테넌트 행 포함), 관리자는 조직 아래 행만, 설계사는 인가 거부. 기본은 그 달 마지막 날 시행 버전의 행.</li>
 * </ul>
 */
class CollectionRateIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Actor OPERATOR = new Actor("ops-rates@test", "OPERATOR");
    private static final YearMonth SEPT = YearMonth.of(2026, 9);
    /** 실행 시각 2026-10-05 10:00 KST — 9월은 끝난 달, 10월은 아니다. */
    private static final Clock RUN = Clock.fixed(Instant.parse("2026-10-05T01:00:00Z"), ZoneOffset.UTC);

    private static CollectionRateService service(WorkflowSetup w, Clock clock) {
        return new CollectionRateService(new CollectionRateRepository(w.gateway), new RuleResolver(w.rules), w.audit, w.tx, Callers.authz(clock), clock,
                UUID::randomUUID);
    }

    interface Sql {
        void run(Connection c) throws SQLException;
    }

    /** 트리거를 끄고 고친다 — 봉인 뒤 바뀌지 않는 조직·완료 시각, 파기·폐기 묘비의 모양만 만든다(각 경로는 그 시험이 본다). */
    private static void bypass(WorkflowSetup w, Sql sql) {
        try (Connection c = w.db.superuserDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("SET LOCAL session_replication_role = replica");
            }
            sql.run(c);
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 봉인 이후 상태의 확인서(조직·완료 시각 지정). */
    private static UUID disclosure(WorkflowSetup w, String status, String org, String completedAtOrNull) {
        String t = w.tenant.value();
        UUID[] id = new UUID[1];
        w.db.seed(t, c -> id[0] = SeedData.disclosure(c, t, status, SeedData.hash('a')));
        bypass(w, c -> SeedData.exec(c, "UPDATE disclosure SET org_path = ?, completed_at = CAST(? AS timestamptz) WHERE tenant_id = ? AND disclosure_id = ?",
                org, completedAtOrNull, t, id[0]));
        return id[0];
    }

    private static UUID link(WorkflowSetup w, UUID disclosure, String policy, String contractDate) {
        String t = w.tenant.value();
        UUID[] id = new UUID[1];
        w.db.seed(t, c -> id[0] = SeedData.contractLink(c, t, disclosure, policy, contractDate));
        return id[0];
    }

    private static String no(WorkflowSetup w, UUID disclosure) {
        return w.db.asApp(w.tenant.value(), c -> SeedData.call(c, "SELECT disclosure_no FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?",
                w.tenant.value(), disclosure));
    }

    private static int snapshotRows(WorkflowSetup w) {
        return Integer.parseInt(w.db.asApp(w.tenant.value(), c -> SeedData.call(c, "SELECT count(*)::text FROM collection_rate_snapshot WHERE tenant_id = ?",
                w.tenant.value())));
    }

    private static String inputsHash(List<String> inputs, List<String> numerator, Optional<List<String>> unmatched) {
        ObjectNode o = JSON.createObjectNode();
        inputs.stream().sorted().forEach(o.putArray("inputs")::add);
        numerator.stream().sorted().forEach(o.putArray("numerator")::add);
        unmatched.ifPresent(u -> u.stream().sorted().forEach(o.putArray("unmatched")::add));
        return Sha256.of(com.ga.platform.canonical.Canonicalizer.canonicalize(o));
    }

    /** DISC-2026-07 본문을 안 B로 바꾼 소급 GLOBAL 버전(9월부터) — 정정은 룰 버전을 올리는 것이다. */
    private static void retroactiveFormulaB(WorkflowSetup w) {
        ObjectNode tree = (ObjectNode) JSON.readTree(com.ga.disclosure.rules.testing.Bundles.text(com.ga.disclosure.rules.testing.Bundles.DISC_2026_07));
        ((ObjectNode) tree.get("body").get("collectionRate")).put("formula", "TARGET_INCLUDING_UNMATCHED");
        String hash = Sha256.ofCanonical(JSON.writeValueAsString(tree.get("body")));
        tree.put("ruleVersionId", "DISC-2026-09").put("bundleId", "DISC-2026-09@" + hash.substring(0, 12)).put("applyFrom", "2026-09-01");
        tree.putObject("supersedes").put("ruleVersionId", "DISC-2026-07");
        new Governance().distribution.distribute(BundleLoader.parse("DISC-2026-09 (retroactive B)", JSON.writeValueAsString(tree)), w.tenant,
                Governance.OPERATOR);
        new Governance("2026-10-05T00:00:00Z").activation.run(w.tenant, Governance.OPERATOR);
    }

    private static void unmatched(WorkflowSetup w, String policy, String date, String reason, String ref) {
        String t = w.tenant.value();
        w.db.seed(t, c -> SeedData.exec(c, """
                INSERT INTO contract_link_unmatched (tenant_id, unmatched_id, policy_no, contract_date, insurer_code, reason, source, source_ref, received_at)
                VALUES (?, gen_random_uuid(), ?, CAST(? AS date), 'INS-A', ?, 'SEED', ?, now())
                """, t, policy, date, reason, ref));
    }

    @Test
    void aSnapshotCountsActiveLinksOnceExcludesTombstonesAndIsNeverRecomputedUnderTheSameRuleVersion() {
        try (WorkflowSetup w = SealScenarios.without2027Rule()) {                // 소급 GLOBAL 버전이 2026 룰을 9월부터 대체할 수 있게
            String t = w.tenant.value();
            // a1 징구(완료 9/10 KST ≤ 계약 9/12), a2 미징구(완료 9/20 00:30 KST > 계약 9/19 — UTC로는 9/19), a3 봉인만
            UUID a1 = disclosure(w, "COMPLETED", "/HQ/B1", "2026-09-10 10:00:00+09");
            link(w, a1, "POL-1", "2026-09-12");
            UUID a2 = disclosure(w, "COMPLETED", "/HQ/B1", "2026-09-20 00:30:00+09");
            link(w, a2, "POL-2", "2026-09-19");
            UUID a3 = disclosure(w, "SEALED", "/HQ/B2", null);
            link(w, a3, "POL-3", "2026-09-05");
            // a4(무효)의 연결이 a5(완료)로 이전 — 받은 확인서로 한 번
            UUID a4 = disclosure(w, "VOID", "/HQ/B2", null);
            UUID carriedFrom = link(w, a4, "POL-4", "2026-09-07");
            UUID a5 = disclosure(w, "COMPLETED", "/HQ/B2", "2026-09-01 10:00:00+09");
            w.db.asAppCommitting(t, c -> {
                UUID to = UUID.randomUUID();
                SeedData.exec(c, "UPDATE contract_link SET carried_to = ?, carried_at = now() WHERE tenant_id = ? AND link_id = ?", to, t, carriedFrom);
                SeedData.exec(c, """
                        INSERT INTO contract_link (tenant_id, link_id, disclosure_id, policy_no, contract_date, insurer_code, source, source_ref, received_at, linked_by)
                        VALUES (?, ?, ?, 'POL-4', DATE '2026-09-07', 'INS-A', 'SEED', ?, now(), 'seed')
                        """, t, to, a5, to.toString());
                SeedData.exec(c, "UPDATE disclosure SET policy_no = 'POL-4', contract_date = DATE '2026-09-07' WHERE tenant_id = ? AND disclosure_id = ?", t, a5);
                return null;
            });
            // a6: 8/31로 연결됐다가 9/15로 정정 — 활성 행만(9월에 한 번)
            UUID a6 = disclosure(w, "COMPLETED", "/HQ/B1", "2026-09-01 10:00:00+09");
            UUID old = link(w, a6, "POL-6", "2026-08-31");
            w.db.asAppCommitting(t, c -> {
                UUID next = UUID.randomUUID();
                SeedData.exec(c, "UPDATE contract_link SET superseded_by = ?, superseded_at = now() WHERE tenant_id = ? AND link_id = ?", next, t, old);
                SeedData.exec(c, """
                        INSERT INTO contract_link (tenant_id, link_id, disclosure_id, policy_no, contract_date, insurer_code, source, source_ref, received_at, linked_by)
                        VALUES (?, ?, ?, 'POL-6', DATE '2026-09-15', 'INS-A', 'SEED', ?, now(), 'seed')
                        """, t, next, a6, next.toString());
                SeedData.exec(c, "UPDATE disclosure SET contract_date = DATE '2026-09-15' WHERE tenant_id = ? AND disclosure_id = ?", t, a6);
                return null;
            });
            // 묘비: a7 파기, a8 ABANDONED 모양 — 양쪽에서 빠진다. a9는 10월 계약.
            UUID a7 = disclosure(w, "COMPLETED", "/X", "2026-09-01 10:00:00+09");
            link(w, a7, "POL-7", "2026-09-08");
            bypass(w, c -> SeedData.exec(c, "UPDATE disclosure SET destroyed_at = now(), destroyed_by = 'seed' WHERE tenant_id = ? AND disclosure_id = ?", t, a7));
            // ABANDONED는 봉인 전 초안의 묘비라 DB가 연결을 막는다(GD130·번호 없음 CHECK) — 연결된 묘비 모양은 트리거를 끄고 만든다(산식이 번호 없이도 뺀다)
            UUID[] a8 = new UUID[1];
            w.db.seed(t, c -> a8[0] = SeedData.disclosure(c, t, "DRAFT", SeedData.hash('a')));
            w.db.asAppCommitting(t, c -> {
                SeedData.exec(c, "SET LOCAL ROLE disclosure_abandoner");
                return SeedData.call(c, "SELECT ga_draft_abandon(?, ?, now(), 'seed')", t, a8[0]);
            });
            bypass(w, c -> SeedData.exec(c, """
                    INSERT INTO contract_link (tenant_id, link_id, disclosure_id, policy_no, contract_date, insurer_code, source, source_ref, received_at, linked_by)
                    VALUES (?, gen_random_uuid(), ?, 'POL-8', DATE '2026-09-08', 'INS-A', 'SEED', 'abandoned-shape', now(), 'seed')
                    """, t, a8[0]));
            UUID a9 = disclosure(w, "COMPLETED", "/HQ/B1", "2026-09-01 10:00:00+09");
            link(w, a9, "POL-9", "2026-10-02");

            CollectionRateService rates = service(w, RUN);
            Caller operator = Callers.cli(w.tenant, OPERATOR);
            assertThat(rates.previousMonth()).isEqualTo(SEPT);
            UUID job = UUID.randomUUID();
            CollectionRateService.Report report = rates.snapshot(operator, SEPT, job);
            assertThat(report.ruleVersionId()).isEqualTo(RuleVersionId.of("DISC-2026-07"));
            assertThat(report.result().formula().name()).isEqualTo("LINKED_COMPLETED_BY_CONTRACT_DATE");

            Caller compliance = Callers.of(w.tenant, SealSetup.COMPLIANCE);
            List<Row> rows = rates.read(compliance, SEPT, SEPT, Optional.empty(), Optional.empty());
            assertThat(rows).extracting(r -> r.orgPath() + " " + r.numerator() + "/" + r.denominator() + " " + r.rateBp())
                    .containsExactly("/ 3/5 OptionalInt[6000]", "/HQ/B1 2/3 OptionalInt[6666]", "/HQ/B2 1/2 OptionalInt[5000]");
            assertThat(rows).allSatisfy(r -> {
                assertThat(r.jobId()).isEqualTo(job);
                assertThat(r.formula()).isEqualTo("LINKED_COMPLETED_BY_CONTRACT_DATE");
                assertThat(r.ruleVersionId().value()).isEqualTo("DISC-2026-07");
            });
            // inputs_hash는 번호 집합에서 다시 만들 수 있다
            assertThat(rows.getFirst().inputsHash()).isEqualTo(inputsHash(List.of(no(w, a1), no(w, a2), no(w, a3), no(w, a5), no(w, a6)),
                    List.of(no(w, a1), no(w, a5), no(w, a6)), Optional.empty()));
            assertThat(rows.get(2).inputsHash()).isEqualTo(inputsHash(List.of(no(w, a3), no(w, a5)), List.of(no(w, a5)), Optional.empty()));

            // 같은 (달, 룰 버전) 재계산은 거부 — 제출 전 검사도 본체도. 새 행 없음, 거부 감사.
            int before = snapshotRows(w);
            assertThatThrownBy(() -> rates.admit(operator, SEPT)).isInstanceOfSatisfying(CommandRejectedException.class, e -> {
                assertThat(e.code()).isEqualTo(CollectionRateService.SNAPSHOT_EXISTS);
                assertThat(e.category().name()).isEqualTo("CONFLICT");
            });
            assertThatThrownBy(() -> rates.snapshot(operator, SEPT, UUID.randomUUID()))
                    .isInstanceOfSatisfying(CommandRejectedException.class, e -> assertThat(e.code()).isEqualTo(CollectionRateService.SNAPSHOT_EXISTS));
            assertThat(snapshotRows(w)).isEqualTo(before);
            assertThat(w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.COLLECTION_RATE_SNAPSHOT_REJECTED).hasSize(2)
                    .allSatisfy(a -> assertThat(a.entry().detail().get("ruleVersionId").asString()).isEqualTo("DISC-2026-07"));
            assertThat(w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.COLLECTION_RATE_SNAPSHOT).singleElement().satisfies(a -> {
                assertThat(a.entry().detail().get("definition").asString()).isEqualTo("INTERNAL_METRIC_NO_REGULATORY_DEFINITION");
                assertThat(a.entry().detail().get("rows").asInt()).isEqualTo(3);
                assertThat(a.entry().detail().toString()).doesNotContain("POL-");
            });
            // 이번 달은 끝나지 않았다
            assertThatThrownBy(() -> rates.admit(operator, YearMonth.of(2026, 10))).isInstanceOf(IllegalArgumentException.class);

            // 저장 뒤의 파기는 행을 바꾸지 않는다
            bypass(w, c -> SeedData.exec(c, "UPDATE disclosure SET destroyed_at = now(), destroyed_by = 'seed' WHERE tenant_id = ? AND disclosure_id = ?", t, a1));
            assertThat(rates.read(compliance, SEPT, SEPT, Optional.empty(), Optional.empty())).isEqualTo(rows);

            // 정정 = 룰 버전을 올린다: 소급 GLOBAL 버전(안 B)이 9월의 시행 버전이 되면 새 rule_version_id로 새 행 — 묘비 제외는 계산 시점(a1 파기)
            unmatched(w, "POL-U1", "2026-09-03", "UNMATCHED", "u1");
            unmatched(w, "POL-U1", "2026-09-03", "UNMATCHED", "u1-again");          // 같은 증권은 한 번
            unmatched(w, "POL-U2", "2026-09-03", "AMBIGUOUS_MATCH", "u2");          // 미매칭 사유만
            unmatched(w, "POL-U3", "2026-10-01", "UNMATCHED", "u3");                // 다른 달
            unmatched(w, "POL-4", "2026-09-07", "UNMATCHED", "u4");                 // 지금 활성 연결이 있는 증권
            retroactiveFormulaB(w);
            CollectionRateService.Report corrected = rates.snapshot(operator, SEPT, UUID.randomUUID());
            assertThat(corrected.ruleVersionId().value()).isEqualTo("DISC-2026-09");
            List<Row> now = rates.read(compliance, SEPT, SEPT, Optional.empty(), Optional.empty());
            assertThat(now).extracting(r -> r.orgPath() + " " + r.numerator() + "/" + r.denominator() + " " + r.formula() + " " + r.ruleVersionId())
                    .containsExactly("/ 2/5 TARGET_INCLUDING_UNMATCHED DISC-2026-09", "/HQ/B1 1/2 TARGET_INCLUDING_UNMATCHED DISC-2026-09",
                            "/HQ/B2 1/2 TARGET_INCLUDING_UNMATCHED DISC-2026-09");
            assertThat(now.getFirst().inputsHash()).isEqualTo(inputsHash(List.of(no(w, a2), no(w, a3), no(w, a5), no(w, a6)),
                    List.of(no(w, a5), no(w, a6)), Optional.of(List.of(Sha256.of("POL-U1".getBytes(StandardCharsets.UTF_8))))));
            // 옛 버전의 행은 그대로 있고 ruleVersionId로 고른다
            assertThat(rates.read(compliance, SEPT, SEPT, Optional.empty(), Optional.of(RuleVersionId.of("DISC-2026-07")))).isEqualTo(rows);
        }
    }

    /**
     * 7단계 회신 ①: "정정된 연결은 한 번"은 <b>계산 시점 기준</b>이다. 8월 스냅샷이 저장된 뒤 계약일이 9월로 정정되면 8월 스냅샷은 불변이라 그 연결을
     * 계속 담고 9월 스냅샷도 담는다 — 두 스냅샷의 {@code inputs_hash}는 각자의 입력 번호 집합에서 다시 만들어져 구분된다.
     */
    @Test
    void aCorrectionAfterASnapshotLeavesTheOldMonthIntactAndTheNewMonthIncludesTheLink() {
        try (WorkflowSetup w = new WorkflowSetup()) {
            String t = w.tenant.value();
            UUID moved = disclosure(w, "COMPLETED", "/HQ/B1", "2026-07-01 10:00:00+09");
            UUID old = link(w, moved, "POL-MV", "2026-08-20");
            UUID stays = disclosure(w, "COMPLETED", "/HQ/B1", "2026-07-01 10:00:00+09");
            link(w, stays, "POL-ST", "2026-08-10");
            CollectionRateService rates = service(w, RUN);
            Caller operator = Callers.cli(w.tenant, OPERATOR);
            Caller compliance = Callers.of(w.tenant, SealSetup.COMPLIANCE);
            YearMonth aug = YearMonth.of(2026, 8);
            rates.snapshot(operator, aug, UUID.randomUUID());
            List<Row> august = rates.read(compliance, aug, aug, Optional.empty(), Optional.empty());
            assertThat(august.getFirst().denominator()).isEqualTo(2);

            // 계약일 정정 8/20 → 9/5(같은 확인서의 다음 행, 옛 행은 superseded_by로 닫힌다)
            w.db.asAppCommitting(t, c -> {
                UUID next = UUID.randomUUID();
                SeedData.exec(c, "UPDATE contract_link SET superseded_by = ?, superseded_at = now() WHERE tenant_id = ? AND link_id = ?", next, t, old);
                SeedData.exec(c, """
                        INSERT INTO contract_link (tenant_id, link_id, disclosure_id, policy_no, contract_date, insurer_code, source, source_ref, received_at, linked_by)
                        VALUES (?, ?, ?, 'POL-MV', DATE '2026-09-05', 'INS-A', 'SEED', ?, now(), 'seed')
                        """, t, next, moved, next.toString());
                SeedData.exec(c, "UPDATE disclosure SET contract_date = DATE '2026-09-05' WHERE tenant_id = ? AND disclosure_id = ?", t, moved);
                return null;
            });
            rates.snapshot(operator, SEPT, UUID.randomUUID());

            // 8월 스냅샷은 그대로(정정된 연결을 계속 담는다), 9월 스냅샷도 그 연결을 담는다
            assertThat(rates.read(compliance, aug, aug, Optional.empty(), Optional.empty())).isEqualTo(august);
            List<Row> september = rates.read(compliance, SEPT, SEPT, Optional.empty(), Optional.empty());
            assertThat(september.getFirst().denominator()).isEqualTo(1);
            String m = no(w, moved);
            String st = no(w, stays);
            assertThat(august.getFirst().inputsHash()).isEqualTo(inputsHash(List.of(m, st), List.of(m, st), Optional.empty()))
                    // 지금 8월을 다시 센다면 입력은 {stays}뿐이다 — 저장된 해시와 다르므로 저장 시점의 입력이었음을 구분할 수 있다
                    .isNotEqualTo(inputsHash(List.of(st), List.of(st), Optional.empty()));
            assertThat(september.getFirst().inputsHash()).isEqualTo(inputsHash(List.of(m), List.of(m), Optional.empty()));
            // 같은 룰 버전으로 8월을 고쳐 쓸 수는 없다
            assertThatThrownBy(() -> rates.snapshot(operator, aug, UUID.randomUUID())).isInstanceOf(CommandRejectedException.class);
        }
    }

    @Test
    void readsAreScopedManagersSeeOnlyTheirOrgRowsAndAgentsNothing() {
        try (WorkflowSetup w = new WorkflowSetup()) {
            UUID b1 = disclosure(w, "COMPLETED", "/HQ/B1", "2026-08-01 10:00:00+09");
            link(w, b1, "POL-M1", "2026-08-10");
            UUID b1x = disclosure(w, "COMPLETED", "/HQ/B1X", "2026-08-01 10:00:00+09");
            link(w, b1x, "POL-M2", "2026-08-10");
            UUID b2 = disclosure(w, "SEALED", "/HQ/B2", null);
            link(w, b2, "POL-M3", "2026-08-10");
            CollectionRateService rates = service(w, RUN);
            // 준법도 스냅샷을 만들 수 있다(작업 제출 행위 — 테넌트)
            rates.snapshot(Callers.of(w.tenant, SealSetup.COMPLIANCE), YearMonth.of(2026, 8), UUID.randomUUID());
            // 빈 달(연결 없음)도 테넌트 행 0/0
            rates.snapshot(Callers.cli(w.tenant, OPERATOR), YearMonth.of(2026, 7), UUID.randomUUID());

            YearMonth jul = YearMonth.of(2026, 7);
            YearMonth aug = YearMonth.of(2026, 8);
            assertThat(rates.read(Callers.of(w.tenant, SealSetup.COMPLIANCE), jul, aug, Optional.empty(), Optional.empty()))
                    .extracting(r -> r.periodMonth() + " " + r.orgPath() + " " + r.numerator() + "/" + r.denominator() + " " + r.rateBp())
                    .containsExactly("2026-07-01 / 0/0 OptionalInt.empty", "2026-08-01 / 2/3 OptionalInt[6666]", "2026-08-01 /HQ/B1 1/1 OptionalInt[10000]",
                            "2026-08-01 /HQ/B1X 1/1 OptionalInt[10000]", "2026-08-01 /HQ/B2 0/1 OptionalInt[0]");
            // 관리자(/HQ/B1): 조직 아래 행만 — 테넌트 행·/HQ/B1X(접두 문자열이 같은 다른 조직)·/HQ/B2 없음
            assertThat(rates.read(Callers.of(w.tenant, WorkflowSetup.MANAGER), jul, aug, Optional.empty(), Optional.empty()))
                    .extracting(Row::orgPath).containsExactly("/HQ/B1");
            assertThat(rates.read(Callers.of(w.tenant, WorkflowSetup.MANAGER), jul, aug, Optional.of("/"), Optional.empty())).isEmpty();
            assertThat(rates.read(Callers.of(w.tenant, SealSetup.COMPLIANCE), jul, aug, Optional.of("/HQ/B2"), Optional.empty()))
                    .extracting(Row::orgPath).containsExactly("/HQ/B2");
            // 설계사는 칸이 없다 — 조회·스냅샷 모두 인가 거부
            assertThatThrownBy(() -> rates.read(Callers.of(w.tenant, WorkflowSetup.AGENT), jul, aug, Optional.empty(), Optional.empty()))
                    .isInstanceOf(AuthorizationDenied.class);
            assertThatThrownBy(() -> rates.snapshot(Callers.of(w.tenant, WorkflowSetup.MANAGER), YearMonth.of(2026, 6), UUID.randomUUID()))
                    .isInstanceOf(AuthorizationDenied.class);
            // 달마다 기본은 그 달 마지막 날 시행 버전 — 시행 룰이 없는 달은 비어 있다(룰은 2026-07부터), 기간 상한
            assertThat(rates.read(Callers.of(w.tenant, SealSetup.COMPLIANCE), YearMonth.of(2026, 5), YearMonth.of(2026, 6), Optional.empty(),
                    Optional.empty())).isEmpty();
            assertThatThrownBy(() -> rates.read(Callers.of(w.tenant, SealSetup.COMPLIANCE), YearMonth.of(2024, 1), aug, Optional.empty(), Optional.empty()))
                    .isInstanceOf(IllegalArgumentException.class);

            // 12월을 1월에 계산해도 산식·버전은 12월 말 시행 룰(DISC-2026-07)이다 — 실행일 룰(2027-01-01부터 DISC-2027-01)이 아니다. 기본 조회가 그 행을 찾는다.
            CollectionRateService january = service(w, Clock.fixed(Instant.parse("2027-01-05T01:00:00Z"), ZoneOffset.UTC));
            YearMonth dec = YearMonth.of(2026, 12);
            assertThat(january.previousMonth()).isEqualTo(dec);
            assertThat(january.snapshot(Callers.cli(w.tenant, OPERATOR), dec, UUID.randomUUID()).ruleVersionId().value()).isEqualTo("DISC-2026-07");
            assertThat(january.read(Callers.of(w.tenant, SealSetup.COMPLIANCE), dec, dec, Optional.empty(), Optional.empty()))
                    .singleElement().satisfies(r -> assertThat(r.ruleVersionId().value()).isEqualTo("DISC-2026-07"));
        }
    }
}
