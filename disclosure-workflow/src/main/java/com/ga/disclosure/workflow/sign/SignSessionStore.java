package com.ga.disclosure.workflow.sign;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.Sha256;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 고객 서명 세션 저장소 포트(V8 {@code sign_session}, 바인딩된 테넌트의 트랜잭션 안). 행은 지우지 않고 상태 전이만 기록한다(GD101). 잠금 순서는
 * 확인서 행 → 세션 행이다 — 세션을 먼저 잠그는 경로가 없도록 토큰 조회는 잠그지 않고({@link #findByToken}), 확인서를 잠근 뒤 {@link #lock}한다.
 */
public interface SignSessionStore {

    void insert(SignSession session);

    /** 토큰 해시로 찾는다(잠그지 않는다 — 어느 확인서인지 알기 위한 것). */
    Optional<SignSession> findByToken(Sha256 tokenHash);

    /** 행 잠금으로 다시 읽는다(확인서 행을 잠근 뒤). */
    Optional<SignSession> lock(UUID sessionId);

    /** 가변 컬럼(상태·실패 횟수·통과 수단·열람 증거·발송·사용·취소)만 쓴다. */
    void update(SignSession session);

    /** 확인서의 OPEN 세션(행 잠금). 한 확인서·역할당 1개(V8 부분 유일 인덱스)지만 목록으로 받는다. */
    List<SignSession> openFor(DisclosureId disclosure);

    /** TTL이 지난 OPEN 세션(잠그지 않는다 — 만료 배치가 확인서를 잠근 뒤 다시 잠근다, 오래된 순). */
    List<SignSession> openElapsed(Instant asOf, int limit);
}
