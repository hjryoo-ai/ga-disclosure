package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.domain.disclosure.AgentReason;
import com.ga.disclosure.domain.disclosure.IllegalTransition;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.infra.persistence.IdleDraftRepository;
import com.ga.disclosure.infra.retention.AbandonGateway;
import com.ga.disclosure.infra.retention.ErasureRepository;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.workflow.authz.AuthorizationDenied;
import com.ga.disclosure.workflow.disclosure.DisclosureService;
import com.ga.disclosure.workflow.disclosure.DraftAbandonService;
import com.ga.disclosure.workflow.disclosure.IdleDraftStore;
import com.ga.disclosure.workflow.disclosure.LifecycleReason;
import com.ga.disclosure.workflow.disclosure.LifecycleService;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G6(6B 지시문 §6, 계획 §2.2·§3): 초안 폐기. 명시 폐기(작성 설계사 + 고정 룰 사유 코드)와 방치 초안 배치(실행 시점 룰 {@code draft.abandonAfterDays},
 * null이면 0건). 묘비는 행을 지우지 않고 {@code pii-columns} 블록의 {@code ga_draft_abandon} 대상 컬럼만 비우며, 감사 {@code DRAFT_ABANDONED}의 지운
 * 값 표현은 폐기 전 행에서 따로 계산한 값과 같다(평문 없음). 문서 상태로 닫는 플래그는 닫히고 엔진 이상 플래그는 남는다. 아웃박스
 * {@code DisclosureAbandoned}. 이후 어떤 명령도 그 초안을 바꾸지 못한다. DB 가드·롤 권한은 {@code V14GuardIT}가 본다.
 */
class AbandonDraftIT {

    /** 폐기가 지우는 컬럼이 있는 테이블(행 비교 대상). */
    static final Map<String, Set<String>> ERASABLE = Map.of(
            "disclosure", Set.of("application_no", "status", "abandoned_at"),
            "disclosure_item", Set.of("field_values"),
            "recommendation", Set.of("reason_text"),
            "review", Set.of("reason"));

    static DraftAbandonService service(WorkflowSetup w, Clock clock) {
        return service(w, clock, new IdleDraftRepository(w.gateway));
    }

    static DraftAbandonService service(WorkflowSetup w, Clock clock, IdleDraftStore idle) {
        return new DraftAbandonService(w.deps(clock), new AbandonGateway(w.db.appDataSource()), new ErasureRepository(w.gateway), idle,
                new com.ga.disclosure.infra.retention.LegalHoldRepository(w.gateway));
    }

    static Clock later(WorkflowSetup w, Duration by) {
        return Clock.fixed(w.clock.instant().plus(by), w.clock.getZone());
    }

    /** 추천사유 텍스트·청약번호·항목 입력값이 있는 REASONED 초안 + 예외 승인 사유(검토 행 — 픽스처) + 열린 플래그 둘. */
    static DisclosureId reasonedWithFreeText(WorkflowSetup w, String applicationNo) {
        DisclosureService s = w.service;
        DisclosureId id = s.createDraft(Callers.of(w.tenant, WorkflowSetup.AGENT), w.customer, WorkflowSetup.GROUP, WorkflowSetup.CONSULT,
                TemplateType.STANDARD, Optional.of(applicationNo));
        s.replaceItems(Callers.of(w.tenant, WorkflowSetup.AGENT), id, WorkflowSetup.threeItems());
        s.compare(Callers.of(w.tenant, WorkflowSetup.AGENT), id);
        s.requestGrades(Callers.of(w.tenant, WorkflowSetup.AGENT), id);
        s.setRecommendations(Callers.of(w.tenant, WorkflowSetup.AGENT), id, List.of(
                new AgentReason(1, List.of(ReasonCode.of("PREMIUM")), "가상 추천 메모 — 허구 1"),
                new AgentReason(3, List.of(ReasonCode.of("COVERAGE")), "가상 추천 메모 — 허구 3")));
        bypass(w, c -> {
            SeedData.review(c, w.tenant.value(), id.value(), "R-GRADE-UNAVAILABLE");
            for (String type : List.of("VALIDATION_OVERRIDE", "GRADE_INCONSISTENT")) {
                SeedData.exec(c, """
                        INSERT INTO compliance_flag (tenant_id, flag_id, type, disclosure_id, severity, raised_at, target_kind, target_id)
                        VALUES (?, gen_random_uuid(), ?, ?, 'MEDIUM', now(), 'DISCLOSURE', ?)""", w.tenant.value(), type, id.value(), id.toString());
            }
        });
        return id;
    }

    interface Sql {
        void run(Connection c) throws SQLException;
    }

    static void bypass(WorkflowSetup w, Sql sql) {
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

    static List<JsonNode> rows(WorkflowSetup w, String table, DisclosureId id) {
        try (Connection c = w.db.superuserDataSource().getConnection();
             var ps = c.prepareStatement("SELECT to_jsonb(t)::text FROM " + table
                     + " t WHERE t.tenant_id = ? AND t.disclosure_id = ? ORDER BY (to_jsonb(t) - ?::text[])::text")) {
            ps.setString(1, w.tenant.value());
            ps.setObject(2, id.value());
            ps.setArray(3, c.createArrayOf("text", ERASABLE.get(table).toArray()));
            List<JsonNode> out = new ArrayList<>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(Canonicalizer.parseStrict(rs.getString(1)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    static String row(WorkflowSetup w, String sql, Object... params) {
        return w.db.asApp(w.tenant.value(), c -> SeedData.call(c, sql, params));
    }

    static String utf8(String v) {
        return "sha256-utf8:" + Sha256.of(v.getBytes(StandardCharsets.UTF_8));
    }

    /** 폐기 전 행에서 감사 표현을 따로 계산한다. */
    static Set<String> expected(Map<String, List<JsonNode>> before) {
        Set<String> out = new HashSet<>();
        before.get("disclosure").stream().filter(d -> !d.get("application_no").isNull())
                .forEach(d -> out.add("disclosure.application_no=" + utf8(d.get("application_no").asString())));
        before.get("recommendation").stream().filter(x -> !x.get("reason_text").isNull())
                .forEach(x -> out.add("recommendation.reason_text=" + utf8(x.get("reason_text").asString())));
        before.get("review").stream().filter(x -> !x.get("reason").isNull())
                .forEach(x -> out.add("review.reason=" + utf8(x.get("reason").asString())));
        before.get("disclosure_item").stream().filter(x -> !x.get("field_values").isEmpty())
                .forEach(x -> out.add("disclosure_item.field_values=sha256-jcs:" + Sha256.of(Canonicalizer.canonicalize(x.get("field_values")))));
        return out;
    }

    static Set<String> erasedInAudit(WorkflowSetup w, DisclosureId id) {
        Set<String> out = new HashSet<>();
        w.auditLog().stream().filter(a -> a.entry().action() == AuditAction.DRAFT_ABANDONED && a.entry().targetId().equals(id.toString()))
                .forEach(a -> a.entry().detail().get("erased").forEach(e -> out.add(e.get("table").asString() + "." + e.get("column").asString() + "="
                        + e.get("repr").asString() + ":" + e.get("value").asString())));
        return out;
    }

    @Test
    void anExplicitAbandonmentLeavesATombstoneWithOnlyTheDesignatedValuesErased() {
        try (WorkflowSetup w = new WorkflowSetup()) {
            DisclosureId id = reasonedWithFreeText(w, "APP-ABANDON-1");
            Map<String, List<JsonNode>> before = new java.util.TreeMap<>();
            ERASABLE.keySet().forEach(t -> before.put(t, rows(w, t, id)));
            Set<String> expected = expected(before);
            // 픽스처가 pii-columns 블록의 폐기 대상 컬럼을 빠짐없이 채웠다 — 블록에 새 행이 생기면 감사 해시까지 시험돼야 통과한다
            Set<String> blockColumns = new TreeSet<>();
            PiiColumnTableTest.block().stream().filter(row -> row.erasedBy().contains("ga_draft_abandon")).forEach(row -> blockColumns.add(row.key()));
            assertThat(new TreeSet<>(expected.stream().map(e -> e.substring(0, e.indexOf('='))).toList())).isEqualTo(blockColumns);

            Clock at = later(w, Duration.ofHours(1));
            LifecycleService.Outcome o = service(w, at).abandon(Callers.of(w.tenant, WorkflowSetup.AGENT), id, "CUSTOMER_DECLINED");
            assertThat(o.applied()).isTrue();
            assertThat(o.status()).isEqualTo(DisclosureStatus.ABANDONED);

            for (String table : ERASABLE.keySet()) {
                List<JsonNode> after = rows(w, table, id);
                assertThat(after).as(table + " rows are kept").hasSameSizeAs(before.get(table));
                for (int i = 0; i < after.size(); i++) {
                    JsonNode was = before.get(table).get(i);
                    JsonNode now = after.get(i);
                    for (var f = was.propertyNames().iterator(); f.hasNext(); ) {
                        String column = f.next();
                        if (!ERASABLE.get(table).contains(column)) {
                            assertThat(now.get(column)).as(table + "." + column + " is kept").isEqualTo(was.get(column));
                        }
                    }
                }
            }
            JsonNode tomb = rows(w, "disclosure", id).getFirst();
            assertThat(tomb.get("status").asString()).isEqualTo("ABANDONED");
            assertThat(java.time.OffsetDateTime.parse(tomb.get("abandoned_at").asString()).toInstant()).isEqualTo(at.instant());
            assertThat(tomb.get("application_no").isNull()).isTrue();
            assertThat(rows(w, "recommendation", id)).allSatisfy(x -> assertThat(x.get("reason_text").isNull()).isTrue());
            assertThat(rows(w, "review", id)).allSatisfy(x -> assertThat(x.get("reason").isNull()).isTrue());
            assertThat(rows(w, "disclosure_item", id)).allSatisfy(x -> assertThat(x.get("field_values").isEmpty()).isTrue());

            // 감사: 지운 값 표현 = 따로 계산한 값, 평문 없음 · 계기·사유 코드 · 룰 버전
            assertThat(erasedInAudit(w, id)).isEqualTo(expected);
            assertThat(w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.DRAFT_ABANDONED).singleElement().satisfies(a -> {
                assertThat(a.entry().actorRole()).isEqualTo("AGENT");
                assertThat(a.entry().detail().get("trigger").asString()).isEqualTo("EXPLICIT");
                assertThat(a.entry().detail().get("reasonCode").asString()).isEqualTo("CUSTOMER_DECLINED");
                assertThat(a.entry().detail().get("from").asString()).isEqualTo("REASONED");
                assertThat(a.entry().detail().toString()).doesNotContain("APP-ABANDON-1").doesNotContain("허구");
            });
            // 문서 상태로 닫는 플래그는 닫히고, 엔진 이상 신호는 남는다
            assertThat(row(w, """
                    SELECT string_agg(type || ':' || coalesce(resolution, 'OPEN'), ',' ORDER BY type) FROM compliance_flag WHERE tenant_id = ? AND disclosure_id = ?""",
                    w.tenant.value(), id.value())).isEqualTo("GRADE_INCONSISTENT:OPEN,VALIDATION_OVERRIDE:SUPERSEDED_BY_DOCUMENT_STATE");
            // 아웃박스: 식별자·시각뿐
            assertThat(row(w, """
                    SELECT version || '|' || (payload ->> 'disclosureId') || '|' || (payload ->> 'abandonedAt')
                      FROM outbox_event WHERE tenant_id = ? AND type = 'DisclosureAbandoned'""", w.tenant.value()))
                    .isEqualTo("1|" + id + "|" + at.instant());

            // 묘비는 종단 상태 — 어떤 명령도, 두 번째 폐기도 바꾸지 못한다
            assertThatThrownBy(() -> service(w, at).abandon(Callers.of(w.tenant, WorkflowSetup.AGENT), id, "DUPLICATE")).isInstanceOf(IllegalTransition.class);
            assertThatThrownBy(() -> w.service.compare(Callers.of(w.tenant, WorkflowSetup.AGENT), id)).isInstanceOf(IllegalTransition.class);
            assertThat(w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.DRAFT_ABANDONED).hasSize(1);
        }
    }

    @Test
    void anUnknownReasonAnotherRoleOrAClosedDocumentDoesNotAbandon() {
        try (WorkflowSetup w = new WorkflowSetup()) {
            DisclosureId id = w.compared();
            DraftAbandonService drafts = service(w, w.clock);

            LifecycleService.Outcome unknown = drafts.abandon(Callers.of(w.tenant, WorkflowSetup.AGENT), id, "NOT_A_RULE_CODE");
            assertThat(unknown.rejection()).contains(LifecycleService.Rejection.REASON_CODE_UNKNOWN);
            assertThat(unknown.status()).isEqualTo(DisclosureStatus.COMPARED);
            assertThat(w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.DISCLOSURE_REJECT).singleElement().satisfies(a -> {
                assertThat(a.entry().detail().get("reason").asString()).isEqualTo("REASON_CODE_UNKNOWN");
                assertThat(a.entry().detail().toString()).doesNotContain("NOT_A_RULE_CODE");
            });

            // 관리자 칸은 없다(인가 표) — 없는 자원과 같은 거부
            assertThatThrownBy(() -> drafts.abandon(Callers.of(w.tenant, WorkflowSetup.MANAGER), id, "DUPLICATE")).isInstanceOf(AuthorizationDenied.class);

            // 무효된 초안(종결)은 폐기 대상이 아니다
            DisclosureId voided = w.draft();
            new LifecycleService(w.deps(w.clock), new com.ga.disclosure.infra.persistence.SignSessionRepository(w.gateway))
                    .voidDisclosure(Callers.of(w.tenant, WorkflowSetup.AGENT), voided, new LifecycleReason("DUPLICATE", null));
            assertThatThrownBy(() -> drafts.abandon(Callers.of(w.tenant, WorkflowSetup.AGENT), voided, "DUPLICATE")).isInstanceOf(IllegalTransition.class);

            assertThat(row(w, "SELECT count(*)::text FROM disclosure WHERE tenant_id = ? AND status = 'ABANDONED'",
                    w.tenant.value())).isEqualTo("0");
            assertThat(row(w, "SELECT count(*)::text FROM outbox_event WHERE tenant_id = ? AND type = 'DisclosureAbandoned'",
                    w.tenant.value())).isEqualTo("0");
        }
    }

    static void hold(WorkflowSetup w, String column, Object target) {
        w.db.seed(w.tenant.value(), c -> SeedData.exec(c, "INSERT INTO legal_hold (tenant_id, hold_id, " + column
                + ", reason_code, placed_by, placed_at) VALUES (?, gen_random_uuid(), ?, 'LITIGATION', 'ops', now())", w.tenant.value(), target));
    }

    /** 보류(확인서 또는 그 고객)가 걸린 초안은 지우지 않는다 — 앱은 업무 거부, 함수도 GD137(파기 전제와 같은 조건). */
    @Test
    void aDraftUnderLegalHoldIsNeverAbandoned() {
        try (WorkflowSetup w = new WorkflowSetup()) {
            DisclosureId heldDraft = reasonedWithFreeText(w, "APP-HELD-1");
            hold(w, "disclosure_id", heldDraft.value());
            DraftAbandonService drafts = service(w, w.clock);
            LifecycleService.Outcome o = drafts.abandon(Callers.of(w.tenant, WorkflowSetup.AGENT), heldDraft, "DUPLICATE");
            assertThat(o.rejection()).contains(LifecycleService.Rejection.UNDER_LEGAL_HOLD);
            assertThat(row(w, "SELECT status || '|' || application_no FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", w.tenant.value(),
                    heldDraft.value())).isEqualTo("REASONED|APP-HELD-1");
            // 함수도 다시 본다(앱 검사를 건너뛴 호출)
            assertThatThrownBy(() -> w.in(() -> {
                new AbandonGateway(w.db.appDataSource()).abandon(heldDraft, w.clock.instant(), "bypass@test");
                return null;
            })).isInstanceOfSatisfying(com.ga.disclosure.workflow.disclosure.AbandonRefusedException.class,
                    e -> assertThat(e.sqlState()).isEqualTo("GD137"));

            // 고객 보류도 같다 — 그 고객의 다른 초안
            DisclosureId customerDraft = w.compared();
            hold(w, "customer_ref", w.customer.value());
            assertThat(drafts.abandon(Callers.of(w.tenant, WorkflowSetup.AGENT), customerDraft, "DUPLICATE").rejection())
                    .contains(LifecycleService.Rejection.UNDER_LEGAL_HOLD);
            assertThat(w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.DISCLOSURE_REJECT)
                    .extracting(a -> a.entry().detail().get("reason").asString()).containsOnly("UNDER_LEGAL_HOLD").hasSize(2);
            assertThat(w.auditLog()).noneMatch(a -> a.entry().action() == AuditAction.DRAFT_ABANDONED);
        }
    }

    @Test
    void heldDraftsAreCountedAndKeptByTheIdleBatch() {
        try (WorkflowSetup w = WorkflowSetup.withRule(body -> ((tools.jackson.databind.node.ObjectNode) body.get("draft")).put("abandonAfterDays", 30))) {
            DisclosureId kept = w.draft();
            DisclosureId gone = w.draft();
            hold(w, "disclosure_id", kept.value());
            DraftAbandonService.BatchReport r = service(w, later(w, Duration.ofDays(31))).abandonIdle(Callers.cli(w.tenant, RetentionSetup.OPERATOR), 100);
            assertThat(r.abandoned()).containsExactly(gone);
            assertThat(r.held()).isOne();
            assertThat(r.skipped()).isZero();
            assertThat(row(w, "SELECT status FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", w.tenant.value(), kept.value())).isEqualTo("DRAFT");
            assertThat(w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.DRAFT_ABANDON_BATCH).singleElement()
                    .satisfies(a -> assertThat(a.entry().detail().get("held").asInt()).isOne());
        }
    }

    @Test
    void withoutARuleValueTheIdleBatchAbandonsNothing() {
        try (WorkflowSetup w = new WorkflowSetup()) {
            DisclosureId id = w.draft();
            DraftAbandonService.BatchReport r = service(w, later(w, Duration.ofDays(3650))).abandonIdle(Callers.cli(w.tenant, RetentionSetup.OPERATOR), 100);
            assertThat(r.abandonAfterDays()).isEmpty();
            assertThat(r.abandoned()).isEmpty();
            assertThat(row(w, "SELECT status FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?",
                    w.tenant.value(), id.value())).isEqualTo("DRAFT");
            assertThat(w.auditLog()).noneMatch(a -> a.entry().action() == AuditAction.DRAFT_ABANDON_BATCH);
        }
    }

    @Test
    void theIdleBatchAbandonsOnlyDraftsUnchangedForTheRulePeriodAndRechecksUnderTheLock() {
        try (WorkflowSetup w = WorkflowSetup.withRule(body -> ((tools.jackson.databind.node.ObjectNode) body.get("draft")).put("abandonAfterDays", 30))) {
            DisclosureId idle = w.compared();                                           // 마지막 변경 = T0
            DisclosureId touched = w.draft();                                           // T0 작성, T0+20일 항목 교체
            w.serviceAt(later(w, Duration.ofDays(20))).replaceItems(Callers.of(w.tenant, WorkflowSetup.AGENT), touched, WorkflowSetup.threeItems());
            DisclosureId racing = w.draft();                                            // 선택 뒤·잠금 전에 고쳐진다
            DisclosureId sealedLike = w.draft();
            new LifecycleService(w.deps(w.clock), new com.ga.disclosure.infra.persistence.SignSessionRepository(w.gateway))
                    .voidDisclosure(Callers.of(w.tenant, WorkflowSetup.AGENT), sealedLike, new LifecycleReason("DUPLICATE", null));   // 종결 — 후보 아님

            // 열람은 변경이 아니다 — 25일째 열람 감사가 있어도 방치 초안이다
            w.in(() -> {
                w.audit.append(new com.ga.disclosure.audit.AuditEntry(w.clock.instant().plus(Duration.ofDays(25)), WorkflowSetup.AGENT.subject(), "AGENT",
                        AuditAction.DISCLOSURE_VIEW, "DISCLOSURE", idle.toString(), tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode()));
                return null;
            });
            Clock run = later(w, Duration.ofDays(31));
            IdleDraftRepository repo = new IdleDraftRepository(w.gateway);
            IdleDraftStore racingStore = new IdleDraftStore() {
                @Override
                public List<Idle> idleSince(Instant changedBefore, Set<String> changeActions, int limit) {
                    List<Idle> found = repo.idleSince(changedBefore, changeActions, limit);
                    w.serviceAt(run).compare(Callers.of(w.tenant, WorkflowSetup.AGENT), racing);    // 다른 트랜잭션처럼 — 선택 뒤 변경
                    return found;
                }

                @Override
                public Optional<Instant> lastChange(DisclosureId disclosure, Set<String> changeActions) {
                    return repo.lastChange(disclosure, changeActions);
                }
            };
            w.service.replaceItems(Callers.of(w.tenant, WorkflowSetup.AGENT), racing, WorkflowSetup.threeItems());

            DraftAbandonService.BatchReport r = service(w, run, racingStore).abandonIdle(Callers.cli(w.tenant, RetentionSetup.OPERATOR), 100);

            assertThat(r.abandonAfterDays()).hasValue(30);
            assertThat(r.changedBefore()).contains(run.instant().minus(Duration.ofDays(30)));
            assertThat(r.abandoned()).containsExactly(idle);
            assertThat(r.skipped()).isOne();                                            // racing
            for (var e : Map.of(idle, "ABANDONED", touched, "DRAFT", racing, "COMPARED", sealedLike, "VOID").entrySet()) {
                assertThat(row(w, "SELECT status FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?",
                        w.tenant.value(), e.getKey().value())).as(e.getKey().toString()).isEqualTo(e.getValue());
            }
            assertThat(w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.DRAFT_ABANDONED).singleElement().satisfies(a -> {
                assertThat(a.entry().targetId()).isEqualTo(idle.toString());
                assertThat(a.entry().actorRole()).isEqualTo("OPERATOR");
                assertThat(a.entry().detail().get("trigger").asString()).isEqualTo("IDLE");
                assertThat(a.entry().detail().get("abandonAfterDays").asInt()).isEqualTo(30);
                assertThat(a.entry().detail().get("lastChangedAt").asString()).isEqualTo(w.clock.instant().toString());
            });
            assertThat(w.auditLog()).filteredOn(a -> a.entry().action() == AuditAction.DRAFT_ABANDON_BATCH).singleElement().satisfies(a -> {
                assertThat(a.entry().detail().get("candidates").asInt()).isEqualTo(2);
                assertThat(a.entry().detail().get("abandoned").asInt()).isOne();
                assertThat(a.entry().detail().get("skipped").asInt()).isOne();
            });

            // 다시 돌리면 남은 후보가 없다(묘비는 후보가 아니다)
            DraftAbandonService.BatchReport again = service(w, run).abandonIdle(Callers.cli(w.tenant, RetentionSetup.OPERATOR), 100);
            assertThat(again.abandoned()).isEmpty();
        }
    }
}
