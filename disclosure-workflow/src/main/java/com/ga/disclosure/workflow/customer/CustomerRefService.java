package com.ga.disclosure.workflow.customer;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.NotAnEntry;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * 고객 참조(설계서 §5 {@code customer_ref}, CLAUDE.md 절대 규칙 6). 이 시스템은 CRM이 아니다 — 확인서 기재·서명 링크 발송·
 * 본인확인에 필요한 이름·연락처·생년월일만, 암호문으로만 보유한다. 모든 행위는 감사 기록이며 detail에 개인정보가 없다.
 * 고객 ID는 개인정보와 무관한 무작위 값({@code CR-} + 32 hex)이다.
 */
public final class CustomerRefService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TARGET = "CUSTOMER_REF";

    private final CustomerVault vault;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;
    private final AuthorizationPort authz;

    public CustomerRefService(CustomerVault vault, AuditPort audit, WorkflowTransactions transactions, Clock clock, AuthorizationPort authz) {
        this.vault = Objects.requireNonNull(vault, "vault");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authz = Objects.requireNonNull(authz, "authz");
    }

    @UseCaseEntry(Action.CUSTOMER_REGISTER)
    public CustomerRef register(Caller caller, NewCustomer customer) {
        Objects.requireNonNull(customer, "customer");
        return transactions.inTenant(caller.tenant(), () -> {
            Actor actor = authz.require(caller, Action.CUSTOMER_REGISTER, Target.none());
            CustomerRef ref = CustomerRef.of("CR-" + UUID.randomUUID().toString().replace("-", ""));
            String keyId = vault.insert(ref, customer, clock.instant());
            ObjectNode detail = JSON.createObjectNode().put("keyId", keyId).put("hasPhone", customer.phone().isPresent())
                    .put("hasBirthDate", customer.birthDate().isPresent());
            record(actor, AuditAction.CUSTOMER_REGISTER, ref, detail);
            return ref;
        });
    }

    @NotAnEntry("vault read with a CUSTOMER_VIEW audit row; no HTTP or CLI caller — customer APIs are a separate 6B plan (6A approval Q17)")
    public Customer lookup(TenantId tenant, Actor actor, CustomerRef ref) {
        return transactions.inTenant(tenant, () -> {
            Customer customer = find(tenant, ref);
            record(actor, AuditAction.CUSTOMER_VIEW, ref, JSON.createObjectNode().put("keyId", customer.keyId()));
            return customer;
        });
    }

    /**
     * 원격 서명 링크 발송 전용 연락처. 발송 번호는 여기서만 나온다(설계사가 임의 번호로 보낼 수 없다, 설계서 §6.5).
     * 목적과 호출자가 준 참조 ID(예: 서명 세션)를 감사에 남긴다.
     */
    @NotAnEntry("internal step of the notification dispatcher, which has authorized NOTIFY_DISPATCH in the same transaction")
    public Sensitive<PhoneNumber> phoneForNotification(TenantId tenant, Actor actor, CustomerRef ref, NotificationPurpose purpose,
                                                       String reference) {
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(reference, "reference");
        return transactions.inTenant(tenant, () -> {
            Customer customer = find(tenant, ref);
            Sensitive<PhoneNumber> phone = customer.phone()
                    .orElseThrow(() -> new CustomerNotFoundException(tenant + ": " + ref + " has no phone number (on-site signing only)"));
            record(actor, AuditAction.CUSTOMER_PHONE_READ, ref,
                    JSON.createObjectNode().put("purpose", purpose.name()).put("reference", reference).put("keyId", customer.keyId()));
            return phone;
        });
    }

    private Customer find(TenantId tenant, CustomerRef ref) {
        return vault.find(ref).orElseThrow(() -> new CustomerNotFoundException(tenant + ": no customer " + ref));
    }

    private void record(Actor actor, AuditAction action, CustomerRef ref, ObjectNode detail) {
        audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), action, TARGET, ref.value(), detail));
    }
}
