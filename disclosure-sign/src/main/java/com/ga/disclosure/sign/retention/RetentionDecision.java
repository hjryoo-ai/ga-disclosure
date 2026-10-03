package com.ga.disclosure.sign.retention;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.RetentionAnchor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 파기 판정(5 계획 §5.1, 승인 Q9·Q10 — 순수). 오늘 = {@code asOf}의 KST 날짜.
 * <pre>
 * terminal = status ∈ {COMPLETED, EXPIRED, VOID, SUPERSEDED} ∧ 봉인됨          (봉인 전 VOID는 범위 밖)
 * reached  = retention_until &lt; today                                     (그 날의 KST 끝까지 잠긴다 — 잠금 기한 = 다음 날 00:00 KST)
 * pending  = ∃ a ∈ retentionAnchors : date(a) = null ∧ possible(a, status) ∧ ¬waived(a)
 *   possible(COMPLETION | CONTRACT_DATE, s) = s = COMPLETED, possible(SEAL, s) = true
 *   waived(CONTRACT_DATE) = date_KST(completed_at) + contractLinkWaitDays &lt; today
 * DESTROY(anchorsWaived)  if terminal ∧ reached ∧ ¬hold ∧ 잠금 만료 ∧ ¬pending ∧ ¬destroyed
 * </pre>
 * 건너뜀 사유가 여럿이면 {@code ALREADY_DESTROYED > NOT_TERMINAL > HOLD > LOCK_NOT_EXPIRED > PENDING_ANCHOR > RETENTION_NOT_REACHED} 순으로 하나를
 * 대표로 고르고 전체 목록도 돌려준다. {@code retentionAnchors}는 확인서에 고정된 룰, {@code contractLinkWaitDays}는 판정 시점의 ACTIVE 룰이다(Q10).
 */
public final class RetentionDecision {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Set<DisclosureStatus> TERMINAL = EnumSet.of(DisclosureStatus.COMPLETED, DisclosureStatus.EXPIRED, DisclosureStatus.VOID,
            DisclosureStatus.SUPERSEDED);

    public enum Reason { ALREADY_DESTROYED, NOT_TERMINAL, HOLD, LOCK_NOT_EXPIRED, PENDING_ANCHOR, RETENTION_NOT_REACHED }

    /**
     * @param anchorDates   생긴 앵커 날짜(SEAL = 봉인일, COMPLETION = 완료일, CONTRACT_DATE = 계약일)
     * @param locksExpired  그 확인서의 모든 객체 잠금 기한이 오늘 이전(5 계획 §5.2 ⓪ — 하나라도 아니면 거짓)
     */
    public record Subject(DisclosureStatus status, boolean sealed, LocalDate retentionUntil, Instant completedAtOrNull,
                          Map<RetentionAnchor, LocalDate> anchorDates, boolean destroyed, boolean held, boolean locksExpired) {
        public Subject {
            Objects.requireNonNull(status, "status");
            anchorDates = Map.copyOf(anchorDates);
        }
    }

    public record Policy(List<RetentionAnchor> retentionAnchors, int contractLinkWaitDays) {
        public Policy {
            retentionAnchors = List.copyOf(retentionAnchors);
            if (contractLinkWaitDays < 0) {
                throw new IllegalArgumentException("contractLinkWaitDays must not be negative");
            }
        }
    }

    public sealed interface Verdict {
        record Destroy(List<RetentionAnchor> anchorsWaived) implements Verdict {
            public Destroy {
                anchorsWaived = List.copyOf(anchorsWaived);
            }
        }

        record Skip(Reason reason, List<Reason> all) implements Verdict {
            public Skip {
                all = List.copyOf(all);
            }
        }
    }

    private RetentionDecision() {
    }

    public static Verdict decide(Subject s, Policy p, Instant asOf) {
        LocalDate today = LocalDate.ofInstant(Objects.requireNonNull(asOf, "asOf"), SEOUL);
        List<Reason> reasons = new ArrayList<>();
        if (s.destroyed()) {
            reasons.add(Reason.ALREADY_DESTROYED);
        }
        if (!s.sealed() || !TERMINAL.contains(s.status())) {
            reasons.add(Reason.NOT_TERMINAL);
        }
        if (s.held()) {
            reasons.add(Reason.HOLD);
        }
        if (!s.locksExpired()) {
            reasons.add(Reason.LOCK_NOT_EXPIRED);
        }
        List<RetentionAnchor> waived = new ArrayList<>();
        boolean pending = false;
        for (RetentionAnchor a : p.retentionAnchors()) {
            if (s.anchorDates().get(a) != null || !possible(a, s.status())) {
                continue;
            }
            if (a == RetentionAnchor.CONTRACT_DATE && s.completedAtOrNull() != null
                    && LocalDate.ofInstant(s.completedAtOrNull(), SEOUL).plusDays(p.contractLinkWaitDays()).isBefore(today)) {
                waived.add(a);
            } else {
                pending = true;
            }
        }
        if (pending) {
            reasons.add(Reason.PENDING_ANCHOR);
        }
        if (s.retentionUntil() == null || !s.retentionUntil().isBefore(today)) {
            reasons.add(Reason.RETENTION_NOT_REACHED);
        }
        if (reasons.isEmpty()) {
            return new Verdict.Destroy(waived);
        }
        return new Verdict.Skip(reasons.getFirst(), reasons);
    }

    /** 고객 파기 건너뜀 사유(대표 하나 — 이 순서). */
    public enum CustomerReason { ALREADY_DESTROYED, LIVE_DISCLOSURES, HOLD, GRACE_NOT_ELAPSED }

    /**
     * 고객 파기 판정(5 계획 §5.3, 판정 시점 룰): {@code live = 0 ∧ ¬보류 ∧ 오늘 ≥ 기준일 + 유예}. 확인서가 있었으면 기준일 = 마지막 {@code destroyed_at}(KST),
     * 유예 = {@code customerRef.graceDaysAfterLastDestruction}; 없었으면 기준일 = 등록일(KST), 유예 = {@code customerRef.abandonedDays}. {@code live}는
     * 파기되지 않은 확인서 수(봉인 전 초안 포함 — 초안 파기는 범위 밖, §14 #13). 파기하면 빈 값, 아니면 대표 사유.
     */
    public static java.util.Optional<CustomerReason> customer(boolean destroyed, int disclosures, int live, boolean held, Instant createdAt,
                                                              Instant lastDestroyedAtOrNull, int graceDaysAfterLastDestruction, int abandonedDays,
                                                              Instant asOf) {
        LocalDate today = LocalDate.ofInstant(asOf, SEOUL);
        if (destroyed) {
            return java.util.Optional.of(CustomerReason.ALREADY_DESTROYED);
        }
        if (live > 0) {
            return java.util.Optional.of(CustomerReason.LIVE_DISCLOSURES);
        }
        if (held) {
            return java.util.Optional.of(CustomerReason.HOLD);
        }
        LocalDate due = disclosures > 0
                ? LocalDate.ofInstant(Objects.requireNonNull(lastDestroyedAtOrNull, "lastDestroyedAt"), SEOUL).plusDays(graceDaysAfterLastDestruction)
                : LocalDate.ofInstant(createdAt, SEOUL).plusDays(abandonedDays);
        return today.isBefore(due) ? java.util.Optional.of(CustomerReason.GRACE_NOT_ELAPSED) : java.util.Optional.empty();
    }

    private static boolean possible(RetentionAnchor anchor, DisclosureStatus status) {
        return switch (anchor) {
            case SEAL -> true;
            case COMPLETION, CONTRACT_DATE -> status == DisclosureStatus.COMPLETED;
        };
    }
}
