package com.ga.disclosure.sign.retention;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.RetentionAnchor;
import com.ga.disclosure.sign.retention.RetentionDecision.Policy;
import com.ga.disclosure.sign.retention.RetentionDecision.Reason;
import com.ga.disclosure.sign.retention.RetentionDecision.Subject;
import com.ga.disclosure.sign.retention.RetentionDecision.Verdict;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G10(5 계획 §5.1): 산식 전수 — 상태 × 봉인 여부 × 계약일 앵커 유무 × 대기 경과 × 보류 × 잠금 만료 × 보존 도래 × 기파기. 기대값은 산식의 문장을 따로
 * 옮긴 것이다(구현을 다시 부르지 않는다). 경계: 보존기한 당일 KST 끝, 대기 일수 당일.
 */
class RetentionDecisionTest {

    static final Instant AS_OF = Instant.parse("2026-10-03T03:00:00Z");                 // KST 2026-10-03 12:00
    static final LocalDate TODAY = LocalDate.parse("2026-10-03");
    static final List<RetentionAnchor> ALL_ANCHORS = List.of(RetentionAnchor.SEAL, RetentionAnchor.COMPLETION, RetentionAnchor.CONTRACT_DATE);

    @Test
    void everyCombinationFollowsTheFormula() {
        int destroy = 0;
        int cases = 0;
        for (DisclosureStatus status : DisclosureStatus.values()) {
            for (boolean sealed : new boolean[]{true, false}) {
                for (boolean contractDate : new boolean[]{true, false}) {
                    for (boolean waitElapsed : new boolean[]{true, false}) {
                        for (boolean held : new boolean[]{true, false}) {
                            for (boolean locks : new boolean[]{true, false}) {
                                for (boolean reached : new boolean[]{true, false}) {
                                    for (boolean destroyed : new boolean[]{true, false}) {
                                        cases++;
                                        Map<RetentionAnchor, LocalDate> dates = new EnumMap<>(RetentionAnchor.class);
                                        dates.put(RetentionAnchor.SEAL, LocalDate.parse("2021-09-23"));
                                        Instant completedAt = status == DisclosureStatus.COMPLETED
                                                ? (waitElapsed ? Instant.parse("2025-01-01T00:00:00Z") : Instant.parse("2026-09-30T00:00:00Z")) : null;
                                        if (completedAt != null) {
                                            dates.put(RetentionAnchor.COMPLETION, LocalDate.parse("2025-01-01"));
                                        }
                                        if (contractDate) {
                                            dates.put(RetentionAnchor.CONTRACT_DATE, LocalDate.parse("2021-10-01"));
                                        }
                                        Subject s = new Subject(status, sealed, reached ? TODAY.minusDays(1) : TODAY, completedAt, dates, destroyed, held, locks);
                                        Verdict v = RetentionDecision.decide(s, new Policy(ALL_ANCHORS, 365), AS_OF);

                                        boolean terminal = sealed && List.of(DisclosureStatus.COMPLETED, DisclosureStatus.EXPIRED, DisclosureStatus.VOID,
                                                DisclosureStatus.SUPERSEDED).contains(status);
                                        boolean contractPending = status == DisclosureStatus.COMPLETED && !contractDate && !waitElapsed;
                                        List<Reason> expected = new ArrayList<>();
                                        if (destroyed) {
                                            expected.add(Reason.ALREADY_DESTROYED);
                                        }
                                        if (!terminal) {
                                            expected.add(Reason.NOT_TERMINAL);
                                        }
                                        if (held) {
                                            expected.add(Reason.HOLD);
                                        }
                                        if (!locks) {
                                            expected.add(Reason.LOCK_NOT_EXPIRED);
                                        }
                                        if (contractPending) {
                                            expected.add(Reason.PENDING_ANCHOR);
                                        }
                                        if (!reached) {
                                            expected.add(Reason.RETENTION_NOT_REACHED);
                                        }
                                        String label = status + " sealed=" + sealed + " contract=" + contractDate + " waited=" + waitElapsed + " held=" + held
                                                + " locks=" + locks + " reached=" + reached + " destroyed=" + destroyed;
                                        if (expected.isEmpty()) {
                                            destroy++;
                                            boolean waived = status == DisclosureStatus.COMPLETED && !contractDate;
                                            assertThat(v).as(label).isEqualTo(new Verdict.Destroy(waived ? List.of(RetentionAnchor.CONTRACT_DATE) : List.of()));
                                        } else {
                                            assertThat(v).as(label).isEqualTo(new Verdict.Skip(expected.getFirst(), expected));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertThat(cases).isEqualTo(11 * 128);   // 6B ABANDONED 추가 — 종료 상태가 아니다(NOT_TERMINAL)
        assertThat(destroy).as("COMPLETED 3(계약일 있음 2 + 대기 경과 1) + EXPIRED·VOID·SUPERSEDED 각 4(계약일·대기 무관)").isEqualTo(15);
    }

    @Test
    void theRetentionDayLastsUntilTheEndOfItsKstDay() {
        Subject s = subject(LocalDate.parse("2026-10-03"));
        assertThat(RetentionDecision.decide(s, policy(), Instant.parse("2026-10-03T14:59:59.999999Z")))
                .isEqualTo(new Verdict.Skip(Reason.RETENTION_NOT_REACHED, List.of(Reason.RETENTION_NOT_REACHED)));
        assertThat(RetentionDecision.decide(s, policy(), Instant.parse("2026-10-03T15:00:00Z"))).isEqualTo(new Verdict.Destroy(List.of()));
    }

    @Test
    void theContractLinkWaitEndsTheDayAfterItsLastDay() {
        Map<RetentionAnchor, LocalDate> dates = Map.of(RetentionAnchor.SEAL, LocalDate.parse("2021-09-23"), RetentionAnchor.COMPLETION,
                LocalDate.parse("2025-10-03"));
        Instant completed = Instant.parse("2025-10-02T15:30:00Z");                             // KST 2025-10-03
        Subject s = new Subject(DisclosureStatus.COMPLETED, true, LocalDate.parse("2026-10-01"), completed, dates, false, false, true);
        assertThat(RetentionDecision.decide(s, new Policy(ALL_ANCHORS, 365), AS_OF))        // 2025-10-03 + 365 = 2026-10-03 = 오늘 → 아직 대기
                .isEqualTo(new Verdict.Skip(Reason.PENDING_ANCHOR, List.of(Reason.PENDING_ANCHOR)));
        assertThat(RetentionDecision.decide(s, new Policy(ALL_ANCHORS, 364), AS_OF)).isEqualTo(new Verdict.Destroy(List.of(RetentionAnchor.CONTRACT_DATE)));
        assertThat(RetentionDecision.decide(s, new Policy(List.of(RetentionAnchor.SEAL), 365), AS_OF))
                .as("룰에 계약일 앵커가 없으면 기다리지 않는다").isEqualTo(new Verdict.Destroy(List.of()));
    }

    private static Subject subject(LocalDate retentionUntil) {
        return new Subject(DisclosureStatus.VOID, true, retentionUntil, null, Map.of(RetentionAnchor.SEAL, LocalDate.parse("2021-09-23")), false, false,
                true);
    }

    private static Policy policy() {
        return new Policy(ALL_ANCHORS, 365);
    }
}
