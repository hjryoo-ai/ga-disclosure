package com.ga.disclosure.workflow.sign;

import com.ga.disclosure.domain.vo.CustomerRef;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 서명 링크 통지 아웃박스(V12 {@code notification_outbox}, GD122) — 바인딩된 테넌트. 수신자는 가명({@code customer_ref}), 대상은 세션 ID다. 전화번호·토큰을
 * 담을 컬럼이 없다. 전이는 PENDING → {SENT, DEAD, CANCELLED} 1회, PENDING 안에서는 실패 1회 기록(시도 +1, 다음 시각 전진, 오류 코드)만.
 */
public interface NotificationStore {

    /** 발급 트랜잭션 안에서 적재(PENDING, 시도 0, 다음 시도 = {@code at}). */
    void insertPending(UUID notificationId, CustomerRef recipient, UUID sessionId, Instant at);

    /** 기한이 된 PENDING(다음 시도 오름차순). 잠그지 않는다 — 행마다 {@link #lockDue}. */
    List<UUID> due(Instant asOf, int limit);

    /** 아직 PENDING이고 기한이 됐으면 잠근다({@code FOR UPDATE SKIP LOCKED} — 다른 디스패처가 쥔 행은 빈 값). */
    Optional<Pending> lockDue(UUID notificationId, Instant asOf);

    /** 아직 PENDING이면 잠근다(발송 실패 기록용 — 기한은 보지 않는다). */
    Optional<Pending> lockPending(UUID notificationId);

    void markSent(UUID notificationId, Instant at);

    /** 실패 1회 기록: 시도 +1, 다음 시도 시각, 오류 코드. */
    void recordFailure(UUID notificationId, Instant nextAttemptAt, String errorCode);

    /** 종단: DEAD(소진·번호 없음) 또는 CANCELLED(세션 닫힘·만료). {@code countAttempt}면 이번 실패를 시도 수에 더한다. */
    void close(UUID notificationId, Closed status, String errorCode, boolean countAttempt, Instant at);

    enum Closed {
        DEAD, CANCELLED
    }

    record Pending(UUID notificationId, CustomerRef recipient, UUID sessionId, int attempts, Instant nextAttemptAt) {
    }
}
