package com.ga.disclosure.sign.session;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.ga.disclosure.sign.session.SessionEvent.CAPTURE;
import static com.ga.disclosure.sign.session.SessionEvent.DOCUMENT_EXPIRE;
import static com.ga.disclosure.sign.session.SessionEvent.DOCUMENT_SUPERSEDE;
import static com.ga.disclosure.sign.session.SessionEvent.DOCUMENT_VOID;
import static com.ga.disclosure.sign.session.SessionEvent.IDENTITY_FAIL;
import static com.ga.disclosure.sign.session.SessionEvent.IDENTITY_PASS;
import static com.ga.disclosure.sign.session.SessionEvent.OPEN_VIEW;
import static com.ga.disclosure.sign.session.SessionEvent.REISSUE;
import static com.ga.disclosure.sign.session.SessionEvent.TTL_ELAPSED;
import static com.ga.disclosure.sign.session.SessionStatus.EXPIRED;
import static com.ga.disclosure.sign.session.SessionStatus.OPEN;
import static com.ga.disclosure.sign.session.SessionStatus.REVOKED;
import static com.ga.disclosure.sign.session.SessionStatus.USED;

/**
 * 세션 상태 × 사건 → 결과 상태 표(설계서 §6.5 {@code session-state-table}, 4 계획 §2.3). 닫힌 상태(USED·EXPIRED·REVOKED)는 어떤 사건도
 * 받지 않는다. IDENTITY_FAIL은 실패 횟수가 룰 {@code identityCheck.maxFailures}에 닿으면 REVOKED, 아니면 OPEN — 조건이 결과를 고른다.
 * 설계서 블록과 이 표가 양방향으로 같다는 것을 {@code SessionStateTableTest}가 증명한다. DB 트리거(V8 GD101)가 같은 규칙의 이중화다.
 */
public final class SessionStateTable {

    private static final Map<SessionStatus, Map<SessionEvent, Set<SessionStatus>>> TABLE = build();

    private SessionStateTable() {
    }

    private static Map<SessionStatus, Map<SessionEvent, Set<SessionStatus>>> build() {
        Map<SessionStatus, Map<SessionEvent, Set<SessionStatus>>> t = new EnumMap<>(SessionStatus.class);
        for (SessionStatus s : SessionStatus.values()) {
            t.put(s, new EnumMap<>(SessionEvent.class));
        }
        put(t, OPEN_VIEW, OPEN);
        put(t, IDENTITY_PASS, OPEN);
        put(t, IDENTITY_FAIL, OPEN, REVOKED);
        put(t, CAPTURE, USED);
        put(t, TTL_ELAPSED, EXPIRED);
        put(t, REISSUE, REVOKED);
        put(t, DOCUMENT_VOID, REVOKED);
        put(t, DOCUMENT_SUPERSEDE, REVOKED);
        put(t, DOCUMENT_EXPIRE, REVOKED);
        // USED·EXPIRED·REVOKED: 끝 상태, 받는 사건 없음

        Map<SessionStatus, Map<SessionEvent, Set<SessionStatus>>> frozen = new EnumMap<>(SessionStatus.class);
        t.forEach((s, row) -> frozen.put(s, Collections.unmodifiableMap(row)));
        return Collections.unmodifiableMap(frozen);
    }

    private static void put(Map<SessionStatus, Map<SessionEvent, Set<SessionStatus>>> t, SessionEvent event, SessionStatus first,
                            SessionStatus... rest) {
        if (t.get(OPEN).put(event, Collections.unmodifiableSet(EnumSet.of(first, rest))) != null) {
            throw new IllegalStateException("duplicate cell OPEN × " + event);
        }
    }

    public static Map<SessionStatus, Map<SessionEvent, Set<SessionStatus>>> table() {
        return TABLE;
    }

    public static Set<SessionStatus> results(SessionStatus from, SessionEvent event) {
        return TABLE.get(from).getOrDefault(event, Set.of());
    }

    public static boolean allows(SessionStatus from, SessionEvent event) {
        return !results(from, event).isEmpty();
    }

    /** 표가 {@code from × event}의 결과로 {@code to}를 허용하면 {@code to}, 아니면 {@link SessionEventRejected}. */
    public static SessionStatus target(SessionStatus from, SessionEvent event, SessionStatus to) {
        if (!results(from, event).contains(to)) {
            throw new SessionEventRejected(from, event);
        }
        return to;
    }
}
