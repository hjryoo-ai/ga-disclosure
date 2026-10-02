package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.ValidationSubject.SignatureMark;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 4 애그리게이트 서명 명령(4 계획 §7): SIGN은 서명 목록에 역할·시각을 더하고, 완료는 COMPLETE 단계 검증(R-SIGNER-SET — 집합·순서·기한, 룰
 * 데이터)을 전부 통과할 때만, 만료는 서명 기한 끝(봉인일 KST + {@code signDeadlineDays}의 23:59:59.999999) 뒤에만이다. 막힌 완료는 아무것도 바꾸지
 * 않는다. 픽스처 룰은 DISC-2026-07(CUSTOMER → AGENT → MANAGER, SEQUENTIAL, 7일), 봉인 2026-09-23 12:00 KST.
 */
class DisclosureSigningTest {

    private static final Instant SEALED_AT = DisclosureTransitionTest.AT;
    /** D = 2026-09-30, 끝 = 2026-09-30 23:59:59.999999 KST. */
    private static final Instant DEADLINE_END = Instant.parse("2026-09-30T14:59:59.999999Z");
    private static final LocalDate SEAL_RETENTION = LocalDate.of(2031, 9, 23);

    private static Disclosure sealed() {
        return DisclosureTransitionTest.in(DisclosureStatus.SEALED);
    }

    private static SignatureMark mark(SignerRole role, long minutes) {
        return new SignatureMark(role, SEALED_AT.plus(Duration.ofMinutes(minutes)));
    }

    private static CompletionStamp completion(long minutes, LocalDate retention) {
        return new CompletionStamp(SEALED_AT.plus(Duration.ofMinutes(minutes)), retention);
    }

    @Test
    void theDeadlineComesFromTheSealDateAndThePinnedRule() {
        Disclosure d = sealed();
        assertThat(d.deadline().orElseThrow().lastDay()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(d.signDeadline()).contains(DEADLINE_END);
        assertThat(Fixtures.draft().signDeadline()).as("no deadline before sealing").isEmpty();
    }

    @Test
    void signingWithoutCompletionMovesToPartiallySignedAndKeepsTheOrder() {
        Disclosure d = sealed();
        TransitionOutcome o = d.sign(mark(SignerRole.CUSTOMER, 10), null, Fixtures.CHECK);
        assertThat(o).isInstanceOf(TransitionOutcome.Applied.class);
        assertThat(d.status()).isEqualTo(DisclosureStatus.PARTIALLY_SIGNED);
        d.sign(mark(SignerRole.AGENT, 20), null, Fixtures.CHECK);
        assertThat(d.signatures()).extracting(SignatureMark::role).containsExactly(SignerRole.CUSTOMER, SignerRole.AGENT);
        assertThat(d.completedAt()).isEmpty();
    }

    @Test
    void aRoleSignsOnceAndStrictlyAfterThePreviousSignature() {
        Disclosure d = sealed();
        d.sign(mark(SignerRole.CUSTOMER, 10), null, Fixtures.CHECK);
        assertThatThrownBy(() -> d.sign(mark(SignerRole.CUSTOMER, 20), null, Fixtures.CHECK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.sign(mark(SignerRole.AGENT, 5), null, Fixtures.CHECK)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> d.sign(mark(SignerRole.AGENT, 10), null, Fixtures.CHECK)).as("same instant").isInstanceOf(IllegalArgumentException.class);
        assertThat(d.signatures()).hasSize(1);
    }

    @Test
    void theLastRequiredSignatureCompletesAndExtendsRetention() {
        Disclosure d = sealed();
        d.sign(mark(SignerRole.CUSTOMER, 10), null, Fixtures.CHECK);
        d.sign(mark(SignerRole.AGENT, 20), null, Fixtures.CHECK);
        SignatureMark last = mark(SignerRole.MANAGER, 30);
        assertThat(d.completionResults(last, Fixtures.CHECK)).allMatch(ValidationResult::passed);
        assertThat(d.status()).as("a preview changes nothing").isEqualTo(DisclosureStatus.PARTIALLY_SIGNED);

        TransitionOutcome o = d.sign(last, completion(30, LocalDate.of(2031, 9, 24)), Fixtures.CHECK);
        assertThat(o).isInstanceOf(TransitionOutcome.Applied.class);
        assertThat(((TransitionOutcome.Applied) o).to()).isEqualTo(DisclosureStatus.COMPLETED);
        assertThat(d.completedAt()).contains(SEALED_AT.plus(Duration.ofMinutes(30)));
        assertThat(d.sealStamp().orElseThrow().retentionUntil()).isEqualTo(LocalDate.of(2031, 9, 24));
    }

    @Test
    void anIncompleteSignerSetBlocksCompletionAndChangesNothing() {
        Disclosure d = sealed();
        d.sign(mark(SignerRole.CUSTOMER, 10), null, Fixtures.CHECK);
        List<ValidationResult> preview = d.completionResults(mark(SignerRole.AGENT, 20), Fixtures.CHECK);
        assertThat(preview).anyMatch(r -> r.ruleId().equals("R-SIGNER-SET") && !r.passed());
        TransitionOutcome o = d.sign(mark(SignerRole.AGENT, 20), completion(20, SEAL_RETENTION.plusDays(1)), Fixtures.CHECK);
        assertThat(o).isInstanceOf(TransitionOutcome.Rejected.class);
        assertThat(d.status()).isEqualTo(DisclosureStatus.PARTIALLY_SIGNED);
        assertThat(d.signatures()).hasSize(1);
        assertThat(d.completedAt()).isEmpty();
        assertThat(d.sealStamp().orElseThrow().retentionUntil()).isEqualTo(SEAL_RETENTION);
    }

    @Test
    void sequentialOrderAndTheDeadlineAreRuleChecksAtCompletion() {
        Disclosure outOfOrder = sealed();
        outOfOrder.sign(mark(SignerRole.AGENT, 10), null, Fixtures.CHECK);
        outOfOrder.sign(mark(SignerRole.CUSTOMER, 20), null, Fixtures.CHECK);
        assertThat(outOfOrder.completionResults(mark(SignerRole.MANAGER, 30), Fixtures.CHECK))
                .anyMatch(r -> r.ruleId().equals("R-SIGNER-SET") && !r.passed() && r.message().contains("순서"));

        Disclosure late = sealed();
        late.sign(mark(SignerRole.CUSTOMER, 10), null, Fixtures.CHECK);
        late.sign(mark(SignerRole.AGENT, 20), null, Fixtures.CHECK);
        SignatureMark afterDeadline = new SignatureMark(SignerRole.MANAGER, DEADLINE_END.plusNanos(1000));
        assertThat(late.completionResults(afterDeadline, Fixtures.CHECK)).anyMatch(r -> !r.passed() && r.message().contains("기한"));
        assertThat(late.completionResults(new SignatureMark(SignerRole.MANAGER, DEADLINE_END), Fixtures.CHECK))
                .allMatch(ValidationResult::passed);
    }

    @Test
    void completionNeverShortensRetention() {
        Disclosure d = sealed();
        d.sign(mark(SignerRole.CUSTOMER, 10), null, Fixtures.CHECK);
        d.sign(mark(SignerRole.AGENT, 20), null, Fixtures.CHECK);
        assertThatThrownBy(() -> d.sign(mark(SignerRole.MANAGER, 30), completion(30, SEAL_RETENTION.minusDays(1)), Fixtures.CHECK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(d.status()).isEqualTo(DisclosureStatus.PARTIALLY_SIGNED);
    }

    @Test
    void theCompleteCommandRetriesWithoutANewSignature() {
        Disclosure d = sealed();
        d.sign(mark(SignerRole.CUSTOMER, 10), null, Fixtures.CHECK);
        d.sign(mark(SignerRole.AGENT, 20), null, Fixtures.CHECK);
        d.sign(mark(SignerRole.MANAGER, 30), null, Fixtures.CHECK);            // 예: 종이 스캔 검토 대기로 완료하지 않았다
        assertThat(d.complete(completion(40, SEAL_RETENTION), Fixtures.CHECK)).isInstanceOf(TransitionOutcome.Applied.class);
        assertThat(d.status()).isEqualTo(DisclosureStatus.COMPLETED);
        assertThatThrownBy(() -> d.complete(completion(50, SEAL_RETENTION), Fixtures.CHECK))
                .isInstanceOf(com.ga.disclosure.domain.disclosure.IllegalTransition.class);
    }

    @Test
    void expiryHappensOnlyAfterTheLastInstantOfTheDeadlineDay() {
        Disclosure d = sealed();
        assertThatThrownBy(() -> d.expire(DEADLINE_END)).isInstanceOf(IllegalArgumentException.class);
        assertThat(d.status()).isEqualTo(DisclosureStatus.SEALED);
        d.expire(DEADLINE_END.plusNanos(1000));
        assertThat(d.status()).isEqualTo(DisclosureStatus.EXPIRED);
    }

    @Test
    void signaturesSurviveExpiry() {
        Disclosure d = sealed();
        d.sign(mark(SignerRole.CUSTOMER, 10), null, Fixtures.CHECK);
        d.expire(DEADLINE_END.plusSeconds(1));
        assertThat(d.status()).isEqualTo(DisclosureStatus.EXPIRED);
        assertThat(d.signatures()).hasSize(1);
    }
}
