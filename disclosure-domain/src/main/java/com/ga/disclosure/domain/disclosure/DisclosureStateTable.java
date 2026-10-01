package com.ga.disclosure.domain.disclosure;

import com.ga.disclosure.domain.enums.DisclosureStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.ga.disclosure.domain.disclosure.DisclosureCommand.APPLY_SNAPSHOT;
import static com.ga.disclosure.domain.disclosure.DisclosureCommand.COMPARE;
import static com.ga.disclosure.domain.disclosure.DisclosureCommand.COMPLETE;
import static com.ga.disclosure.domain.disclosure.DisclosureCommand.REBASE;
import static com.ga.disclosure.domain.disclosure.DisclosureCommand.EXPIRE;
import static com.ga.disclosure.domain.disclosure.DisclosureCommand.REPLACE_ITEMS;
import static com.ga.disclosure.domain.disclosure.DisclosureCommand.SEAL;
import static com.ga.disclosure.domain.disclosure.DisclosureCommand.SET_RECOMMENDATIONS;
import static com.ga.disclosure.domain.disclosure.DisclosureCommand.SIGN;
import static com.ga.disclosure.domain.disclosure.DisclosureCommand.SUPERSEDE;
import static com.ga.disclosure.domain.disclosure.DisclosureCommand.VOID;
import static com.ga.disclosure.domain.enums.DisclosureStatus.COMPARED;
import static com.ga.disclosure.domain.enums.DisclosureStatus.COMPLETED;
import static com.ga.disclosure.domain.enums.DisclosureStatus.DRAFT;
import static com.ga.disclosure.domain.enums.DisclosureStatus.EXPIRED;
import static com.ga.disclosure.domain.enums.DisclosureStatus.GRADED;
import static com.ga.disclosure.domain.enums.DisclosureStatus.PARTIALLY_SIGNED;
import static com.ga.disclosure.domain.enums.DisclosureStatus.REASONED;
import static com.ga.disclosure.domain.enums.DisclosureStatus.SEALED;
import static com.ga.disclosure.domain.enums.DisclosureStatus.SUPERSEDED;

/**
 * 상태 × 명령 → 결과 상태 표(설계서 §6.1). 데이터 구조(EnumMap)이며 전이 조건(검증 단계 통과 등)은 애그리게이트가 따로 검사한다.
 * 결과가 둘 이상인 칸(SIGN → PARTIALLY_SIGNED 또는 COMPLETED, REBASE → COMPARED 또는 DRAFT)은 조건이 결과를 고른다. 표에 없는 칸은 {@link IllegalTransition}.
 * 설계서의 {@code state-table} 블록과 이 표가 양방향으로 같다는 것을 {@code DisclosureStateTableTest}가 증명한다(3A W1).
 */
public final class DisclosureStateTable {

    private static final Map<DisclosureStatus, Map<DisclosureCommand, Set<DisclosureStatus>>> TABLE = build();

    private DisclosureStateTable() {
    }

    private static Map<DisclosureStatus, Map<DisclosureCommand, Set<DisclosureStatus>>> build() {
        Map<DisclosureStatus, Map<DisclosureCommand, Set<DisclosureStatus>>> t = new EnumMap<>(DisclosureStatus.class);
        for (DisclosureStatus s : DisclosureStatus.values()) {
            t.put(s, new EnumMap<>(DisclosureCommand.class));
        }
        put(t, DRAFT, REPLACE_ITEMS, DRAFT);
        put(t, DRAFT, COMPARE, COMPARED);
        put(t, DRAFT, VOID, DisclosureStatus.VOID);

        put(t, COMPARED, REPLACE_ITEMS, COMPARED);
        put(t, COMPARED, APPLY_SNAPSHOT, GRADED);
        put(t, COMPARED, VOID, DisclosureStatus.VOID);
        put(t, COMPARED, REBASE, COMPARED, DRAFT);

        put(t, GRADED, REPLACE_ITEMS, COMPARED);
        put(t, GRADED, APPLY_SNAPSHOT, GRADED);
        put(t, GRADED, SET_RECOMMENDATIONS, REASONED);
        put(t, GRADED, VOID, DisclosureStatus.VOID);
        put(t, GRADED, REBASE, COMPARED, DRAFT);

        put(t, REASONED, REPLACE_ITEMS, COMPARED);
        put(t, REASONED, APPLY_SNAPSHOT, GRADED);
        put(t, REASONED, SET_RECOMMENDATIONS, REASONED);
        put(t, REASONED, SEAL, SEALED);
        put(t, REASONED, VOID, DisclosureStatus.VOID);
        put(t, REASONED, REBASE, COMPARED, DRAFT);

        put(t, SEALED, SIGN, PARTIALLY_SIGNED, COMPLETED);
        put(t, SEALED, EXPIRE, EXPIRED);
        put(t, SEALED, VOID, DisclosureStatus.VOID);
        put(t, SEALED, SUPERSEDE, SUPERSEDED);

        put(t, PARTIALLY_SIGNED, SIGN, PARTIALLY_SIGNED, COMPLETED);
        put(t, PARTIALLY_SIGNED, COMPLETE, COMPLETED);
        put(t, PARTIALLY_SIGNED, EXPIRE, EXPIRED);
        put(t, PARTIALLY_SIGNED, VOID, DisclosureStatus.VOID);
        put(t, PARTIALLY_SIGNED, SUPERSEDE, SUPERSEDED);

        put(t, COMPLETED, VOID, DisclosureStatus.VOID);
        put(t, COMPLETED, SUPERSEDE, SUPERSEDED);

        put(t, EXPIRED, VOID, DisclosureStatus.VOID);
        put(t, EXPIRED, SUPERSEDE, SUPERSEDED);
        // VOID·SUPERSEDED: 종결 상태, 허용 명령 없음

        Map<DisclosureStatus, Map<DisclosureCommand, Set<DisclosureStatus>>> frozen = new EnumMap<>(DisclosureStatus.class);
        t.forEach((s, row) -> frozen.put(s, Collections.unmodifiableMap(row)));
        return Collections.unmodifiableMap(frozen);
    }

    private static void put(Map<DisclosureStatus, Map<DisclosureCommand, Set<DisclosureStatus>>> t, DisclosureStatus from,
                            DisclosureCommand command, DisclosureStatus first, DisclosureStatus... rest) {
        if (t.get(from).put(command, Collections.unmodifiableSet(EnumSet.of(first, rest))) != null) {
            throw new IllegalStateException("duplicate cell " + from + " × " + command);
        }
    }

    /** 표 전체(읽기 전용). */
    public static Map<DisclosureStatus, Map<DisclosureCommand, Set<DisclosureStatus>>> table() {
        return TABLE;
    }

    /** {@code from}에서 {@code command}의 결과 상태들. 허용되지 않으면 빈 집합. */
    public static Set<DisclosureStatus> results(DisclosureStatus from, DisclosureCommand command) {
        return TABLE.get(from).getOrDefault(command, Set.of());
    }

    public static boolean allows(DisclosureStatus from, DisclosureCommand command) {
        return !results(from, command).isEmpty();
    }

    /** {@code from}에서 {@code command}가 허용되지 않으면 {@link IllegalTransition}. */
    public static void require(DisclosureStatus from, DisclosureCommand command) {
        if (!allows(from, command)) {
            throw new IllegalTransition(from, command);
        }
    }

    /** 표가 {@code from × command}의 결과로 {@code to}를 허용하는가 — 결과가 표 밖이면 {@link IllegalTransition}. */
    public static DisclosureStatus target(DisclosureStatus from, DisclosureCommand command, DisclosureStatus to) {
        if (!results(from, command).contains(to)) {
            throw new IllegalTransition(from, command);
        }
        return to;
    }
}
