package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.retention.DestructionJob;
import com.ga.disclosure.workflow.retention.LegalHoldRejectedException;
import com.ga.disclosure.workflow.retention.LegalHoldService;
import com.ga.disclosure.workflow.retention.LegalHoldService.Target;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G11(5 계획 §8.6): 설정 → 파기 배치가 {@code HOLD}로 건너뜀, 해제 → 다음 실행이 파기. 설정·해제 감사 각 1행, 대상당 활성 1건(앱 거부 + 유일 인덱스는
 * {@link LegalHoldGuardIT}), {@code retention_until} 불변. 저장소 보류(SeaweedFS 지원 — 계약 결과 {@link ArtifactStoreContract})는 모든 객체에서
 * 켜고 끄며, 다른 활성 보류가 덮는 객체는 끄지 않는다. 미지원 저장소에서도 DB 보류가 통제다.
 */
class LegalHoldIT {

    final RetentionSetup r = new RetentionSetup();
    final LegalHoldService holds = r.holdService(RetentionSetup.at(RetentionSetup.AFTER));

    @AfterEach
    void close() {
        r.close();
    }

    List<String> keys(DisclosureId id) {
        List<String> keys = new ArrayList<>();
        r.x.w.in(() -> r.x.s.records.artifacts(id)).forEach(a -> keys.add(a.storageKey()));
        r.x.w.in(() -> r.x.s.records.evidence(id)).forEach(e -> keys.add(e.storageKey()));
        return keys;
    }

    String retentionUntil(DisclosureId id) {
        return r.text("SELECT retention_until::text FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", r.x.w.tenant.value(), id.value());
    }

    long audits(AuditAction action, String targetId) {
        return r.x.s.audit().stream().filter(a -> a.entry().action() == action && targetId.equals(a.entry().targetId())).count();
    }

    String rejection(Runnable call) {
        try {
            call.run();
        } catch (LegalHoldRejectedException e) {
            return e.code();
        }
        throw new AssertionError("not rejected");
    }

    @Test
    void aHoldSkipsDestructionAndItsReleaseLetsTheNextRunDestroy() {
        DisclosureId id = r.completed();
        r.reconcileAfterRetention();
        String until = retentionUntil(id);
        List<String> keys = keys(id);

        LegalHoldService.Outcome placed = holds.place(r.x.w.tenant, RetentionSetup.OPERATOR, new Target.Disclosure(id), "LITIGATION", null);

        assertThat(placed.storage()).isEqualTo(new LegalHoldService.StorageHold(keys.size(), 0, false));
        assertThat(keys).isNotEmpty().allSatisfy(k -> assertThat(r.x.s.bucket.legalHold(k)).as(k).isTrue());
        DestructionJob.Report held = r.destroy();
        assertThat(held.skipped()).singleElement().satisfies(s -> assertThat(s.reason()).isEqualTo("HOLD"));
        assertThat(held.destroyed()).isEmpty();
        assertThat(retentionUntil(id)).isEqualTo(until);

        LegalHoldService.Outcome released = holds.release(r.x.w.tenant, RetentionSetup.OPERATOR, placed.holdId(), "CASE_CLOSED");

        assertThat(released.storage()).isEqualTo(new LegalHoldService.StorageHold(keys.size(), 0, false));
        assertThat(keys).allSatisfy(k -> assertThat(r.x.s.bucket.legalHold(k)).as(k).isFalse());
        assertThat(r.destroy().destroyed()).extracting(DestructionJob.Destroyed::id).containsExactly(id);
        assertThat(audits(AuditAction.LEGAL_HOLD_PLACED, id.toString())).isEqualTo(1);
        assertThat(audits(AuditAction.LEGAL_HOLD_RELEASED, id.toString())).isEqualTo(1);
        assertThat(retentionUntil(id)).as("보류는 보존기한을 바꾸지 않는다").isEqualTo(until);
    }

    @Test
    void oneActiveHoldPerTargetAndAReleaseHappensOnce() {
        DisclosureId id = r.completed();
        Target target = new Target.Disclosure(id);
        LegalHoldService.Outcome first = holds.place(r.x.w.tenant, RetentionSetup.OPERATOR, target, "LITIGATION", null);

        assertThat(rejection(() -> holds.place(r.x.w.tenant, RetentionSetup.OPERATOR, target, "REGULATOR_INQUIRY", null))).isEqualTo("ALREADY_HELD");
        holds.release(r.x.w.tenant, RetentionSetup.OPERATOR, first.holdId(), "CASE_CLOSED");
        assertThat(rejection(() -> holds.release(r.x.w.tenant, RetentionSetup.OPERATOR, first.holdId(), "CASE_CLOSED"))).isEqualTo("ALREADY_RELEASED");
        holds.place(r.x.w.tenant, RetentionSetup.OPERATOR, target, "REGULATOR_INQUIRY", null);

        assertThat(r.count("SELECT count(*) FROM legal_hold WHERE tenant_id = ? AND disclosure_id = ? AND released_at IS NULL",
                r.x.w.tenant.value(), id.value())).isEqualTo(1);
        assertThat(audits(AuditAction.LEGAL_HOLD_PLACED, id.toString())).isEqualTo(2);
        assertThat(audits(AuditAction.LEGAL_HOLD_RELEASED, id.toString())).isEqualTo(1);
    }

    @Test
    void aCustomerHoldCoversEveryDisclosureAndKeepsTheStorageHoldItCovers() {
        DisclosureId a = r.completed();
        DisclosureId b = r.completed();
        r.reconcileAfterRetention();
        LegalHoldService.Outcome customer = holds.place(r.x.w.tenant, RetentionSetup.OPERATOR, new Target.Customer(r.x.signer), "CUSTOMER_COMPLAINT", null);
        LegalHoldService.Outcome onA = holds.place(r.x.w.tenant, RetentionSetup.OPERATOR, new Target.Disclosure(a), "LITIGATION", null);

        assertThat(customer.storage().applied()).isEqualTo(keys(a).size() + keys(b).size());
        assertThat(r.destroy().skipped()).extracting(DestructionJob.Skipped::reason).containsExactly("HOLD", "HOLD");

        LegalHoldService.Outcome releasedA = holds.release(r.x.w.tenant, RetentionSetup.OPERATOR, onA.holdId(), "CASE_CLOSED");
        assertThat(releasedA.storage().applied()).as("고객 보류가 덮는 객체는 끄지 않는다").isZero();
        assertThat(keys(a)).allSatisfy(k -> assertThat(r.x.s.bucket.legalHold(k)).isTrue());
        assertThat(r.destroy().destroyed()).isEmpty();

        holds.release(r.x.w.tenant, RetentionSetup.OPERATOR, customer.holdId(), "CASE_CLOSED");
        assertThat(r.destroy().destroyed()).extracting(DestructionJob.Destroyed::id).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void reasonCodesAndTextLimitsComeFromTheRule() {
        DisclosureId id = r.completed();
        Target target = new Target.Disclosure(id);

        assertThat(rejection(() -> holds.place(r.x.w.tenant, RetentionSetup.OPERATOR, target, "SUBPOENA", null))).isEqualTo("UNKNOWN_REASON");
        assertThat(rejection(() -> holds.place(r.x.w.tenant, RetentionSetup.OPERATOR, target, "OTHER", " "))).isEqualTo("TEXT_REQUIRED");
        assertThat(rejection(() -> holds.place(r.x.w.tenant, RetentionSetup.OPERATOR, target, "OTHER", "가".repeat(501)))).isEqualTo("TEXT_TOO_LONG");
        LegalHoldService.Outcome ok = holds.place(r.x.w.tenant, RetentionSetup.OPERATOR, target, "OTHER", "가".repeat(500));
        assertThat(rejection(() -> holds.release(r.x.w.tenant, RetentionSetup.OPERATOR, ok.holdId(), "closed"))).isEqualTo("BAD_RELEASE_REASON");

        assertThat(audits(AuditAction.LEGAL_HOLD_PLACED, id.toString())).isEqualTo(1);
        assertThat(r.x.s.audit()).filteredOn(a -> a.entry().action() == AuditAction.LEGAL_HOLD_PLACED).singleElement()
                .satisfies(a -> assertThat(a.entry().detail().get("reasonTextLength").asInt()).isEqualTo(500))
                .satisfies(a -> assertThat(a.entry().detail().has("reasonText")).as("텍스트 원문은 감사에 남기지 않는다").isFalse());
    }

    @Test
    void withoutStorageSupportTheDatabaseHoldStillControls() {
        DisclosureId id = r.completed();
        r.reconcileAfterRetention();
        r.x.s.store.legalHoldUnsupported.set(true);

        LegalHoldService.Outcome placed = holds.place(r.x.w.tenant, RetentionSetup.OPERATOR, new Target.Disclosure(id), "LITIGATION", null);

        assertThat(placed.storage()).isEqualTo(new LegalHoldService.StorageHold(0, 0, true));
        assertThat(keys(id)).allSatisfy(k -> assertThat(r.x.s.bucket.legalHold(k)).isFalse());
        assertThat(r.destroy().skipped()).extracting(DestructionJob.Skipped::reason).containsExactly("HOLD");
        assertThatThrownBy(() -> r.x.s.store.legalHold(keys(id).getFirst()))
                .isInstanceOf(com.ga.disclosure.workflow.artifact.UnsupportedCapabilityException.class);
    }
}
