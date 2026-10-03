package com.ga.disclosure.workflow.retention;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditEntry;
import com.ga.disclosure.audit.AuditPort;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.resolve.LifecycleReasonRule;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.Actor;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.artifact.ArtifactMissingException;
import com.ga.disclosure.workflow.artifact.ArtifactStore;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 법적 보류(5 계획 §8.6). 통제는 DB 보류다 — 파기 배치·파기 함수가 건너뛴다. 저장소 보류는 벨트: 지원하면 대상 객체(고객 보류면 그 고객의 봉인된 확인서
 * 객체 전부)의 모든 버전에 켜고, 해제 때 끈다(다른 활성 보류가 덮는 객체는 끄지 않는다). 저장소 보류 실패·미지원은 보고만 하고 DB 보류는 유지한다.
 * {@code retention_until}은 바꾸지 않는다. 사유 코드는 설정 시점(오늘 KST)의 룰 {@code legalHoldReasons}, 텍스트 상한은
 * {@code legalHoldReasonTextMaxLength}. 해제 사유 코드는 룰 어휘가 없다 — 형식만 본다.
 */
public final class LegalHoldService {

    // TODO(confirm#15): 해제 사유 코드의 닫힌 목록(룰 어휘)은 미정 — 그 전까지 형식(대문자 코드)만 검사한다
    static final Pattern RELEASE_REASON = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");
    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public sealed interface Target {
        record Disclosure(DisclosureId id) implements Target {
        }

        record Customer(CustomerRef ref) implements Target {
        }
    }

    /** 저장소 보류 결과: 켜거나 끈 객체 수, 실패 수, 미지원 여부. */
    public record StorageHold(int applied, int failed, boolean unsupported) {
    }

    public record Outcome(UUID holdId, StorageHold storage) {
    }

    private final LegalHoldStore holds;
    private final RetentionStore retention;
    private final DocumentRecordStore records;
    private final ArtifactStore storage;
    private final RuleResolver rules;
    private final AuditPort audit;
    private final WorkflowTransactions transactions;
    private final Clock clock;
    private final Supplier<UUID> ids;

    public LegalHoldService(LegalHoldStore holds, RetentionStore retention, DocumentRecordStore records, ArtifactStore storage, RuleResolver rules,
                            AuditPort audit, WorkflowTransactions transactions, Clock clock, Supplier<UUID> ids) {
        this.holds = Objects.requireNonNull(holds, "holds");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.records = Objects.requireNonNull(records, "records");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    public Outcome place(TenantId tenant, Actor actor, Target target, String reasonCode, String reasonTextOrNull) {
        Instant now = clock.instant();
        UUID holdId = ids.get();
        Set<String> keys = transactions.inTenant(tenant, () -> {
            EffectiveRule rule = rules.resolve(tenant, LocalDate.ofInstant(now, SEOUL));
            LifecycleReasonRule reason = rule.legalHoldReasons().stream().filter(r -> r.code().equals(reasonCode)).findFirst()
                    .orElseThrow(() -> new LegalHoldRejectedException("UNKNOWN_REASON"));
            String text = reasonTextOrNull == null || reasonTextOrNull.isBlank() ? null : reasonTextOrNull;
            if (reason.requiresText() && text == null) {
                throw new LegalHoldRejectedException("TEXT_REQUIRED");
            }
            if (text != null && text.codePointCount(0, text.length()) > rule.legalHoldReasonTextMaxLength()) {
                throw new LegalHoldRejectedException("TEXT_TOO_LONG");
            }
            boolean already = switch (target) {
                case Target.Disclosure d -> holds.activeFor(d.id()).isPresent();
                case Target.Customer c -> holds.activeFor(c.ref()).isPresent();
            };
            if (already) {
                throw new LegalHoldRejectedException("ALREADY_HELD");
            }
            holds.insert(new LegalHoldStore.Hold(holdId, target instanceof Target.Disclosure d ? d.id() : null,
                    target instanceof Target.Customer c ? c.ref() : null, reasonCode, text, actor.subject(), now, null, null, null));
            ObjectNode detail = JSON.createObjectNode().put("holdId", holdId.toString()).put("reasonCode", reasonCode)
                    .put("reasonTextLength", text == null ? 0 : text.codePointCount(0, text.length()))
                    .put("ruleVersionId", rule.globalRuleVersionId().value());
            audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.LEGAL_HOLD_PLACED, targetKind(target), targetId(target), detail));
            return keys(target);
        });
        return new Outcome(holdId, storageHold(keys, true));
    }

    public Outcome release(TenantId tenant, Actor actor, UUID holdId, String releaseReasonCode) {
        if (releaseReasonCode == null || !RELEASE_REASON.matcher(releaseReasonCode).matches()) {
            throw new LegalHoldRejectedException("BAD_RELEASE_REASON");
        }
        Instant now = clock.instant();
        Set<String> keys = transactions.inTenant(tenant, () -> {
            LegalHoldStore.Hold hold = holds.find(holdId).orElseThrow(() -> new LegalHoldRejectedException("NOT_FOUND"));
            if (!holds.release(holdId, actor.subject(), now, releaseReasonCode)) {
                throw new LegalHoldRejectedException("ALREADY_RELEASED");
            }
            Target target = hold.disclosureOrNull() != null ? new Target.Disclosure(hold.disclosureOrNull()) : new Target.Customer(hold.customerOrNull());
            audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.LEGAL_HOLD_RELEASED, targetKind(target), targetId(target),
                    JSON.createObjectNode().put("holdId", holdId.toString()).put("releaseReasonCode", releaseReasonCode)));
            return stillUncovered(target);
        });
        return new Outcome(holdId, storageHold(keys, false));
    }

    /** 해제 뒤 저장소 보류를 끌 객체: 다른 활성 보류(고객 보류 ↔ 확인서 보류)가 덮는 확인서의 객체는 뺀다. */
    private Set<String> stillUncovered(Target released) {
        Set<String> out = new LinkedHashSet<>();
        for (DisclosureId id : disclosures(released)) {
            Optional<CustomerRef> customer = retention.candidate(id, LocalDate.ofInstant(clock.instant(), SEOUL)).map(RetentionStore.Candidate::customer);
            boolean covered = holds.activeFor(id).isPresent() || customer.flatMap(holds::activeFor).isPresent();
            if (!covered) {
                out.addAll(keysOf(id));
            }
        }
        return out;
    }

    private Set<String> keys(Target target) {
        Set<String> out = new LinkedHashSet<>();
        disclosures(target).forEach(id -> out.addAll(keysOf(id)));
        return out;
    }

    private List<DisclosureId> disclosures(Target target) {
        return switch (target) {
            case Target.Disclosure d -> List.of(d.id());
            case Target.Customer c -> retention.sealedDisclosuresOf(c.ref());
        };
    }

    private List<String> keysOf(DisclosureId id) {
        List<String> keys = new ArrayList<>();
        records.artifacts(id).forEach(a -> keys.add(a.storageKey()));
        records.evidence(id).forEach(e -> keys.add(e.storageKey()));
        return keys;
    }

    private StorageHold storageHold(Set<String> keys, boolean on) {
        if (storage.capabilities().legalHold() == ArtifactStore.Support.UNSUPPORTED) {
            return new StorageHold(0, 0, true);
        }
        int applied = 0;
        int failed = 0;
        for (String key : keys) {
            try {
                storage.setLegalHold(key, on);
                applied++;
            } catch (ArtifactMissingException e) {
                // 이미 지워진 객체(파기 뒤) — 걸 것이 없다
            } catch (RuntimeException e) {
                failed++;
            }
        }
        return new StorageHold(applied, failed, false);
    }

    private static String targetKind(Target t) {
        return t instanceof Target.Disclosure ? "DISCLOSURE" : "CUSTOMER_REF";
    }

    private static String targetId(Target t) {
        return switch (t) {
            case Target.Disclosure d -> d.id().toString();
            case Target.Customer c -> c.ref().value();
        };
    }
}
