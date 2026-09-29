package com.ga.disclosure.rules.resolve;

import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.rules.version.RuleVersion;
import com.ga.disclosure.rules.version.RuleVersionPort;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 기준일 룰 해석기(설계서 §6.2). 엔진과 같은 규약: 기준일 필수, scope별 단건 해석, 2건 이상이면 Ambiguous로 즉시 실패.
 * <ol>
 *   <li>GLOBAL 정확히 1건 — 0건 {@link ResolutionFailure#NO_GLOBAL_RULE}, 2건 이상 {@link ResolutionFailure#AMBIGUOUS}.</li>
 *   <li>TENANT 0~1건 — 2건 이상 {@link ResolutionFailure#AMBIGUOUS}.</li>
 *   <li>병합: GLOBAL 본문 위에 TENANT 본문의 최상위 키를 덮는다. 단 GLOBAL {@code tenantOverridable}에 있는 키만 — 밖의 키가
 *       하나라도 있으면 {@link ResolutionFailure#DISALLOWED_OVERRIDE}(조용히 무시하지 않는다). 중첩 객체는 통째로 교체한다.</li>
 * </ol>
 * DB 배타 제약이 2건을 원천 차단하지만 해석기는 그것에 기대지 않는다(이중 방어).
 */
public final class RuleResolver {

    private final RuleVersionPort port;

    public RuleResolver(RuleVersionPort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    public EffectiveRule resolve(TenantId tenant, LocalDate asOf) {
        Objects.requireNonNull(tenant, "tenant");
        Objects.requireNonNull(asOf, "asOf is mandatory (consult date)");
        List<RuleVersion> global = inScope(port.findActive(tenant, RuleScope.GLOBAL, asOf), RuleScope.GLOBAL, asOf);
        if (global.isEmpty()) {
            throw new RuleResolutionException(ResolutionFailure.NO_GLOBAL_RULE, "no GLOBAL rule in force on " + asOf + " for " + tenant);
        }
        if (global.size() > 1) {
            throw ambiguous(RuleScope.GLOBAL, asOf, global);
        }
        List<RuleVersion> local = inScope(port.findActive(tenant, RuleScope.TENANT, asOf), RuleScope.TENANT, asOf);
        if (local.size() > 1) {
            throw ambiguous(RuleScope.TENANT, asOf, local);
        }
        return merge(asOf, global.getFirst(), local.isEmpty() ? null : local.getFirst());
    }

    /**
     * GLOBAL 위에 TENANT를 병합한다(해석과 사규 승인이 같은 규칙을 쓴다). TENANT가 {@code null}이면 GLOBAL 그대로.
     */
    public static EffectiveRule merge(LocalDate asOf, RuleVersion global, RuleVersion tenantOrNull) {
        if (global.scope() != RuleScope.GLOBAL || (tenantOrNull != null && tenantOrNull.scope() != RuleScope.TENANT)) {
            throw new IllegalArgumentException("merge expects a GLOBAL rule and an optional TENANT rule");
        }
        ObjectNode merged = (ObjectNode) global.body();
        if (tenantOrNull == null) {
            return EffectiveRule.of(asOf, global.id(), null, merged);
        }
        Set<String> allowed = new TreeSet<>();
        JsonNode overridable = merged.get("tenantOverridable");
        if (overridable != null && overridable.isArray()) {
            overridable.forEach(n -> allowed.add(n.asString()));
        }
        JsonNode tenantBody = tenantOrNull.body();
        Set<String> disallowed = new TreeSet<>(tenantBody.propertyNames());
        disallowed.removeAll(allowed);
        if (!disallowed.isEmpty()) {
            throw new RuleResolutionException(ResolutionFailure.DISALLOWED_OVERRIDE, "TENANT rule " + tenantOrNull.id()
                    + " overrides " + disallowed + " which GLOBAL rule " + global.id() + " does not open (tenantOverridable=" + allowed + ")");
        }
        for (String key : tenantBody.propertyNames()) {
            merged.set(key, tenantBody.get(key));
        }
        return EffectiveRule.of(asOf, global.id(), tenantOrNull.id(), merged);
    }

    private static List<RuleVersion> inScope(List<RuleVersion> found, RuleScope scope, LocalDate asOf) {
        for (RuleVersion r : found) {
            if (r.scope() != scope || !r.covers(asOf)) {
                throw new IllegalStateException("port returned " + r.id() + " (" + r.scope() + ") for " + scope + " on " + asOf);
            }
        }
        return found;
    }

    private static RuleResolutionException ambiguous(RuleScope scope, LocalDate asOf, List<RuleVersion> rules) {
        return new RuleResolutionException(ResolutionFailure.AMBIGUOUS,
                rules.size() + " " + scope + " rules in force on " + asOf + ": " + rules.stream().map(r -> r.id().value()).toList());
    }
}
