package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.artifact.ArtifactRecord;
import com.ga.disclosure.workflow.artifact.ObjectLockedException;
import com.ga.disclosure.workflow.disclosure.ArtifactService;
import com.ga.disclosure.workflow.disclosure.SealService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 3B S9(3A 수용심사 §3-5 순서): 업로드(잠금 없음) → 커밋 → 커밋 후 Object Lock → {@code retention_applied_at}.
 * <ol>
 *   <li>(a) 업로드 뒤·커밋 전 실패: 롤백(상태·번호·카운터·키·산출물 기록 없음), 저장소에는 잠금 없는 잔여물 2개 → 잔여물 정리가 지운다.</li>
 *   <li>(b) 유예 안의 잔여물(진행 중인 봉인이 올렸을 수 있는 객체)은 정리가 건드리지 않는다. 유예는 봉인 트랜잭션 제한보다 커야 한다.</li>
 *   <li>(c) 커밋 후 잠금 실패: 봉인은 유효, {@code retention_applied_at} NULL·감사 {@code ARTIFACT_RETAIN_DEFERRED} → 재적용이 잠그고 기록한다.</li>
 *   <li>(d) 잠긴 객체는 보존기한 전에 지울 수 없고, 산출물 기록 시점(커밋 전)에는 잠금이 없었다.</li>
 * </ol>
 */
class RetentionOrderIT {

    private final SealSetup s = new SealSetup();

    @AfterEach
    void close() {
        s.close();
    }

    private ArtifactService artifactsHoursFromNow(long hours) {
        return s.artifactsAt(Clock.offset(Clock.systemUTC(), Duration.ofHours(hours)), s.store);
    }

    @Test
    void failureBeforeCommitLeavesOnlyUnlockedOrphansThatGcRemoves() {
        DisclosureId id = s.w.reasoned();
        s.recordPort.failOnArtifact.set(true);
        assertThatThrownBy(() -> s.seal.seal(Callers.of(s.w.tenant, WorkflowSetup.AGENT), id)).isInstanceOf(FailingPorts.InjectedFailure.class);
        s.recordPort.failOnArtifact.set(false);

        String t = s.w.tenant.value();
        assertThat(s.text("SELECT status FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", t, id.value())).isEqualTo("REASONED");
        assertThat(s.count("SELECT count(*) FROM disclosure_counter WHERE tenant_id = ?", t)).as("번호도 롤백").isZero();
        assertThat(s.count("SELECT count(*) FROM document_key WHERE tenant_id = ?", t)).isZero();
        assertThat(s.count("SELECT count(*) FROM document_artifact WHERE tenant_id = ?", t)).isZero();
        assertThat(s.audit()).anyMatch(r -> r.entry().action() == AuditAction.COMMAND_FAILED);
        assertThat(s.store.uploaded).hasSize(2);
        assertThat(s.objects()).as("잠금 없는 잔여물").isEqualTo(2);
        s.store.uploaded.forEach(k -> assertThat(s.bucket.retention(k)).isEmpty());

        // (b) 유예(24시간) 안: 건드리지 않는다
        ArtifactService.GcReport young = s.artifacts.gc(Callers.cli(s.w.tenant, SealSetup.MANAGER), SealSetup.grace());
        assertThat(young.deleted()).isEmpty();
        assertThat(s.objects()).isEqualTo(2);
        assertThatThrownBy(() -> s.artifacts.gc(Callers.cli(s.w.tenant, SealSetup.MANAGER), Duration.ofSeconds(30)))
                .as("유예는 봉인 트랜잭션 제한(60초)보다 커야 한다").isInstanceOf(IllegalArgumentException.class);

        // (a) 25시간 뒤: 참조 없는 잠금 없는 객체를 지운다
        ArtifactService.GcReport gc = artifactsHoursFromNow(25).gc(Callers.cli(s.w.tenant, SealSetup.MANAGER), SealSetup.grace());
        assertThat(gc.deleted()).containsExactlyInAnyOrderElementsOf(s.store.uploaded);
        assertThat(s.objects()).isZero();
        assertThat(s.audit().stream().filter(r -> r.entry().action() == AuditAction.ARTIFACT_GC)).hasSize(2);

        // 같은 확인서를 다시 봉인하면 성공한다(번호는 1 — 실패한 시도가 번호를 쓰지 않았다)
        SealService.Outcome retry = s.seal.seal(Callers.of(s.w.tenant, WorkflowSetup.AGENT), id);
        assertThat(retry.number().orElseThrow().sequence()).isEqualTo(1);
    }

    @Test
    void retentionFailureAfterCommitIsReconciled() {
        DisclosureId id = s.w.reasoned();
        s.store.failRetention.set(true);
        SealService.Outcome o = s.seal.seal(Callers.of(s.w.tenant, WorkflowSetup.AGENT), id);
        s.store.failRetention.set(false);
        assertThat(o.sealed()).isTrue();
        assertThat(o.status()).isEqualTo(DisclosureStatus.SEALED);
        assertThat(o.retentionPending()).isTrue();
        String t = s.w.tenant.value();
        assertThat(s.count("SELECT count(*) FROM document_artifact WHERE tenant_id = ? AND retention_applied_at IS NULL", t)).isEqualTo(2);
        assertThat(s.audit().stream().filter(r -> r.entry().action() == AuditAction.ARTIFACT_RETAIN_DEFERRED)).hasSize(2);
        List<ArtifactRecord> artifacts = s.artifactsOf(id);
        artifacts.forEach(a -> assertThat(s.bucket.retention(a.storageKey())).isEmpty());

        ArtifactService.ReconcileReport report = s.artifacts.reconcile(Callers.cli(s.w.tenant, SealSetup.MANAGER), 100);
        assertThat(report.applied()).isEqualTo(2);
        assertThat(report.failed()).isZero();
        assertThat(s.count("SELECT count(*) FROM document_artifact WHERE tenant_id = ? AND retention_applied_at IS NULL", t)).isZero();
        artifacts.forEach(a -> assertThat(s.bucket.retention(a.storageKey())).hasValue(SealService.retainUntilInstant(LocalDate.of(2031, 9, 23))));
        assertThat(s.artifacts.reconcile(Callers.cli(s.w.tenant, SealSetup.MANAGER), 100).applied()).as("다시 돌려도 할 일 없음").isZero();

        // (d) 잠긴 산출물은 보존기한 전에 지울 수 없다 — 정리도 참조 행 때문에 건드리지 않는다
        artifacts.forEach(a -> assertThatThrownBy(() -> s.bucket.delete(a.storageKey())).isInstanceOf(ObjectLockedException.class));
        ArtifactService.GcReport gc = artifactsHoursFromNow(25).gc(Callers.cli(s.w.tenant, SealSetup.MANAGER), SealSetup.grace());
        assertThat(gc.deleted()).isEmpty();
        assertThat(gc.referenced()).isEqualTo(2);
    }

    @Test
    void noLockIsTakenBeforeTheCommit() {
        SealService.Outcome o = s.sealReasoned();
        assertThat(o.sealed()).isTrue();
        assertThat(s.recordPort.retentionBeforeCommit).hasSize(2).allMatch(java.util.Optional::isEmpty);
        assertThat(s.store.retentionInsideTransaction).as("잠금은 트랜잭션 밖(커밋 후)에서만").hasSize(2).containsOnly(false);
        s.artifactsOf(o.id()).forEach(a -> assertThat(s.bucket.retention(a.storageKey())).isPresent());
    }
}
