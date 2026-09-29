package com.ga.disclosure.rules.resolve;

import com.ga.disclosure.domain.enums.ManagerConfirmMode;
import com.ga.disclosure.domain.enums.SignOrder;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 기준일에 해석된 유효 룰: GLOBAL(규제) 본문 위에 TENANT(사규) 본문의 허용 키를 덮은 결과(설계서 §6.2).
 *
 * <p>재현 가능한 값객체다 — 기준일, 두 버전 ID, 병합 본문과 그 해시({@code SHA-256(JCS(body))})를 담는다. 확인서에는 두 버전 ID가
 * 박제된다({@code disclosure.rule_version_id}, {@code tenant_rule_version_id}).
 *
 * <p>접근자는 본문의 값을 타입으로 <b>해석만</b> 한다. 기본값이 없다 — 키가 없거나 형식이 다르면 {@link MissingRuleKeyException}
 * (룰 스키마가 필수 키를 보장한다). 임계치·코드값·서명자·채널은 전부 본문에서 온다.
 *
 * @param tenantRuleVersionId 적용된 사규 버전, 없으면 {@code null}
 */
public record EffectiveRule(
        LocalDate asOf,
        RuleVersionId globalRuleVersionId,
        RuleVersionId tenantRuleVersionId,
        JsonNode body,
        String bodyHash) {

    public EffectiveRule {
        Objects.requireNonNull(asOf, "asOf");
        Objects.requireNonNull(globalRuleVersionId, "globalRuleVersionId");
        Objects.requireNonNull(body, "body");
        body = body.deepCopy();
        String recomputed = Sha256.of(Canonicalizer.canonicalize(body));
        if (bodyHash == null) {
            bodyHash = recomputed;
        } else if (!bodyHash.equals(recomputed)) {
            throw new IllegalArgumentException("bodyHash does not match SHA-256(JCS(body))");
        }
    }

    /** 병합 본문으로 만든다(해시는 계산). */
    public static EffectiveRule of(LocalDate asOf, RuleVersionId global, RuleVersionId tenantOrNull, JsonNode mergedBody) {
        return new EffectiveRule(asOf, global, tenantOrNull, mergedBody, null);
    }

    @Override
    public JsonNode body() {
        return body.deepCopy();
    }

    public Optional<RuleVersionId> tenantRuleVersion() {
        return Optional.ofNullable(tenantRuleVersionId);
    }

    /** 로그·감사 detail용 재현 정보. */
    public String describe() {
        return "asOf=" + asOf + " global=" + globalRuleVersionId + " tenant=" + (tenantRuleVersionId == null ? "-" : tenantRuleVersionId)
                + " bodyHash=" + bodyHash;
    }

    // ------------------------------------------------------------------ 비교·등급

    public int minCompare() {
        return intValue("minCompare");
    }

    public boolean distinctInsurer() {
        return boolValue("distinctInsurer");
    }

    public boolean gradeRequiredWhenLargeGa() {
        return boolValue(object("gradeRequired"), "gradeRequired.whenLargeGa", "whenLargeGa");
    }

    public Set<String> allowedGradingPolicies() {
        return strings("allowedGradingPolicies", Function.identity());
    }

    public Set<String> allowedRankingPolicies() {
        return strings("allowedRankingPolicies", Function.identity());
    }

    public Set<TieBreak> allowedTieBreaks() {
        return strings("allowedTieBreaks", TieBreak::valueOf);
    }

    public int snapshotMaxAgeDays() {
        return intValue("snapshotMaxAgeDays");
    }

    // ------------------------------------------------------------------ 추천사유

    public List<ReasonCodeRule> reasonCodes() {
        List<ReasonCodeRule> out = new ArrayList<>();
        for (JsonNode n : array("reasonCodes")) {
            out.add(new ReasonCodeRule(ReasonCode.of(text(n, "reasonCodes[].code", "code")), text(n, "reasonCodes[].label", "label"),
                    n.path("requiresText").asBoolean(false), n.path("auto").asBoolean(false)));
        }
        return List.copyOf(out);
    }

    /** {@code auto=true}인 코드(시스템이 조건에 따라 부가, R-REQUESTED). */
    public Set<ReasonCode> autoReasonCodes() {
        Set<ReasonCode> out = new LinkedHashSet<>();
        reasonCodes().stream().filter(ReasonCodeRule::auto).forEach(r -> out.add(r.code()));
        return Collections.unmodifiableSet(out);
    }

    public int reasonTextMaxLength() {
        return intValue("reasonTextMaxLength");
    }

    // ------------------------------------------------------------------ 서명

    /** 필수 서명자(순서 포함). */
    public List<SignerRole> signerSet() {
        return List.copyOf(strings("signerSet", SignerRole::valueOf));
    }

    public SignOrder signOrder() {
        return SignOrder.valueOf(text(body, "signOrder", "signOrder"));
    }

    public ManagerConfirmMode managerConfirmMode() {
        return ManagerConfirmMode.valueOf(text(body, "managerConfirmMode", "managerConfirmMode"));
    }

    public int signDeadlineDays() {
        return intValue("signDeadlineDays");
    }

    public boolean gateRequiresManager() {
        return boolValue("gateRequiresManager");
    }

    public int remoteLinkTtlHours() {
        return intValue("remoteLinkTtlHours");
    }

    public Map<SignatureChannel, Boolean> channels() {
        JsonNode channels = object("channels");
        Map<SignatureChannel, Boolean> out = new EnumMap<>(SignatureChannel.class);
        for (String name : channels.propertyNames()) {
            out.put(SignatureChannel.valueOf(name), boolValue(channels, "channels." + name, name));
        }
        return Collections.unmodifiableMap(out);
    }

    /** 채널별 본인확인 수단(데이터 문자열, 어휘는 설계서 §5 {@code signature.identity_check}). 채널 키가 없으면 예외. */
    public List<String> identityCheck(SignatureChannel channel) {
        JsonNode checks = object("identityCheck").get(channel.name());
        if (checks == null || !checks.isArray()) {
            throw missing("identityCheck." + channel.name());
        }
        List<String> out = new ArrayList<>();
        checks.forEach(n -> out.add(n.asString()));
        return List.copyOf(out);
    }

    public int identityCheckMaxFailures() {
        return intValue(object("identityCheck"), "identityCheck.maxFailures", "maxFailures");
    }

    // ------------------------------------------------------------------ 보관·검증 목록·사규 허용 키

    public int retentionYears() {
        return intValue("retentionYears");
    }

    public boolean retainUnlinked() {
        return boolValue("retainUnlinked");
    }

    /** 실행할 검증 규칙 ID(순서대로). */
    public List<String> validations() {
        return List.copyOf(strings("validations", Function.identity()));
    }

    public Set<String> tenantOverridable() {
        return strings("tenantOverridable", Function.identity());
    }

    // ------------------------------------------------------------------

    private JsonNode object(String key) {
        JsonNode n = body.get(key);
        if (n == null || !n.isObject()) {
            throw missing(key);
        }
        return n;
    }

    private JsonNode array(String key) {
        JsonNode n = body.get(key);
        if (n == null || !n.isArray()) {
            throw missing(key);
        }
        return n;
    }

    private <T> Set<T> strings(String key, Function<String, T> parse) {
        Set<T> out = new LinkedHashSet<>();
        for (JsonNode n : array(key)) {
            if (!n.isString()) {
                throw missing(key + "[]");
            }
            out.add(parse.apply(n.asString()));
        }
        return Collections.unmodifiableSet(out);
    }

    private int intValue(String key) {
        return intValue(body, key, key);
    }

    private int intValue(JsonNode parent, String path, String key) {
        JsonNode n = parent.get(key);
        if (n == null || !n.isInt()) {
            throw missing(path);
        }
        return n.intValue();
    }

    private boolean boolValue(String key) {
        return boolValue(body, key, key);
    }

    private boolean boolValue(JsonNode parent, String path, String key) {
        JsonNode n = parent.get(key);
        if (n == null || !n.isBoolean()) {
            throw missing(path);
        }
        return n.booleanValue();
    }

    private String text(JsonNode parent, String path, String key) {
        JsonNode n = parent.get(key);
        if (n == null || !n.isString()) {
            throw missing(path);
        }
        return n.asString();
    }

    private MissingRuleKeyException missing(String path) {
        return new MissingRuleKeyException("effective rule has no valid '" + path + "' (" + describe() + ")");
    }
}
