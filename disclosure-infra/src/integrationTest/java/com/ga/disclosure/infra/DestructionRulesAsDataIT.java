package com.ga.disclosure.infra;

import com.ga.disclosure.domain.enums.RetentionAnchor;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.disclosure.ExpireService;
import com.ga.disclosure.workflow.retention.DestructionJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G10(CLAUDE.md 절대 규칙 4, 5 계획 §5.1): 계약일 대기·보존 길이는 룰 데이터다 — 같은 코드에 룰 변형만 바꿔 판정이 바뀐다. {@code COMPLETED} +
 * {@code CONTRACT_DATE} 룰 + 계약일 없음은 {@code contractLinkWaitDays}가 지날 때까지 {@code PENDING_ANCHOR}, 지나면 파기 + {@code anchorsWaived}.
 * {@code EXPIRED}는 계약일이 생길 수 없어 기다리지 않는다.
 */
class DestructionRulesAsDataIT {

    RetentionSetup r;

    @AfterEach
    void close() {
        r.close();
    }

    RetentionSetup with(Consumer<ObjectNode> edit) {
        r = new RetentionSetup(edit);
        return r;
    }

    static Consumer<ObjectNode> waitDays(int days) {
        return body -> ((ObjectNode) body.get("retention")).put("contractLinkWaitDays", days);
    }

    @Test
    void theContractWaitComesFromTheRule() {
        with(waitDays(3));
        DisclosureId id = r.completed();                       // 완료 2026-09-23 KST, 대기 3일 → 09-26까지 대기
        r.reconcileAfterRetention();

        DestructionJob.Report waiting = r.destroy();           // 09-26
        assertThat(waiting.skipped()).singleElement().satisfies(s -> {
            assertThat(s.reason()).isEqualTo("PENDING_ANCHOR");
            assertThat(s.reasons()).containsExactly("PENDING_ANCHOR");
        });

        DestructionJob.Report after = r.destroyAt(RetentionSetup.AFTER.plus(Duration.ofDays(1)));
        assertThat(after.destroyed()).singleElement().satisfies(d -> {
            assertThat(d.id()).isEqualTo(id);
            assertThat(d.anchorsWaived()).containsExactly(RetentionAnchor.CONTRACT_DATE);
        });
        assertThat(after.toJson().get("anchorsWaived").asLong()).isEqualTo(1);
    }

    @Test
    void anExpiredDisclosureDoesNotWaitForAContractDate() {
        with(waitDays(365));
        DisclosureId expired = r.x.sealed();
        DisclosureId completed = r.completed();
        Instant afterDeadline = Instant.parse("2026-10-01T01:00:00Z");
        new ExpireService(r.x.w.deps(r.x.clock), r.x.sessions).run(Callers.of(r.x.w.tenant, RetentionSetup.OPERATOR), afterDeadline, 100);
        Instant judged = afterDeadline.plus(Duration.ofDays(2));
        r.reconcileAt(judged);

        DestructionJob.Report report = r.destroyAt(judged);

        assertThat(report.destroyed()).extracting(DestructionJob.Destroyed::id).containsExactly(expired);
        assertThat(report.destroyed().getFirst().anchorsWaived()).isEmpty();
        assertThat(report.skipped()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo(completed);
            assertThat(s.reason()).isEqualTo("PENDING_ANCHOR");
        });
    }

    @Test
    void theRetentionLengthComesFromTheRule() {
        with(body -> body.put("retentionDays", 5));
        DisclosureId id = r.completed();                       // 보존기한 2026-09-28 — 09-26엔 미도달

        r.reconcileAfterRetention();

        DestructionJob.Report early = r.destroy();             // 09-26: 보존기한 전이라 후보조차 아니다
        assertThat(early.candidates()).isZero();
        assertThat(early.destroyed()).isEmpty();
        assertThat(r.text("SELECT retention_until::text FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", r.x.w.tenant.value(), id.value()))
                .isEqualTo("2026-09-28");

        Instant past = Instant.parse("2026-09-29T01:00:00Z");
        r.reconcileAt(past);
        assertThat(r.destroyAt(past).destroyed()).extracting(DestructionJob.Destroyed::id).containsExactly(id);
    }
}
