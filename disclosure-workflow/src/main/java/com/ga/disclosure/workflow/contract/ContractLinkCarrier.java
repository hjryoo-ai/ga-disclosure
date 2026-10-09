package com.ga.disclosure.workflow.contract;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.audit.outbox.EventType;
import com.ga.disclosure.audit.outbox.OutboxPayloads;
import com.ga.disclosure.audit.outbox.OutboxPort;
import com.ga.disclosure.domain.enums.RetentionAnchor;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.DisclosureNo;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.sign.retention.RetentionAnchors;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.authz.NotAnEntry;
import com.ga.disclosure.workflow.disclosure.LinkCarry;
import com.ga.platform.canonical.Sha256;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link LinkCarry} 구현 — 봉인 유스케이스의 트랜잭션 안에서만 불린다(인가는 봉인이 이미 했다). 새 행의 출처는 옛 행의 출처, 출처 참조는
 * {@code 옛 참조@carry:새 확인서 ID}(재수입 멱등 키는 옛 행이 계속 갖는다).
 */
public final class ContractLinkCarrier implements LinkCarry {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ContractLinkStore store;
    private final AuditPort audit;
    private final OutboxPort outbox;
    private final Supplier<UUID> ids;

    public ContractLinkCarrier(ContractLinkStore store, AuditPort audit, OutboxPort outbox, Supplier<UUID> ids) {
        this.store = Objects.requireNonNull(store, "store");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    @NotAnEntry("seal plumbing (6B interim ③): moves the predecessor's active contract link to the superseding version inside SEAL's transaction")
    public LocalDate carryOnSeal(Actor actor, DisclosureId sealed, DisclosureNo number, DisclosureId predecessor, EffectiveRule pinned,
                                 LocalDate retentionUntil, Instant at) {
        Optional<ContractLinkStore.ActiveLink> held = store.activeLink(predecessor);
        if (held.isEmpty()) {
            return retentionUntil;
        }
        ContractLinkStore.ActiveLink from = held.get();
        UUID linkId = ids.get();
        store.carry(from.linkId(), linkId, at);
        store.insertLink(new ContractLinkStore.NewLink(linkId, sealed, from.policyNo(), from.applicationNo(), from.contractDate(), from.insurerCode(),
                from.productKey(), from.source(), from.sourceRef() + "@carry:" + sealed, at, actor.subject()));
        store.mirror(sealed, from.policyNo(), from.contractDate());
        LocalDate after = RetentionAnchors.until(retentionUntil, pinned.retentionAnchors(), Map.of(RetentionAnchor.CONTRACT_DATE, from.contractDate()),
                pinned.retentionPeriod());
        boolean extended = after.isAfter(retentionUntil) && store.extendRetention(sealed, after);
        LocalDate result = extended ? after : retentionUntil;
        audit.append(new AuditEntry(at, actor.subject(), actor.role(), AuditAction.CONTRACT_LINK_CARRIED, "DISCLOSURE", sealed.toString(),
                JSON.createObjectNode().put("reason", "SUPERSEDING_VERSION_SEALED").put("fromDisclosureId", predecessor.toString())
                        .put("fromLinkId", from.linkId().toString()).put("linkId", linkId.toString())
                        .put("policyNoSha256", Sha256.of(from.policyNo().getBytes(StandardCharsets.UTF_8)))
                        .put("contractDate", from.contractDate().toString()).put("retentionUntilBefore", retentionUntil.toString())
                        .put("retentionUntilAfter", result.toString()).put("retention", extended ? "EXTENDED" : "NOT_EXTENDED")
                        .put("ruleVersionId", pinned.globalRuleVersionId().value())));
        outbox.append(EventType.PolicyLinked, sealed.toString(), at, OutboxPayloads.policyLinked(sealed.value(), number.value(), linkId,
                from.contractDate(), from.insurerCode(), null));
        return result;
    }
}
