package com.ga.disclosure.infra;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.infra.testing.SeedData;
import com.ga.disclosure.workflow.artifact.LockedObject;
import com.ga.disclosure.workflow.disclosure.SealService;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * G12 보존기한 앵커(3B 수용심사 §3-3, 4 계획 §7.3 4·6항): {@code retention_until = max(현재, max(앵커 날짜 + retentionYears))}, 앵커 목록은 룰 데이터.
 * 완료일(KST)이 봉인일보다 늦으면 완료가 기한을 늘리고 커밋 뒤 산출물·서명 증거 전부에 늘어난 기한으로 잠금을 다시 건다(적용 기한 증가만). 앵커 목록에서
 * COMPLETION을 빼면 늘지 않는다. 단축은 DB가 거부한다(GD094·GD093·GD105).
 */
class RetentionAnchorIT {

    private static final String SEAL_RETENTION = "2031-09-23";

    private static DisclosureId completeOn(SignSetup x, Instant at) {
        DisclosureId id = x.sealed();                                   // 봉인 2026-09-23 10:00 KST
        x.clock.set(at);
        x.customerSignsOnTouchPad(id);
        x.agentSigns(id);
        if (!x.managerConfirms(id).completed()) {
            throw new IllegalStateException("not completed");
        }
        return id;
    }

    private static String retention(SignSetup x, DisclosureId id) {
        return x.s.text("SELECT retention_until::text FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", x.w.tenant.value(), id.value());
    }

    private static List<LockedObject> objects(SignSetup x, DisclosureId id) {
        List<LockedObject> all = new ArrayList<>(x.s.artifactsOf(id));
        all.addAll(x.w.in(() -> x.s.records.evidence(id)));
        return all;
    }

    @Test
    void aLaterCompletionDayExtendsRetentionAndRelocksEverything() {
        try (SignSetup x = new SignSetup()) {
            DisclosureId id = completeOn(x, Instant.parse("2026-09-25T02:00:00Z"));   // 완료 2026-09-25 KST
            assertThat(retention(x, id)).isEqualTo("2031-09-25");
            Instant until = SealService.retainUntilInstant(LocalDate.parse("2031-09-25"));
            List<LockedObject> objects = objects(x, id);
            assertThat(objects).hasSize(8);                              // 산출물 4 + 증거 4(고객·설계사 스트로크·이미지)
            objects.forEach(o -> assertThat(x.s.bucket.retention(o.storageKey())).as(o.kindName()).hasValue(until));
            assertThat(x.s.count("SELECT count(*) FROM document_artifact WHERE tenant_id = ? AND disclosure_id = ? "
                    + "AND retention_applied_until = DATE '2031-09-25'", x.w.tenant.value(), id.value())).isEqualTo(4);
            assertThat(x.s.count("SELECT count(*) FROM signature_evidence WHERE tenant_id = ? AND disclosure_id = ? "
                    + "AND retention_applied_until = DATE '2031-09-25'", x.w.tenant.value(), id.value())).isEqualTo(4);
        }
    }

    @Test
    void theAnchorListIsRuleData() {
        try (SignSetup x = new SignSetup(new SealSetup(WorkflowSetup.withRule(body -> body.putArray("retentionAnchors").add("SEAL"))))) {
            DisclosureId id = completeOn(x, Instant.parse("2026-09-25T02:00:00Z"));
            assertThat(retention(x, id)).isEqualTo(SEAL_RETENTION);
        }
    }

    @Test
    void shorteningIsRefusedByTheDatabase() {
        try (SignSetup x = new SignSetup()) {
            DisclosureId id = completeOn(x, Instant.parse("2026-09-25T02:00:00Z"));
            String t = x.w.tenant.value();
            assertThat(state(x, () -> x.w.db.asApp(t, c -> SeedData.exec(c, "UPDATE disclosure SET retention_until = DATE '2031-09-24' "
                    + "WHERE tenant_id = ? AND disclosure_id = ?", t, id.value())))).isEqualTo("GD094");
            assertThat(state(x, () -> x.w.db.asApp(t, c -> SeedData.exec(c, "UPDATE document_artifact SET retention_applied_until = DATE '2031-09-24' "
                    + "WHERE tenant_id = ? AND disclosure_id = ?", t, id.value())))).isEqualTo("GD093");
            assertThat(state(x, () -> x.w.db.asApp(t, c -> SeedData.exec(c, "UPDATE signature_evidence SET retention_applied_until = DATE '2031-09-24' "
                    + "WHERE tenant_id = ? AND disclosure_id = ?", t, id.value())))).isEqualTo("GD105");
            // 재적용은 증가만: 같은·짧은 기한의 기록은 하지 않는다
            LockedObject any = objects(x, id).getFirst();
            assertThat(x.w.in(() -> x.s.records.markRetentionApplied(any, x.clock.instant(), LocalDate.parse("2031-09-25")))).isFalse();
            assertThat(x.w.in(() -> x.s.records.markRetentionApplied(any, x.clock.instant(), LocalDate.parse("2031-09-24")))).isFalse();
        }
    }

    private static String state(SignSetup x, Runnable statement) {
        RuntimeException e = catchThrowableOfType(RuntimeException.class, statement::run);
        Throwable c = e;
        while (c != null && !(c instanceof SQLException)) {
            c = c.getCause();
        }
        return c == null ? "none: " + e : ((SQLException) c).getSQLState();
    }
}
