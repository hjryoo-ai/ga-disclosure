package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.infra.persistence.DocumentRecordRepository;
import com.ga.disclosure.infra.persistence.RetentionRecomputeRepository;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.rules.bundle.BundleLoader;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.authz.AuthorizationDenied;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.disclosure.CommandRejectedException;
import com.ga.disclosure.workflow.disclosure.RetentionRecomputeService;
import com.ga.disclosure.workflow.disclosure.RetentionRecomputeService.Outcome;
import com.ga.disclosure.workflow.disclosure.SealService;
import com.ga.platform.canonical.Sha256;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G8(6B 지시문 §7, 계획 §8): 보존 재계산 — 연장만, dry-run은 무변경, 파기 건 제외(수만), 잠금 재적용, 감사 전후값. 룰은 이 테넌트의 ACTIVE·APPROVED
 * GLOBAL 버전만(아니면 {@code RULE_VERSION_NOT_USABLE}). 후보가 짧으면 계산은 하되(보고서) 쓰지 않는다 — 쓰는 코드가 "더 길 때만" 하나다.
 */
class RetentionRecomputeIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Actor OPERATOR = new Actor("ops-recompute@test", "OPERATOR");
    /** 실행 시각 — 보존(1일)이 아직 끝나지 않은 날(잠금을 실제로 건다). */
    private static final Clock RUN = Clock.fixed(Instant.parse("2026-09-23T03:00:00Z"), java.time.ZoneOffset.UTC);

    static RetentionRecomputeService service(RetentionSetup r, Clock clock) {
        WorkflowSetup w = r.x.w;
        return new RetentionRecomputeService(new RetentionRecomputeRepository(w.gateway, w.disclosures), w.rules, new DocumentRecordRepository(w.gateway),
                r.x.s.store, w.audit, w.tx, Callers.authz(clock), clock);
    }

    /** DISC-2026-07 본문을 고친 GLOBAL 버전을 배포한다(미래 시행일 — APPROVED로 남는다). */
    static void deploy(RetentionSetup r, String id, String applyFrom, String supersedes, Consumer<ObjectNode> edit) {
        ObjectNode tree = (ObjectNode) JSON.readTree(com.ga.disclosure.rules.testing.Bundles.text(com.ga.disclosure.rules.testing.Bundles.DISC_2026_07));
        ObjectNode body = (ObjectNode) tree.get("body");
        body.put("retentionYears", 0).put("retentionDays", 1);
        ((ObjectNode) body.get("retention")).put("contractLinkWaitDays", 0);
        ((ObjectNode) body.get("customerRef")).put("graceDaysAfterLastDestruction", 0);
        edit.accept(body);
        String hash = Sha256.ofCanonical(JSON.writeValueAsString(body));
        tree.put("ruleVersionId", id).put("bundleId", id + "@" + hash.substring(0, 12)).put("applyFrom", applyFrom);
        tree.putObject("supersedes").put("ruleVersionId", supersedes);
        new Governance().distribution.distribute(BundleLoader.parse(id, JSON.writeValueAsString(tree)), r.x.w.tenant, Governance.OPERATOR);
    }

    static String retention(RetentionSetup r, DisclosureId id) {
        return r.x.w.db.asApp(r.x.w.tenant.value(), c -> SeedData.call(c, "SELECT retention_until::text FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?",
                r.x.w.tenant.value(), id.value()));
    }

    /** 그 확인서의 산출물·서명 증거 객체별 잠금 적용 기한(정렬, 미적용은 "-") — 잠금 재적용의 증거. */
    static List<String> applied(RetentionSetup r, DisclosureId id) {
        String t = r.x.w.tenant.value();
        String all = r.x.w.db.asApp(t, c -> SeedData.call(c, """
                SELECT string_agg(u, ',' ORDER BY u) FROM (
                    SELECT coalesce(retention_applied_until::text, '-') AS u FROM document_artifact WHERE tenant_id = ? AND disclosure_id = ?
                    UNION ALL
                    SELECT coalesce(retention_applied_until::text, '-') FROM signature_evidence WHERE tenant_id = ? AND disclosure_id = ?) x
                """, t, id.value(), t, id.value()));
        return List.of(all.split(","));
    }

    static List<String> keys(RetentionSetup r, DisclosureId id) {
        String t = r.x.w.tenant.value();
        return List.of(r.x.w.db.asApp(t, c -> SeedData.call(c, """
                SELECT string_agg(k, ',') FROM (SELECT storage_key AS k FROM document_artifact WHERE tenant_id = ? AND disclosure_id = ?
                                                 UNION ALL SELECT storage_key FROM signature_evidence WHERE tenant_id = ? AND disclosure_id = ?) x
                """, t, id.value(), t, id.value())).split(","));
    }

    static void bypass(RetentionSetup r, String sql, Object... params) {
        try (Connection c = r.x.w.db.superuserDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("SET LOCAL session_replication_role = replica");
            }
            SeedData.exec(c, sql, params);
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void dryRunWritesNothingApplyExtendsOnlyRelocksAuditsAndARerunIsUnchanged() {
        try (RetentionSetup r = new RetentionSetup()) {
            DisclosureId a = r.completed();
            DisclosureId b = r.completed();
            DisclosureId gone = r.completed();
            bypass(r, "UPDATE disclosure SET destroyed_at = now(), destroyed_by = 'seed' WHERE tenant_id = ? AND disclosure_id = ?", r.x.w.tenant.value(),
                    gone.value());
            deploy(r, "DISC-RET-LONG", "2027-03-01", "DISC-2026-07", body -> body.put("retentionYears", 5));
            String beforeA = retention(r, a);
            // 시작점: 현재 보존기한으로 적용됐다는 기록(1일 보존이라 저장소 잠금 기한은 이미 지났다 — 저장소는 과거 기한을 거부하므로 기록만 둔다)
            r.x.s.store.retentionInsideTransaction.clear();
            bypass(r, "UPDATE document_artifact SET retention_applied_at = now(), retention_applied_until = CAST(? AS date) WHERE tenant_id = ? AND disclosure_id = ?",
                    beforeA, r.x.w.tenant.value(), a.value());
            bypass(r, "UPDATE signature_evidence SET retention_applied_at = now(), retention_applied_until = CAST(? AS date) WHERE tenant_id = ? AND disclosure_id = ?",
                    beforeA, r.x.w.tenant.value(), a.value());
            RetentionRecomputeService recompute = service(r, RUN);
            Caller compliance = Callers.of(r.x.w.tenant, SealSetup.COMPLIANCE);

            // dry-run(기본): 같은 표, 쓰기·감사 없음
            RetentionRecomputeService.Report dry = recompute.run(compliance, RuleVersionId.of("DISC-RET-LONG"), false, UUID.randomUUID());
            assertThat(dry.apply()).isFalse();
            assertThat(dry.items()).extracting(RetentionRecomputeService.Item::id).containsExactlyInAnyOrder(a, b);
            assertThat(dry.items()).allSatisfy(i -> {
                assertThat(i.outcome()).isEqualTo(Outcome.EXTENDED);
                assertThat(i.candidate()).isEqualTo(i.before().minusDays(1).plusYears(5).plusDays(1));
            });
            assertThat(dry.destroyedExcluded()).isOne();
            assertThat(com.ga.disclosure.audit.verify.VerifySchemas.retentionRecomputeReport(dry.toJson())).isEmpty();
            String afterA = LocalDate.parse(beforeA).minusDays(1).plusYears(5).plusDays(1).toString();
            assertThat(retention(r, a)).isEqualTo(beforeA);
            assertThat(applied(r, a)).hasSize(8).containsOnly(beforeA);
            assertThat(r.x.s.store.retentionInsideTransaction).as("dry-run은 저장소를 부르지 않는다").isEmpty();
            assertThat(r.x.w.auditLog()).noneMatch(x -> x.entry().action() == AuditAction.RETENTION_RECOMPUTED);

            // 적용: 연장 + 감사(전후·룰·작업) + 잠금 재적용
            UUID job = UUID.randomUUID();
            RetentionRecomputeService.Report applied = recompute.run(compliance, RuleVersionId.of("DISC-RET-LONG"), true, job);
            assertThat(applied.count(Outcome.EXTENDED)).isEqualTo(2);
            assertThat(applied.relockPending()).isZero();
            assertThat(retention(r, a)).isEqualTo(afterA);
            assertThat(applied(r, a)).hasSize(8).containsOnly(afterA);
            assertThat(keys(r, a)).allSatisfy(k -> assertThat(r.x.s.store.retention(k)).contains(SealService.retainUntilInstant(LocalDate.parse(afterA))));
            assertThat(r.x.s.store.retentionInsideTransaction).as("잠금은 커밋 뒤, 두 건 × 8객체").hasSize(16).doesNotContain(true);
            assertThat(r.x.w.auditLog()).filteredOn(x -> x.entry().action() == AuditAction.RETENTION_RECOMPUTED).hasSize(2)
                    .filteredOn(x -> x.entry().targetId().equals(a.toString())).singleElement().satisfies(x -> {
                        assertThat(x.entry().detail().get("before").asString()).isEqualTo(beforeA);
                        assertThat(x.entry().detail().get("after").asString()).isEqualTo(afterA);
                        assertThat(x.entry().detail().get("ruleVersionId").asString()).isEqualTo("DISC-RET-LONG");
                        assertThat(x.entry().detail().get("jobId").asString()).isEqualTo(job.toString());
                    });
            // 파기된 확인서는 손대지 않는다
            assertThat(r.x.w.auditLog()).noneMatch(x -> x.entry().action() == AuditAction.RETENTION_RECOMPUTED && x.entry().targetId().equals(gone.toString()));

            // 재실행: 이미 연장된 건은 UNCHANGED(멱등), 감사 추가 없음
            RetentionRecomputeService.Report again = recompute.run(compliance, RuleVersionId.of("DISC-RET-LONG"), true, UUID.randomUUID());
            assertThat(again.items()).allSatisfy(i -> assertThat(i.outcome()).isEqualTo(Outcome.UNCHANGED));
            assertThat(r.x.w.auditLog()).filteredOn(x -> x.entry().action() == AuditAction.RETENTION_RECOMPUTED).hasSize(2);
            assertThat(r.x.s.store.retentionInsideTransaction).as("연장하지 않은 건은 잠금도 다시 걸지 않는다").hasSize(16);

            // 짧은 버전(봉인일만, 1일): 후보는 계산해 보고하지만(지금보다 짧다) 쓰지 않는다
            deploy(r, "DISC-RET-SHORT", "2027-06-01", "DISC-RET-LONG", body -> body.putArray("retentionAnchors").add("SEAL"));
            RetentionRecomputeService.Report shorter = recompute.run(compliance, RuleVersionId.of("DISC-RET-SHORT"), true, UUID.randomUUID());
            assertThat(shorter.items()).allSatisfy(i -> {
                assertThat(i.outcome()).isEqualTo(Outcome.UNCHANGED);
                assertThat(i.candidate()).isBefore(i.before());
            });
            assertThat(retention(r, a)).isEqualTo(afterA);
            assertThat(applied(r, a)).containsOnly(afterA);
            assertThat(r.x.s.store.retentionInsideTransaction).hasSize(16);
            assertThat(com.ga.disclosure.audit.verify.VerifySchemas.retentionRecomputeReport(shorter.toJson())).isEmpty();

            // 쓰기 메서드 자체도 더 길 때만, 파기되지 않은 행만 — 같거나 짧은 값·묘비는 0행(트리거 GD094에 닿기 전에)
            RetentionRecomputeRepository writes = new RetentionRecomputeRepository(r.x.w.gateway, r.x.w.disclosures);
            for (String until : List.of(afterA, beforeA)) {
                assertThat(r.x.w.tx.inTenant(r.x.w.tenant, () -> writes.extendRetention(a, LocalDate.parse(until)))).as(until).isFalse();
            }
            assertThat(r.x.w.tx.inTenant(r.x.w.tenant, () -> writes.extendRetention(gone, LocalDate.parse("2099-12-31")))).isFalse();
            assertThat(retention(r, a)).isEqualTo(afterA);
        }
    }

    @Test
    void onlyADeployedActiveOrApprovedGlobalVersionIsUsableAndOnlyComplianceRunsIt() {
        try (RetentionSetup r = new RetentionSetup()) {
            r.completed();
            deploy(r, "DISC-RET-LONG", "2027-03-01", "DISC-2026-07", body -> body.put("retentionYears", 5));
            RetentionRecomputeService recompute = service(r, RUN);
            Caller compliance = Callers.of(r.x.w.tenant, SealSetup.COMPLIANCE);
            // ACTIVE(지금 시행 중인 버전)도 쓸 수 있다 — 같은 기간이라 전부 UNCHANGED
            assertThat(recompute.run(compliance, RuleVersionId.of("DISC-2026-07"), false, UUID.randomUUID()).items())
                    .allSatisfy(i -> assertThat(i.outcome()).isEqualTo(Outcome.UNCHANGED));
            // 없는 버전·RETIRED는 쓰지 않는다(제출 전 검사와 본체 모두)
            bypass(r, "UPDATE rule_version SET status = 'RETIRED' WHERE tenant_id = ? AND rule_version_id = 'DISC-RET-LONG'", r.x.w.tenant.value());
            for (String id : List.of("DISC-NO-SUCH", "DISC-RET-LONG")) {
                assertThatThrownBy(() -> recompute.admit(compliance, RuleVersionId.of(id))).as(id)
                        .isInstanceOfSatisfying(CommandRejectedException.class, e -> {
                            assertThat(e.code()).isEqualTo("RULE_VERSION_NOT_USABLE");
                            assertThat(e.category().name()).isEqualTo("INVALID");
                        });
                assertThatThrownBy(() -> recompute.run(compliance, RuleVersionId.of(id), true, UUID.randomUUID())).as(id)
                        .isInstanceOf(CommandRejectedException.class);
            }
            // 관리자·설계사는 칸이 없다, 운영자 CLI는 대리 실행
            for (Actor someone : List.of(WorkflowSetup.MANAGER, WorkflowSetup.AGENT)) {
                assertThatThrownBy(() -> recompute.admit(Callers.of(r.x.w.tenant, someone), RuleVersionId.of("DISC-2026-07"))).as(someone.role())
                        .isInstanceOf(AuthorizationDenied.class);
            }
            recompute.admit(Callers.cli(r.x.w.tenant, OPERATOR), RuleVersionId.of("DISC-2026-07"));
        }
    }

    @Test
    void aFailedRelockKeepsTheExtensionAndLeavesTheObjectsForReconcile() {
        try (RetentionSetup r = new RetentionSetup()) {
            DisclosureId a = r.completed();
            deploy(r, "DISC-RET-LONG", "2027-03-01", "DISC-2026-07", body -> body.put("retentionYears", 5));
            String afterA = LocalDate.parse(retention(r, a)).minusDays(1).plusYears(5).plusDays(1).toString();
            r.x.s.store.failRetention.set(true);
            RetentionRecomputeService.Report report = service(r, RUN).run(Callers.of(r.x.w.tenant, SealSetup.COMPLIANCE),
                    RuleVersionId.of("DISC-RET-LONG"), true, UUID.randomUUID());
            assertThat(report.count(Outcome.EXTENDED)).isOne();
            assertThat(report.relockPending()).isOne();
            // 연장은 커밋됐고(감사 포함), 잠금은 뒤처진 채 재적용 대상으로 남는다
            assertThat(retention(r, a)).isEqualTo(afterA);
            assertThat(r.x.w.auditLog()).filteredOn(x -> x.entry().action() == AuditAction.RETENTION_RECOMPUTED).hasSize(1);
            assertThat(r.x.w.auditLog()).filteredOn(x -> x.entry().action() == AuditAction.ARTIFACT_RETAIN_DEFERRED
                    && x.entry().actorSubject().equals(SealSetup.COMPLIANCE.subject())).hasSize(8);
            assertThat(r.x.w.tx.inTenant(r.x.w.tenant, () -> new DocumentRecordRepository(r.x.w.gateway).unretained(100)))
                    .filteredOn(u -> u.record().disclosureId().equals(a)).hasSize(8).allSatisfy(u -> assertThat(u.retentionUntil()).hasToString(afterA));
        }
    }
}
