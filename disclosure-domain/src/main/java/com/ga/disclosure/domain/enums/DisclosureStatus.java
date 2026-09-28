package com.ga.disclosure.domain.enums;

/**
 * 확인서 상태(설계서 §6.1). 전이표·전이 조건은 Phase 3 상태기계가 정한다.
 *
 * <p>가변 상태는 {@code DRAFT·COMPARED·GRADED·REASONED} 넷뿐이다. 나머지(봉인 이후)는 본문이 불변이며, DB 트리거
 * ({@code V3__immutability.sql})가 같은 집합으로 강제한다 — 두 정의가 어긋나지 않는지 통합 테스트가 대조한다.
 */
public enum DisclosureStatus {
    DRAFT,
    COMPARED,
    GRADED,
    REASONED,
    SEALED,
    PARTIALLY_SIGNED,
    COMPLETED,
    VOID,
    SUPERSEDED,
    EXPIRED;

    /** 본문(헤더 본문 컬럼·비교 항목·추천사유)을 바꿀 수 있는 상태. */
    public boolean isMutable() {
        return switch (this) {
            case DRAFT, COMPARED, GRADED, REASONED -> true;
            case SEALED, PARTIALLY_SIGNED, COMPLETED, VOID, SUPERSEDED, EXPIRED -> false;
        };
    }

    public boolean isSealedOrLater() {
        return !isMutable();
    }
}
