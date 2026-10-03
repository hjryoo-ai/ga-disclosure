package com.ga.disclosure.workflow.customer;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * 고객 등록 유스케이스(3A 지시문 §2, Phase 2 {@link CustomerRefService}의 등록 규약 위): 입력은 {@code Sensitive} 값객체만 담은
 * {@link NewCustomer}, 출력은 {@link CustomerRef}. 등록 멱등 키가 같으면 새 고객을 만들지 않는다 — 데모 파일을 두 번 수입해도, API 재시도가
 * 와도 한 명이다. 감사 detail에는 키 ID·연락처 유무·결과만 있고 개인정보는 없다.
 */
public final class RegisterCustomer {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CustomerVault vault;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;
    private final AuthorizationPort authz;

    public RegisterCustomer(CustomerVault vault, AuditPort audit, WorkflowTransactions transactions, Clock clock, AuthorizationPort authz) {
        this.vault = Objects.requireNonNull(vault, "vault");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.authz = Objects.requireNonNull(authz, "authz");
    }

    public record Registration(CustomerRef ref, boolean created) {
    }

    @UseCaseEntry(Action.CUSTOMER_REGISTER)
    public Registration execute(Caller caller, RegistrationKey key, NewCustomer customer) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(customer, "customer");
        return transactions.inTenant(caller.tenant(), () -> {
            Actor actor = authz.require(caller, Action.CUSTOMER_REGISTER, Target.none());
            CustomerRef candidate = CustomerRef.of("CR-" + UUID.randomUUID().toString().replace("-", ""));
            CustomerVault.KeyedInsert r = vault.insertKeyed(candidate, customer, clock.instant(), key);
            ObjectNode detail = JSON.createObjectNode().put("registrationKey", key.value()).put("outcome", r.created() ? "CREATED" : "NOOP")
                    .put("hasPhone", customer.phone().isPresent()).put("hasBirthDate", customer.birthDate().isPresent());
            if (r.keyIdOrNull() != null) {
                detail.put("keyId", r.keyIdOrNull());
            }
            audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), AuditAction.CUSTOMER_REGISTER, "CUSTOMER_REF",
                    r.ref().value(), detail));
            return new Registration(r.ref(), r.created());
        });
    }
}
