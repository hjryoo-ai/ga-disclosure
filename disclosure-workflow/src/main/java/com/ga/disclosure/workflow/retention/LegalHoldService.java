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
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
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

/**
 * 법적 보류(5 계획 §8.6). 통제는 DB 보류다 — 파기 배치·파기 함수가 건너뛴다. 저장소 보류는 벨트: 지원하면 대상 객체(고객 보류면 그 고객의 봉인된 확인서
 * 객체 전부)의 모든 버전에 켜고, 해제 때 끈다(다른 활성 보류가 덮는 객체는 끄지 않는다). 저장소 보류 실패·미지원은 보고만 하고 DB 보류는 유지한다.
 * {@code retention_until}은 바꾸지 않는다. 사유 코드는 설정 시점(오늘 KST)의 룰 {@code legalHoldReasons}, 텍스트 상한은
 * {@code legalHoldReasonTextMaxLength}. 해제 사유 코드는 해제 시점 룰 {@code legalHoldReleaseReasons}(닫힌 목록 — 5 수용심사 D12가 §14 #15를
 * 6A에서 닫았다)이고, 해제자는 설정자와 달라야 한다(4-eyes — 여기와 DB {@code ck_legal_hold_four_eyes} 양쪽, 5 수용심사 결정 1).
 */
public final class LegalHoldService {

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
    private final AuthorizationPort authz;

    public LegalHoldService(LegalHoldStore holds, RetentionStore retention, DocumentRecordStore records, ArtifactStore storage, RuleResolver rules,
                            AuditPort audit, WorkflowTransactions transactions, Clock clock, Supplier<UUID> ids, AuthorizationPort authz) {
        this.holds = Objects.requireNonNull(holds, "holds");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.records = Objects.requireNonNull(records, "records");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.authz = Objects.requireNonNull(authz, "authz");
    }

    @UseCaseEntry(Action.LEGAL_HOLD_PLACE)
    public Outcome place(Caller caller, Target target, String reasonCode, String reasonTextOrNull) {
        TenantId tenant = caller.tenant();
        Instant now = clock.instant();
        UUID holdId = ids.get();
        Set<String> keys = transactions.inTenant(tenant, () -> {
            Actor actor = authz.require(caller, Action.LEGAL_HOLD_PLACE, switch (target) {
                case Target.Disclosure d -> com.ga.disclosure.workflow.authz.Target.disclosure(d.id());
                case Target.Customer c -> com.ga.disclosure.workflow.authz.Target.none();
            });
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
            // 대상 행을 잠근 뒤 판정한다 — 폐기·파기가 먼저 잠갔으면 기다렸다가 지워진 대상을 만난다(묘비에 보류는 의미가 없다, 6B 중간 회신 ④)
            boolean erased = switch (target) {
                case Target.Disclosure d -> holds.lockErased(d.id());
                case Target.Customer c -> holds.lockErased(c.ref());
            };
            if (erased) {
                throw new LegalHoldRejectedException("TARGET_ALREADY_DESTROYED");
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

    @UseCaseEntry(Action.LEGAL_HOLD_RELEASE)
    public Outcome release(Caller caller, UUID holdId, String releaseReasonCode) {
        TenantId tenant = caller.tenant();
        Instant now = clock.instant();
        Set<String> keys = transactions.inTenant(tenant, () -> {
            Actor actor = authz.require(caller, Action.LEGAL_HOLD_RELEASE, new com.ga.disclosure.workflow.authz.Target.Hold(holdId));
            LegalHoldStore.Hold hold = holds.find(holdId).orElseThrow(() -> new LegalHoldRejectedException("NOT_FOUND"));
            EffectiveRule rule = rules.resolve(tenant, LocalDate.ofInstant(now, SEOUL));
            if (rule.legalHoldReleaseReasons().stream().noneMatch(r -> r.code().equals(releaseReasonCode))) {
                throw new LegalHoldRejectedException("BAD_RELEASE_REASON");
            }
            if (hold.placedBy().equals(actor.subject())) {
                throw new LegalHoldRejectedException("FOUR_EYES_REQUIRED");
            }
            if (!holds.release(holdId, actor.subject(), now, releaseReasonCode)) {
                throw new LegalHoldRejectedException("ALREADY_RELEASED");
            }
            Target target = hold.disclosureOrNull() != null ? new Target.Disclosure(hold.disclosureOrNull()) : new Target.Customer(hold.customerOrNull());
            audit.append(new AuditEntry(now, actor.subject(), actor.role(), AuditAction.LEGAL_HOLD_RELEASED, targetKind(target), targetId(target),
                    JSON.createObjectNode().put("holdId", holdId.toString()).put("releaseReasonCode", releaseReasonCode)
                            .put("ruleVersionId", rule.globalRuleVersionId().value())));
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
