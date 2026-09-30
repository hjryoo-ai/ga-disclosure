package com.ga.disclosure.workflow.customer;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 고객 데이터 키 순환({@code customer rekey}, Phase 2 지시문 §3). ① 활성 키 RETIRED + 새 키 ACTIVE ② 활성 키가 아닌 키의 행을
 * 배치(트랜잭션별)로 재암호화 ③ 쓰는 행이 없어진 RETIRED 키의 키 재료 파기. 중간에 멈추면 다시 실행해 이어간다(다시 순환하지만
 * 남은 행은 전부 최신 키로 옮겨진다). 순환 도중에는 구·신 키가 함께 복호화된다. 단계마다 감사 기록.
 */
public final class CustomerRekeyService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TARGET = "CUSTOMER_DATA_KEY";

    private final CustomerVault vault;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;

    public CustomerRekeyService(CustomerVault vault, AuditPort audit, WorkflowTransactions transactions, Clock clock) {
        this.vault = Objects.requireNonNull(vault, "vault");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RekeyReport rekey(TenantId tenant, Actor actor, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batch size must be >= 1");
        }
        CustomerVault.KeyRotation rotation = transactions.inTenant(tenant, () -> {
            CustomerVault.KeyRotation r = vault.rotate(clock.instant());
            ObjectNode detail = JSON.createObjectNode().put("retired", r.retiredKeyId().orElse(null));
            record(actor, AuditAction.CUSTOMER_KEY_ROTATE, r.activeKeyId(), detail);
            return r;
        });
        int total = 0;
        while (true) {
            int done = transactions.inTenant(tenant, () -> {
                int n = vault.reencryptBatch(batchSize);
                if (n > 0) {
                    record(actor, AuditAction.CUSTOMER_REKEY, rotation.activeKeyId(), JSON.createObjectNode().put("rows", n));
                }
                return n;
            });
            if (done == 0) {
                break;
            }
            total += done;
        }
        List<String> destroyed = transactions.inTenant(tenant, () -> {
            List<String> ids = new ArrayList<>(vault.destroyUnusedRetiredKeys(clock.instant()));
            ids.forEach(id -> record(actor, AuditAction.CUSTOMER_KEY_DESTROY, id, JSON.createObjectNode()));
            return ids;
        });
        return new RekeyReport(tenant, rotation.retiredKeyId(), rotation.activeKeyId(), total, destroyed);
    }

    private void record(Actor actor, AuditAction action, String keyId, ObjectNode detail) {
        audit.append(new AuditEntry(clock.instant(), actor.subject(), actor.role(), action, TARGET, keyId, detail));
    }
}
