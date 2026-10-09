package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.infra.persistence.ContractLinkRepository;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.contract.ContractLinkBatch;
import com.ga.disclosure.workflow.contract.ContractLinkBatchParser;
import com.ga.disclosure.workflow.contract.ContractLinkService;
import com.ga.disclosure.workflow.contract.ContractLinkService.Outcome;
import com.ga.disclosure.workflow.contract.ContractLinkService.RetentionChange;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G4(6B 지시문 §3, 계획 §4·§A): 계약 연결 — 매칭 순서(청약번호 → 증권번호의 활성 연결), {@code UNMATCHED}·{@code AMBIGUOUS_MATCH}·{@code NOT_SEALED}·
 * {@code CUSTOMER_MISMATCH}는 보고 행만, 활성 연결 1건과 정정 이력, 확인서 현재값 투영, 보존기한은 앵커 {@code CONTRACT_DATE}로 연장만(앞당겨지는 정정은
 * 그대로 — DB도 단축을 거부), 재수입 멱등, 감사 {@code CONTRACT_LINK_CHANGED}(번호는 해시)·아웃박스 {@code PolicyLinked} v2(번호 없음), 그리고 Phase 5
 * 파기 판정의 계약일 대기가 연결로 끝난다(파기 로직 무변경).
 */
class ContractLinkIT {

    static final Actor FEED_OPERATOR = new Actor("ops-contract-feed@test", "OPERATOR");

    static ContractLinkService service(RetentionSetup r, Clock clock) {
        WorkflowSetup w = r.x.w;
        return new ContractLinkService(new ContractLinkRepository(w.gateway, w.disclosures), new RuleResolver(w.rules), w.audit, w.outbox, w.tx,
                Callers.authz(clock), clock, UUID::randomUUID);
    }

    static Caller feed(RetentionSetup r) {
        return Callers.cli(r.x.w.tenant, FEED_OPERATOR);
    }

    static ContractLinkBatch batch(String batchId, String... items) {
        String json = "{\"schemaVersion\":1,\"source\":\"INS_FEED_A\",\"batchId\":\"" + batchId + "\",\"items\":[" + String.join(",", items) + "]}";
        return ContractLinkBatchParser.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    static String item(String policy, String applicationOrNull, String date) {
        return "{\"policyNo\":\"" + policy + "\"," + (applicationOrNull == null ? "" : "\"applicationNo\":\"" + applicationOrNull + "\",")
                + "\"contractDate\":\"" + date + "\",\"insurerCode\":\"INS-A\"}";
    }

    /** 청약번호는 작성 때만 쓰인다(GD132) — 완료 문서 픽스처에는 트리거를 끄고 넣는다(작성 경로는 {@link #anApplicationNumberIsWrittenOnlyAtCreation}). */
    static void applicationNo(RetentionSetup r, DisclosureId id, String applicationNo) {
        try (Connection c = r.x.w.db.superuserDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("SET LOCAL session_replication_role = replica");
            }
            SeedData.exec(c, "UPDATE disclosure SET application_no = ? WHERE tenant_id = ? AND disclosure_id = ?", applicationNo, r.x.w.tenant.value(), id.value());
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static String row(RetentionSetup r, String sql, Object... params) {
        return r.x.w.db.asApp(r.x.w.tenant.value(), c -> SeedData.call(c, sql, params));
    }

    static String current(RetentionSetup r, DisclosureId id) {
        return row(r, "SELECT policy_no || '|' || contract_date || '|' || retention_until FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?",
                r.x.w.tenant.value(), id.value());
    }

    @Test
    void anApplicationNumberIsWrittenOnlyAtCreation() {
        try (RetentionSetup r = new RetentionSetup()) {
            WorkflowSetup w = r.x.w;
            DisclosureId id = w.service.createDraft(Callers.of(w.tenant, WorkflowSetup.AGENT), w.customer, WorkflowSetup.GROUP, WorkflowSetup.CONSULT,
                    com.ga.disclosure.domain.enums.TemplateType.STANDARD, Optional.of("APP-CREATE-1"));
            assertThat(row(r, "SELECT application_no FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", w.tenant.value(), id.value()))
                    .isEqualTo("APP-CREATE-1");
            assertThat(w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.DISCLOSURE_CREATE && a.entry().targetId().equals(id.toString()))
                    .singleElement().satisfies(a -> {
                        assertThat(a.entry().detail().get("applicationNoSha256").asString())
                                .isEqualTo(Sha256.of("APP-CREATE-1".getBytes(StandardCharsets.UTF_8)));
                        assertThat(a.entry().detail().toString()).doesNotContain("APP-CREATE-1");
                    });
            assertThatThrownBy(() -> w.service.createDraft(Callers.of(w.tenant, WorkflowSetup.AGENT), w.customer, WorkflowSetup.GROUP,
                    WorkflowSetup.CONSULT, com.ga.disclosure.domain.enums.TemplateType.STANDARD, Optional.of("APP WITH SPACE")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("APP WITH SPACE");
        }
    }

    @Test
    void linkCorrectAndReimportKeepOneActiveLinkAndOnlyExtendRetention() {
        try (RetentionSetup r = new RetentionSetup()) {
            DisclosureId d = r.completed();
            applicationNo(r, d, "APP-D1");
            String before = row(r, "SELECT retention_until FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", r.x.w.tenant.value(), d.value());
            ContractLinkService links = service(r, r.x.w.clock);

            // 청약번호로 매칭 → 연결, 현재값 투영, 계약일 앵커로 연장(보존 1일: 2026-09-24 → 2026-09-25)
            ContractLinkService.Report first = links.importBatch(feed(r), batch("B1", item("POL-D1", "APP-D1", "2026-09-24")));
            assertThat(first.items()).singleElement().satisfies(i -> {
                assertThat(i.outcome()).isEqualTo(Outcome.LINKED);
                assertThat(i.retention()).isEqualTo(RetentionChange.EXTENDED);
            });
            assertThat(before).isEqualTo("2026-09-24");
            assertThat(current(r, d)).isEqualTo("POL-D1|2026-09-24|2026-09-25");

            // 같은 배치 재수입 = 그때의 결과(새 행·감사·이벤트 없음), 다른 배치의 같은 내용 = NOOP
            int events = count(r, "SELECT count(*)::text FROM outbox_event WHERE tenant_id = ? AND type = 'PolicyLinked'");
            assertThat(links.importBatch(feed(r), batch("B1", item("POL-D1", "APP-D1", "2026-09-24"))).items().getFirst())
                    .satisfies(i -> assertThat(i.repeated()).isTrue()).extracting(ContractLinkService.ItemResult::outcome).isEqualTo(Outcome.NOOP);
            assertThat(links.importBatch(feed(r), batch("B2", item("POL-D1", "APP-D1", "2026-09-24"))).items().getFirst().outcome()).isEqualTo(Outcome.NOOP);
            assertThat(count(r, "SELECT count(*)::text FROM outbox_event WHERE tenant_id = ? AND type = 'PolicyLinked'")).isEqualTo(events);

            // 증권번호의 활성 연결로 매칭되는 정정 — 계약일이 앞당겨져도 보존기한은 줄지 않는다
            ContractLinkService.Report corrected = links.importBatch(feed(r), batch("B3", item("POL-D1", null, "2026-09-20")));
            assertThat(corrected.items().getFirst().outcome()).isEqualTo(Outcome.CORRECTED);
            assertThat(corrected.items().getFirst().retention()).isEqualTo(RetentionChange.NOT_EXTENDED);
            assertThat(current(r, d)).isEqualTo("POL-D1|2026-09-20|2026-09-25");
            assertThat(row(r, """
                    SELECT count(*) || '|' || count(*) FILTER (WHERE superseded_by IS NULL) FROM contract_link WHERE tenant_id = ? AND disclosure_id = ?""",
                    r.x.w.tenant.value(), d.value())).isEqualTo("2|1");
            // DB도 단축을 거부한다(GD094)
            assertThat(TriggerAssertions.sqlStateOf(() -> r.x.w.db.asApp(r.x.w.tenant.value(), c -> SeedData.exec(c,
                    "UPDATE disclosure SET retention_until = DATE '2026-09-21' WHERE tenant_id = ? AND disclosure_id = ?", r.x.w.tenant.value(), d.value()))))
                    .isEqualTo("GD094");

            // 감사: 첫 연결도 정정도 CONTRACT_LINK_CHANGED, 번호는 해시뿐 · 이벤트 v2에는 번호가 없다
            assertThat(r.x.w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.CONTRACT_LINK_CHANGED)
                    .extracting(a -> a.entry().detail().get("outcome").asString()).containsExactly("LINKED", "CORRECTED");
            assertThat(r.x.w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.CONTRACT_LINK_CHANGED || a.entry().action() == AuditAction.CONTRACT_LINK_IMPORT)
                    .allSatisfy(a -> assertThat(a.entry().detail().toString()).doesNotContain("POL-D1").doesNotContain("APP-D1"));
            assertThat(row(r, """
                    SELECT string_agg(version || ':' || (payload ->> 'corrected'), ',' ORDER BY seq) FROM outbox_event WHERE tenant_id = ? AND type = 'PolicyLinked'""",
                    r.x.w.tenant.value())).isEqualTo("2:false,2:true");
            assertThat(row(r, "SELECT string_agg(payload::text, '') FROM outbox_event WHERE tenant_id = ? AND type = 'PolicyLinked'", r.x.w.tenant.value()))
                    .doesNotContain("POL-D1").doesNotContain("APP-D1");
        }
    }

    @Test
    void unmatchedAmbiguousUnsealedAndMismatchedItemsOnlyLeaveReportRows() {
        try (RetentionSetup r = new RetentionSetup()) {
            DisclosureId linked = r.completed();
            applicationNo(r, linked, "APP-L");
            DisclosureId dup1 = r.completed();
            DisclosureId dup2 = r.completed();
            applicationNo(r, dup1, "APP-DUP");
            applicationNo(r, dup2, "APP-DUP");
            DisclosureId other = r.completed();
            applicationNo(r, other, "APP-OTHER");
            DisclosureId draft = r.x.w.service.createDraft(Callers.of(r.x.w.tenant, WorkflowSetup.AGENT), r.x.w.customer, WorkflowSetup.GROUP,
                    WorkflowSetup.CONSULT, com.ga.disclosure.domain.enums.TemplateType.STANDARD, Optional.of("APP-DRAFT"));
            ContractLinkService links = service(r, r.x.w.clock);
            links.importBatch(feed(r), batch("SETUP", item("POL-L", "APP-L", "2026-09-24")));

            ContractLinkService.Report report = links.importBatch(feed(r), batch("MIX",
                    item("POL-NONE", null, "2026-09-24"),                              // 매칭 0건
                    item("POL-DUP", "APP-DUP", "2026-09-24"),                          // 청약번호 2건
                    item("POL-DRAFT", "APP-DRAFT", "2026-09-24"),                      // 봉인 전
                    "{\"policyNo\":\"POL-CUST\",\"applicationNo\":\"APP-OTHER\",\"contractDate\":\"2026-09-24\",\"insurerCode\":\"INS-A\",\"customerRef\":\"C-OTHER\"}",
                    item("POL-L", "APP-OTHER", "2026-09-24"),                          // 그 증권은 다른 확인서에 활성
                    item("POL-OK", "APP-OTHER", "2026-09-24")));
            assertThat(report.items()).extracting(ContractLinkService.ItemResult::outcome).containsExactly(Outcome.UNMATCHED, Outcome.AMBIGUOUS_MATCH,
                    Outcome.NOT_SEALED, Outcome.CUSTOMER_MISMATCH, Outcome.AMBIGUOUS_MATCH, Outcome.LINKED);
            assertThat(row(r, "SELECT string_agg(reason, ',' ORDER BY source_ref) FROM contract_link_unmatched WHERE tenant_id = ?", r.x.w.tenant.value()))
                    .isEqualTo("UNMATCHED,AMBIGUOUS_MATCH,NOT_SEALED,CUSTOMER_MISMATCH,AMBIGUOUS_MATCH");
            for (DisclosureId untouched : List.of(dup1, dup2, draft)) {
                assertThat(row(r, "SELECT coalesce(policy_no, '-') FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", r.x.w.tenant.value(),
                        untouched.value())).isEqualTo("-");
            }
            assertThat(current(r, linked)).startsWith("POL-L|");
            assertThat(current(r, other)).startsWith("POL-OK|");
            // 요약 감사: 결과별 수, 번호 없음
            assertThat(r.x.w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.CONTRACT_LINK_IMPORT
                    && a.entry().detail().get("batchId").asString().equals("MIX")).singleElement().satisfies(a -> {
                assertThat(a.entry().detail().at("/counts/AMBIGUOUS_MATCH").asInt()).isEqualTo(2);
                assertThat(a.entry().detail().at("/counts/LINKED").asInt()).isOne();
                assertThat(a.entry().detail().toString()).doesNotContain("POL-");
            });
            // 보고서 JSON에도 번호가 없다
            assertThat(new String(Canonicalizer.canonicalize(report.toJson()), StandardCharsets.UTF_8)).doesNotContain("POL-").doesNotContain("APP-");
        }
    }

    /**
     * 6B 중간 회신 ①: 배치 참조(출처·배치 ID)마다 내용(JCS 해시) 하나. 같은 내용 재전송은 재생(새 행 없음, {@code replayed}), 다른 내용은 배치 전체 거부
     * {@code BATCH_REF_REUSED}(항목 하나도 처리하지 않음, 감사 1행 — 번호 없음). 같은 내용이면 CSV든 JSON이든 같은 해시다.
     */
    @Test
    void aBatchReferenceCarriesOneContentAndADifferentContentIsRejectedWhole() {
        try (RetentionSetup r = new RetentionSetup()) {
            DisclosureId d = r.completed();
            applicationNo(r, d, "APP-R1");
            ContractLinkService links = service(r, r.x.w.clock);
            ContractLinkService.Report first = links.importBatch(feed(r), batch("R1", item("POL-R1", "APP-R1", "2026-09-24")));
            assertThat(first.replayed()).isFalse();
            assertThat(first.items().getFirst().outcome()).isEqualTo(Outcome.LINKED);
            assertThat(row(r, "SELECT content_sha256 || '|' || (summary ->> 'LINKED') FROM contract_link_batch WHERE tenant_id = ? AND batch_id = 'R1'",
                    r.x.w.tenant.value())).isEqualTo(first.sha256() + "|1");

            ContractLinkService.Report again = links.importBatch(feed(r), batch("R1", item("POL-R1", "APP-R1", "2026-09-24")));
            assertThat(again.replayed()).isTrue();
            assertThat(again.items().getFirst().repeated()).isTrue();

            int unmatched = count(r, "SELECT count(*)::text FROM contract_link_unmatched WHERE tenant_id = ?");
            assertThatThrownBy(() -> links.importBatch(feed(r), batch("R1", item("POL-R1-OTHER", null, "2026-09-24"))))
                    .isInstanceOfSatisfying(com.ga.disclosure.workflow.disclosure.CommandRejectedException.class,
                            e -> assertThat(e.code()).isEqualTo(ContractLinkService.BATCH_REF_REUSED));
            assertThat(count(r, "SELECT count(*)::text FROM contract_link_unmatched WHERE tenant_id = ?")).isEqualTo(unmatched);
            assertThat(r.x.w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.CONTRACT_LINK_BATCH_REJECTED).singleElement().satisfies(a -> {
                assertThat(a.entry().detail().get("recordedSha256").asString()).isEqualTo(first.sha256());
                assertThat(a.entry().detail().toString()).doesNotContain("POL-");
            });
            assertThat(r.x.w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.CONTRACT_LINK_IMPORT).hasSize(2);

            // 같은 내용이면 형식과 무관하게 같은 해시(JCS)
            byte[] csv = "policyNo,applicationNo,contractDate,insurerCode,customerRef,productKey\nPOL-R1,APP-R1,2026-09-24,INS-A,,\n"
                    .getBytes(StandardCharsets.UTF_8);
            assertThat(com.ga.disclosure.workflow.contract.ContractLinkCsv.parse("INS_FEED_A", "R1", csv).sha256()).isEqualTo(first.sha256());
        }
    }

    /**
     * 활성 연결은 확인서가 무효·정정된 뒤에도 그 확인서에 남는다(이력). 같은 청약의 새 확인서가 같은 증권으로 매칭돼도 "다른 확인서에 활성" 판정은 후보 제외
     * 규칙과 무관하게 활성 연결 전체를 본다 — 아니면 증권 부분 유일 위반이 항목 하나로 배치 전체를 매번 멈춘다.
     */
    @Test
    void aPolicyStillHeldByAVoidedDisclosureIsReportedWithoutStoppingTheBatch() {
        try (RetentionSetup r = new RetentionSetup()) {
            DisclosureId voided = r.completed();
            applicationNo(r, voided, "APP-V");
            ContractLinkService links = service(r, r.x.w.clock);
            assertThat(links.importBatch(feed(r), batch("V1", item("POL-V", "APP-V", "2026-09-24"))).items().getFirst().outcome()).isEqualTo(Outcome.LINKED);
            assertThat(r.x.lifecycle.voidDisclosure(Callers.of(r.x.w.tenant, SealSetup.MANAGER), voided,
                    new com.ga.disclosure.workflow.disclosure.LifecycleReason("OTHER", "가상 무효 메모 — 허구")).rejection()).isEmpty();
            DisclosureId redo = r.completed();
            applicationNo(r, redo, "APP-V");
            DisclosureId other = r.completed();
            applicationNo(r, other, "APP-W");

            ContractLinkService.Report report = links.importBatch(feed(r), batch("V2", item("POL-V", "APP-V", "2026-09-24"),
                    item("POL-W", "APP-W", "2026-09-24")));
            assertThat(report.items()).extracting(ContractLinkService.ItemResult::outcome).containsExactly(Outcome.AMBIGUOUS_MATCH, Outcome.LINKED);
            assertThat(row(r, "SELECT coalesce(policy_no, '-') FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", r.x.w.tenant.value(),
                    redo.value())).isEqualTo("-");
            assertThat(current(r, other)).startsWith("POL-W|");
            assertThat(r.x.w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.CONTRACT_LINK_IMPORT
                    && a.entry().detail().get("batchId").asString().equals("V2")).hasSize(1);
        }
    }

    @Test
    void aLinkEndsTheContractDateWaitOfDestruction() {
        try (RetentionSetup r = new RetentionSetup(body -> ((tools.jackson.databind.node.ObjectNode) body.get("retention")).put("contractLinkWaitDays", 365))) {
            DisclosureId d = r.completed();
            applicationNo(r, d, "APP-WAIT");
            r.reconcileAfterRetention();
            com.ga.disclosure.workflow.retention.DestructionJob.Report waiting = r.destroy();
            assertThat(waiting.destroyed()).isEmpty();
            assertThat(waiting.skipped()).extracting(s -> s.id()).contains(d);
            service(r, r.x.w.clock).importBatch(feed(r), batch("WAIT", item("POL-WAIT", "APP-WAIT", "2026-09-24")));
            r.reconcileAfterRetention();
            assertThat(r.destroy().destroyed()).extracting(x -> x.id()).containsExactly(d);
            // 파기는 연결 이력의 번호까지 지운다(V14) — 감사에는 해시만(V15)
            assertThat(row(r, "SELECT count(*) FILTER (WHERE policy_no IS NULL)::text FROM contract_link WHERE tenant_id = ? AND disclosure_id = ?",
                    r.x.w.tenant.value(), d.value())).isEqualTo("1");
        }
    }

    /** 미매칭 보고 행(증권번호)은 룰 기간 뒤 지운다 — 룰 값이 null이면 지우지 않는다(실값 미정, §14 #16). 배치 역할만. */
    @Test
    void unmatchedReportRowsArePurgedOnlyAfterTheRulePeriod() {
        try (RetentionSetup none = new RetentionSetup()) {
            service(none, none.x.w.clock).importBatch(feed(none), batch("U", item("POL-U", null, "2026-09-24")));
            Clock later = Clock.fixed(none.x.w.clock.instant().plus(java.time.Duration.ofDays(400)), none.x.w.clock.getZone());
            ContractLinkService.PurgeReport kept = service(none, later).purgeUnmatched(feed(none), 100);
            assertThat(kept.retentionDays()).isEmpty();
            assertThat(kept.purged()).isZero();
            assertThat(count(none, "SELECT count(*)::text FROM contract_link_unmatched WHERE tenant_id = ?")).isOne();
        }
        try (RetentionSetup r = new RetentionSetup(body -> ((tools.jackson.databind.node.ObjectNode) body.get("contractLink")).put("unmatchedRetentionDays", 30))) {
            service(r, r.x.w.clock).importBatch(feed(r), batch("U", item("POL-U1", null, "2026-09-24"), item("POL-U2", null, "2026-09-24")));
            Clock day29 = Clock.fixed(r.x.w.clock.instant().plus(java.time.Duration.ofDays(29)), r.x.w.clock.getZone());
            assertThat(service(r, day29).purgeUnmatched(feed(r), 100).purged()).isZero();
            Clock day31 = Clock.fixed(r.x.w.clock.instant().plus(java.time.Duration.ofDays(31)), r.x.w.clock.getZone());
            assertThat(service(r, day31).purgeUnmatched(feed(r), 1).purged()).as("limit").isOne();
            assertThat(service(r, day31).purgeUnmatched(feed(r), 100).purged()).isOne();
            assertThat(count(r, "SELECT count(*)::text FROM contract_link_unmatched WHERE tenant_id = ?")).isZero();
            assertThat(r.x.w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.CONTRACT_LINK_UNMATCHED_PURGE)
                    .extracting(a -> a.entry().detail().get("purged").asInt()).containsExactly(0, 1, 1);
            assertThatThrownBy(() -> service(r, day31).purgeUnmatched(Callers.of(r.x.w.tenant, SealSetup.COMPLIANCE), 100))
                    .isInstanceOf(com.ga.disclosure.workflow.authz.AuthorizationDenied.class);
        }
    }

    static int count(RetentionSetup r, String sql) {
        return Integer.parseInt(row(r, sql, r.x.w.tenant.value()));
    }
}
