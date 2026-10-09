package com.ga.disclosure.workflow.customer;

import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.domain.vo.CustomerRef;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * 고객 등록 API의 유스케이스(6B 계획 §9, 승인 §4 조건 6, 10단계 회신): 한도 확인 뒤 Phase 2·3A {@link RegisterCustomer}를 <b>그대로</b> 부른다(같은
 * 트랜잭션 — 암호화·가명 발급·등록 키 유일, 감사 {@code CUSTOMER_REGISTER} CREATED/NOOP). 중복은 등록 키(주체 + 멱등 키)로만 본다 — 이름·전화·생년월일을
 * 비교하지 않으므로 "이미 등록됨"이라는 신호가 없고, 같은 사람을 다른 키로 두 번 등록하면 가명이 둘이다(§14 #22).
 * <ul>
 *   <li>한도: 룰 {@code customers.registerPerMinute}(사규 덮어쓰기) — 주체별 advisory 잠금 아래 지난 60초 등록 감사 행 수가 한도 이상이면
 *       {@link RegistrationRateLimitedException}(429, 저장·감사 없음).</li>
 *   <li>응답: 가명 + 영수증 ID({@link CustomerReceiptPort} — 결정론적 파생). 첫 등록과 같은 키의 NOOP가 같은 {@code {customerRef, receiptId}}다
 *       (생성 여부를 내보내지 않는다).</li>
 * </ul>
 */
public final class CustomerRegistrationService {

    static final Duration WINDOW = Duration.ofSeconds(60);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final RegisterCustomer register;
    private final CustomerRegistrationLimitStore limits;
    private final CustomerReceiptPort receipts;
    private final RuleResolver rules;
    private final WorkflowTransactions transactions;
    private final AuthorizationPort authz;
    private final Clock clock;

    public CustomerRegistrationService(RegisterCustomer register, CustomerRegistrationLimitStore limits, CustomerReceiptPort receipts, RuleResolver rules,
                                       WorkflowTransactions transactions, AuthorizationPort authz, Clock clock) {
        this.register = Objects.requireNonNull(register, "register");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 등록 영수증 — 가명과 영수증 ID뿐. */
    public record Receipt(CustomerRef ref, UUID receiptId) {
        public Receipt {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(receiptId, "receiptId");
        }
    }

    /**
     * {@code input}은 인가 뒤에 부른다(게이트와 같은 순서 — 칸이 없는 주체는 본문 형식과 무관하게 같은 404). 인자는 기준일(KST 오늘)이고 생년월일 상한으로
     * 값객체에 넘긴다. 형식 오류는 한도·감사 전에 나간다(등록 시도로 세지 않는다).
     */
    @UseCaseEntry(Action.CUSTOMER_REGISTER)
    public Receipt register(Caller caller, RegistrationKey key, Function<LocalDate, NewCustomer> input) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(input, "input");
        return transactions.inTenant(caller.tenant(), () -> {
            Actor actor = authz.require(caller, Action.CUSTOMER_REGISTER, Target.none());
            LocalDate today = LocalDate.ofInstant(clock.instant(), SEOUL);
            NewCustomer customer = Objects.requireNonNull(input.apply(today), "customer");
            int perMinute = rules.resolve(caller.tenant(), today).customersRegisterPerMinute();
            if (limits.lockAndCountSince(actor.subject(), clock.instant().minus(WINDOW)) >= perMinute) {
                throw new RegistrationRateLimitedException();
            }
            RegisterCustomer.Registration r = register.execute(caller, key, customer);
            return new Receipt(r.ref(), receipts.receipt(caller.tenant(), r.ref(), key));
        });
    }
}
