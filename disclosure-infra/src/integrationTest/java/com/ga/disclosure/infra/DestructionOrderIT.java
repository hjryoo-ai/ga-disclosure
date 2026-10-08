package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.domain.enums.RetentionAnchor;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.artifact.ObjectLockedException;
import com.ga.disclosure.workflow.retention.DestroyerPort;
import com.ga.disclosure.workflow.retention.DestructionJob;
import com.ga.disclosure.workflow.retention.DestructionRefusedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G8(5 계획 §5.2): ⓪ 잠금 미만료면 변경 0(키 포함). 끝까지 가면 키 NULL·객체 버전·마커 0·묘비·감사·아웃박스. ① 뒤 중단 → 재실행은 ②③만(①감사 1행),
 * ③ 직전 중단 → 이어서 ③, 저장소 잠금 거부 → ③ 미진입. dry-run은 쓰기 0. 파기자 롤 전환은 실패 뒤 연결에 남지 않는다.
 */
class DestructionOrderIT {

    final RetentionSetup r = new RetentionSetup();

    @AfterEach
    void close() {
        r.close();
    }

    long actions(DisclosureId id, AuditAction action) {
        return r.x.s.actionsFor(id).stream().filter(a -> a == action).count();
    }

    boolean keyLive(DisclosureId id) {
        return r.count("SELECT count(*) FROM document_key WHERE tenant_id = ? AND disclosure_id = ? AND wrapped_dek IS NOT NULL",
                r.x.w.tenant.value(), id.value()) == 1;
    }

    List<String> keys(DisclosureId id) {
        List<String> keys = new ArrayList<>();
        r.x.w.in(() -> r.x.s.records.artifacts(id)).forEach(a -> keys.add(a.storageKey()));
        r.x.w.in(() -> r.x.s.records.evidence(id)).forEach(e -> keys.add(e.storageKey()));
        return keys;
    }

    /** 진행 중 트랜잭션의 연결로 현재 롤을 읽는다. */
    String currentUser() {
        javax.sql.DataSource ds = r.x.w.db.appDataSource();
        java.sql.Connection c = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(ds);
        try (var st = c.createStatement(); var rs = st.executeQuery("SELECT current_user")) {
            rs.next();
            return rs.getString(1);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(c, ds);
        }
    }

    /**
     * 승인 Q6(b): 최소 보존(0년 1일)으로 봉인·완료한 직후에는 보존이 끝났다는 사유가 나올 수 없다(합계 ≥ 1일). 같은 확인서를 보존이 끝난 뒤 재적용하면
     * 객체마다 {@code RETENTION_ALREADY_ELAPSED}로 기록된다(저장소 호출 없음).
     */
    @Test
    void theMinimumRetentionNeverElapsesAtSealOrCompletionButDoesAfterwards() {
        String elapsed = "SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'ARTIFACT_RETAIN' AND detail ->> 'reason' = 'RETENTION_ALREADY_ELAPSED'";
        DisclosureId id = r.completed();
        assertThat(r.count(elapsed, r.x.w.tenant.value())).isZero();

        r.reconcileAfterRetention();

        assertThat(r.count(elapsed, r.x.w.tenant.value())).isEqualTo(keys(id).size());
    }

    @Test
    void locksNotYetRecordedAsExpiredChangeNothing() {
        DisclosureId id = r.completed();                    // 잠금 적용이 기록되지 않았다(과거 기한 — 저장소 거부)

        DestructionJob.Report report = r.destroy();

        assertThat(report.skipped()).singleElement().satisfies(s -> assertThat(s.reason()).isEqualTo("LOCK_NOT_EXPIRED"));
        assertThat(keyLive(id)).isTrue();
        assertThat(actions(id, AuditAction.DOCUMENT_KEY_SHREDDED)).isZero();
        assertThat(keys(id)).allSatisfy(k -> assertThat(r.x.s.bucket.exists(k)).isTrue());
    }

    @Test
    void aFullRunShredsDeletesEveryVersionAndLeavesATombstone() {
        DisclosureId id = r.completed();
        r.reconcileAfterRetention();
        List<String> keys = keys(id);

        DestructionJob.Report report = r.destroy();

        assertThat(report.destroyed()).singleElement().satisfies(d -> {
            assertThat(d.id()).isEqualTo(id);
            assertThat(d.anchorsWaived()).containsExactly(RetentionAnchor.CONTRACT_DATE);
        });
        assertThat(report.failed()).isEmpty();
        assertThat(keyLive(id)).isFalse();
        assertThat(keys).isNotEmpty().allSatisfy(k -> assertThat(r.x.s.bucket.versionCount(k).isEmpty()).as(k).isTrue());
        assertThat(r.text("SELECT destroyed_at IS NOT NULL FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", r.x.w.tenant.value(), id.value()))
                .isEqualTo("t");
        assertThat(actions(id, AuditAction.DOCUMENT_KEY_SHREDDED)).isEqualTo(1);
        assertThat(actions(id, AuditAction.DISCLOSURE_DESTROYED)).isEqualTo(1);
        assertThat(r.count("SELECT count(*) FROM outbox_event WHERE tenant_id = ? AND type = 'DisclosureDestroyed' AND aggregate_id = ?",
                r.x.w.tenant.value(), id.toString())).isEqualTo(1);
        assertThat(r.x.s.audit()).filteredOn(a -> a.entry().action() == AuditAction.DESTRUCTION_BATCH_RUN).hasSize(1);

        assertThat(r.destroy().candidates()).as("파기된 확인서는 다시 후보가 아니다").isZero();
    }

    @Test
    void anInterruptionAfterTheShredResumesAtTheObjects() {
        DisclosureId id = r.completed();
        r.reconcileAfterRetention();
        r.x.s.store.deleteFailure.set(() -> new IllegalStateException("injected storage outage"));

        DestructionJob.Report first = r.destroy();

        assertThat(first.failed()).singleElement().satisfies(f -> assertThat(f.stage()).isEqualTo("DELETE_OBJECTS"));
        assertThat(keyLive(id)).isFalse();

        r.x.s.store.deleteFailure.set(null);
        DestructionJob.Report second = r.destroy();

        assertThat(second.destroyed()).extracting(DestructionJob.Destroyed::id).containsExactly(id);
        assertThat(actions(id, AuditAction.DOCUMENT_KEY_SHREDDED)).as("① 감사는 한 번").isEqualTo(1);
        assertThat(actions(id, AuditAction.DISCLOSURE_DESTROYED)).isEqualTo(1);
    }

    @Test
    void anInterruptionBeforeTheTombstoneFinishesOnTheNextRun() {
        DisclosureId id = r.completed();
        r.reconcileAfterRetention();
        AtomicBoolean failOnce = new AtomicBoolean(true);
        DestroyerPort flaky = new DestroyerPort() {
            @Override
            public String shredDocumentKey(DisclosureId d, LocalDate asOf, Instant at, String by) {
                return r.destroyer.shredDocumentKey(d, asOf, at, by);
            }

            @Override
            public void destroyDisclosure(DisclosureId d, LocalDate asOf, Instant at, String by) {
                if (failOnce.getAndSet(false)) {
                    throw new DestructionRefusedException("INJECTED", null);
                }
                r.destroyer.destroyDisclosure(d, asOf, at, by);
            }

            @Override
            public void destroyCustomerRef(CustomerRef c, Instant at, String by) {
                r.destroyer.destroyCustomerRef(c, at, by);
            }
        };

        DestructionJob.Report first = RetentionSetup.conforming(r.job(flaky).run(Callers.of(r.x.w.tenant, RetentionSetup.SYSTEM), RetentionSetup.AFTER, false, 100));
        assertThat(first.failed()).singleElement().satisfies(f -> assertThat(f.stage()).isEqualTo("DESTROY"));
        assertThat(actions(id, AuditAction.DISCLOSURE_DESTROYED)).as("거부된 트랜잭션의 감사는 롤백").isZero();
        assertThat(keys(id)).allSatisfy(k -> assertThat(r.x.s.bucket.versionCount(k).isEmpty()).isTrue());

        DestructionJob.Report second = RetentionSetup.conforming(r.job(flaky).run(Callers.of(r.x.w.tenant, RetentionSetup.SYSTEM), RetentionSetup.AFTER, false, 100));
        assertThat(second.destroyed()).extracting(DestructionJob.Destroyed::id).containsExactly(id);
        assertThat(actions(id, AuditAction.DOCUMENT_KEY_SHREDDED)).isEqualTo(1);
        assertThat(actions(id, AuditAction.DISCLOSURE_DESTROYED)).isEqualTo(1);
    }

    @Test
    void aStorageLockRefusalStopsBeforeTheTombstone() {
        DisclosureId id = r.completed();
        r.reconcileAfterRetention();
        r.x.s.store.deleteFailure.set(() -> new ObjectLockedException("injected", null));

        DestructionJob.Report report = r.destroy();

        assertThat(report.skipped()).singleElement().satisfies(s -> assertThat(s.reason()).isEqualTo("LOCK_NOT_EXPIRED"));
        assertThat(r.text("SELECT destroyed_at IS NULL FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", r.x.w.tenant.value(), id.value()))
                .isEqualTo("t");
        assertThat(actions(id, AuditAction.DISCLOSURE_DESTROYED)).isZero();
    }

    @Test
    void aDryRunJudgesButWritesNothing() {
        DisclosureId id = r.completed();
        r.reconcileAfterRetention();
        int auditBefore = r.x.s.audit().size();

        DestructionJob.Report report = RetentionSetup.conforming(r.job().run(Callers.of(r.x.w.tenant, RetentionSetup.SYSTEM), RetentionSetup.AFTER, true, 100));

        assertThat(report.wouldDestroy()).extracting(DestructionJob.Destroyed::id).containsExactly(id);
        assertThat(report.destroyed()).isEmpty();
        assertThat(r.x.s.audit()).hasSize(auditBefore);
        assertThat(keyLive(id)).isTrue();
    }

    @Test
    void theDestroyerRoleNeverOutlivesTheCallEvenAfterARefusal() {
        DisclosureId notTerminal = r.x.sealed();
        assertThatThrownBy(() -> r.x.w.in(() -> r.destroyer.shredDocumentKey(notTerminal, LocalDate.parse("2026-09-26"), RetentionSetup.AFTER, "ops")))
                .isInstanceOfSatisfying(DestructionRefusedException.class, e -> assertThat(e.sqlState()).isEqualTo("GD114"));
        for (int i = 0; i < 5; i++) {                               // 풀의 연결마다 앱 롤로 돌아와 있다
            assertThat(r.x.w.in(this::currentUser)).isEqualTo("disclosure_app");
        }
        assertThatThrownBy(() -> r.destroyer.destroyDisclosure(notTerminal, LocalDate.parse("2026-09-26"), RetentionSetup.AFTER, "ops"))
                .as("트랜잭션 밖 호출은 거부").isInstanceOf(IllegalStateException.class);
    }
}
