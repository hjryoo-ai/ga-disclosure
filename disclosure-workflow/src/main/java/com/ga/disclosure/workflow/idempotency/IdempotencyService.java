package com.ga.disclosure.workflow.idempotency;

import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.NotAnEntry;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Idempotency-Key 청구·완료(6A 계획 §4.2). 호출자는 API 층의 인터셉터 하나다(진입점이 아니다 — 인가는 그 뒤의 유스케이스가 한다; 행은 인증된 주체의 키
 * 공간에만 생긴다). 청구와 완료는 각각 <b>별도 트랜잭션</b>이고 유스케이스 트랜잭션과 묶이지 않는다:
 * <ol>
 *   <li>없으면 진행 중으로 청구 → {@link Claim.Proceed}. 만료된 행이 남아 있으면 지우고 새로 청구한다.</li>
 *   <li>요청 해시가 다르면 {@link Claim.Reused}(422) — 완료 여부와 무관하다.</li>
 *   <li>완료 행은 {@link Claim.Replay}(저장된 영수증 튜플·상태·응답 해시).</li>
 *   <li>진행 중 행은 {@code claimed_at + 임차} 전이면 {@link Claim.InProgress}(409), 지났으면 인수 → {@link Claim.Proceed}(순번 + 1). 인수 뒤 유스케이스가
 *       다시 돌아도 업무 상태 가드가 이중 효과를 막는다.</li>
 * </ol>
 * 유스케이스에 닿은 요청만 키를 묶는다(6A 수용심사 §2 ②): 저장하지 않는 응답(400·401·404·428·5xx) 뒤에는 {@link #release}가 그 청구를 지워 같은 키의 고친
 * 요청이 새로 청구한다.
 */
public final class IdempotencyService {

    public static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{16,128}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final IdempotencyStore store;
    private final RuleResolver rules;
    private final WorkflowTransactions transactions;
    private final Clock clock;

    public IdempotencyService(IdempotencyStore store, RuleResolver rules, WorkflowTransactions transactions, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public sealed interface Claim {
        /** 유스케이스를 실행하고 이 순번으로 완료를 기록한다. */
        record Proceed(int claimSeq) implements Claim {
        }

        record Replay(int status, String responseRef, String responseHash) implements Claim {
        }

        record Reused() implements Claim {
        }

        record InProgress() implements Claim {
        }
    }

    @NotAnEntry("write-path plumbing called only by the API idempotency interceptor; the use case behind it authorizes, rows live in the caller's own key space")
    public Claim claim(Caller caller, String key, String requestHash) {
        Objects.requireNonNull(caller, "caller");
        requireKey(key);
        if (!HASH.matcher(requestHash).matches()) {
            throw new IllegalArgumentException("request hash must be lowercase SHA-256 hex");
        }
        return transactions.inTenant(caller.tenant(), () -> {
            Instant now = clock.instant();
            EffectiveRule rule = rules.resolve(caller.tenant(), LocalDate.ofInstant(now, SEOUL));
            Instant expiresAt = now.plusSeconds(3600L * rule.idempotencyTtlHours());
            if (store.claimNew(caller.subject(), key, requestHash, now, expiresAt)) {
                return new Claim.Proceed(1);
            }
            Optional<IdempotencyRecord> found = store.lock(caller.subject(), key);
            if (found.isEmpty()) {
                // 다른 청구가 그 사이 정리했다 — 이 트랜잭션에서 다시 청구한다
                return store.claimNew(caller.subject(), key, requestHash, now, expiresAt) ? new Claim.Proceed(1) : new Claim.InProgress();
            }
            IdempotencyRecord row = found.get();
            if (!row.expiresAt().isAfter(now) && store.deleteExpired(caller.subject(), key, now)) {
                return store.claimNew(caller.subject(), key, requestHash, now, expiresAt) ? new Claim.Proceed(1) : new Claim.InProgress();
            }
            if (!row.requestHash().equals(requestHash)) {
                return new Claim.Reused();
            }
            if (row.completed()) {
                return new Claim.Replay(row.responseStatus().orElseThrow(), row.responseRef().orElseThrow(), row.responseHash().orElseThrow());
            }
            if (now.isBefore(row.claimedAt().plusSeconds(rule.idempotencyLeaseSeconds()))) {
                return new Claim.InProgress();
            }
            return store.takeOver(caller.subject(), key, row.claimSeq(), now) ? new Claim.Proceed(row.claimSeq() + 1) : new Claim.InProgress();
        });
    }

    /**
     * 완료 기록. 이 청구 순번의 진행 중 행일 때만 쓴다 — 임차가 지나 다른 요청이 인수했으면 {@code false}(그쪽이 기록한다).
     *
     * @param responseRef 영수증 튜플(JCS 텍스트, 개인정보 자리가 없는 닫힌 모양 — 승인 Q4)
     */
    @NotAnEntry("write-path plumbing called only by the API idempotency interceptor after the use case answered; records the closed receipt tuple")
    public boolean complete(Caller caller, String key, int claimSeq, int status, String responseRef, String responseHash) {
        Objects.requireNonNull(caller, "caller");
        requireKey(key);
        Objects.requireNonNull(responseRef, "responseRef");
        if (!HASH.matcher(responseHash).matches()) {
            throw new IllegalArgumentException("response hash must be lowercase SHA-256 hex");
        }
        if (!storable(status)) {
            throw new IllegalArgumentException("only 2xx, 409 and 422 responses are stored");
        }
        return transactions.inTenant(caller.tenant(), () -> store.complete(caller.subject(), key, claimSeq, status, responseRef, responseHash));
    }

    /** 해제: 저장하지 않는 응답 뒤 이 청구 순번의 진행 중 행을 지운다. 인수됐거나 이미 완료됐으면 {@code false}(그쪽 것이다). */
    @NotAnEntry("write-path plumbing called only by the API idempotency interceptor after an unstored response; deletes the caller's own in-progress claim")
    public boolean release(Caller caller, String key, int claimSeq) {
        Objects.requireNonNull(caller, "caller");
        requireKey(key);
        return transactions.inTenant(caller.tenant(), () -> store.release(caller.subject(), key, claimSeq));
    }

    /**
     * 저장하는 응답: 2xx·409·422. 5xx·401·404·428은 저장하지 않는다 — 404를 저장하면 키 재사용으로 "예전엔 없었다"를 알 수 있다(존재 누설). 저장하지 않은
     * 응답의 청구는 해제한다({@link #release}).
     */
    public static boolean storable(int status) {
        return (status >= 200 && status < 300) || status == 409 || status == 422;
    }

    private static void requireKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Idempotency-Key must match " + KEY.pattern());
        }
    }
}
