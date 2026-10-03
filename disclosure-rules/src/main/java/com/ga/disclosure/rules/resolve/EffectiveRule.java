package com.ga.disclosure.rules.resolve;

import com.ga.disclosure.domain.enums.IdentityMethod;
import com.ga.disclosure.domain.enums.ManagerConfirmMode;
import com.ga.disclosure.domain.enums.PiiField;
import com.ga.disclosure.domain.enums.RetentionAnchor;
import com.ga.disclosure.domain.enums.SignOrder;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignatureMethod;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.enums.ValidationStage;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.domain.vo.RetentionPeriod;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
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

    /** 고객 채널별 정책(룰 {@code channels}, 값은 {@code {enabled, requiresManagerReview?}} 객체 — 4 계획 승인 Q5). */
    public Map<SignatureChannel, ChannelPolicy> channels() {
        JsonNode channels = object("channels");
        Map<SignatureChannel, ChannelPolicy> out = new EnumMap<>(SignatureChannel.class);
        for (String name : channels.propertyNames()) {
            JsonNode policy = channels.get(name);
            String path = "channels." + name;
            if (policy == null || !policy.isObject()) {
                throw missing(path);
            }
            JsonNode review = policy.get("requiresManagerReview");
            if (review != null && !review.isBoolean()) {
                throw missing(path + ".requiresManagerReview");
            }
            out.put(SignatureChannel.valueOf(name), new ChannelPolicy(boolValue(policy, path + ".enabled", "enabled"),
                    review != null && review.booleanValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    /** 고객 채널 하나의 정책. 룰에 그 채널 키가 없으면 예외(기본값 없음). */
    public ChannelPolicy channel(SignatureChannel channel) {
        ChannelPolicy policy = channels().get(channel);
        if (policy == null) {
            throw missing("channels." + channel.name());
        }
        return policy;
    }

    /** 고객 서명 세션 유효 시간(분, 룰 {@code sessionTtlMinutes}) — TOUCH_PAD·PAPER_SCAN. REMOTE_LINK는 {@link #remoteLinkTtlHours()}. */
    public int sessionTtlMinutes(SignatureChannel channel) {
        return intValue(object("sessionTtlMinutes"), "sessionTtlMinutes." + channel.name(), channel.name());
    }

    /** 설계사 서명 방식(룰 {@code agentSignMethod}): DRAWN 또는 SSO_APPROVAL. */
    public SignatureMethod agentSignMethod() {
        SignatureMethod method = SignatureMethod.valueOf(text(body, "agentSignMethod", "agentSignMethod"));
        if (method == SignatureMethod.UPLOADED_SCAN) {
            throw missing("agentSignMethod (UPLOADED_SCAN is not an agent method)");
        }
        return method;
    }

    /** 채널별 본인확인 수단(닫힌 어휘 {@link IdentityMethod}). 채널 키가 없으면 예외. */
    public List<IdentityMethod> identityMethods(SignatureChannel channel) {
        return identityCheck(channel).stream().map(IdentityMethod::valueOf).toList();
    }

    /** 대리 서명 탐지: 같은 기기 지문으로 같은 날(Asia/Seoul) 서명한 서로 다른 고객 수 임계치. */
    public int sameDeviceDistinctCustomersPerDay() {
        return intValue(object("proxySignatureDetection"), "proxySignatureDetection.sameDeviceDistinctCustomersPerDay",
                "sameDeviceDistinctCustomersPerDay");
    }

    /** 대리 서명 탐지: 같은 IP 임계치(선택 — 없으면 IP 지표를 쓰지 않는다, 4 계획 승인 Q8). */
    public java.util.OptionalInt sameIpDistinctCustomersPerDay() {
        JsonNode n = object("proxySignatureDetection").get("sameIpDistinctCustomersPerDay");
        if (n == null) {
            return java.util.OptionalInt.empty();
        }
        if (!n.isInt()) {
            throw missing("proxySignatureDetection.sameIpDistinctCustomersPerDay");
        }
        return java.util.OptionalInt.of(n.intValue());
    }

    /** 대리 서명 탐지: 원격 링크 발송부터 서명까지 최소 초. */
    public int minSecondsFromSendToSign() {
        return intValue(object("proxySignatureDetection"), "proxySignatureDetection.minSecondsFromSendToSign", "minSecondsFromSendToSign");
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

    /** 보존기간 = {@code retentionYears}년 + {@code retentionDays}일(산식 하나, 5 계획 승인 Q5). GLOBAL 전용 키. */
    public RetentionPeriod retentionPeriod() {
        return new RetentionPeriod(intValue("retentionYears"), intValue("retentionDays"));
    }

    /** 일일 앵커 머클 트리 깊이(GLOBAL 전용, 비오버라이드 — 루트는 전 테넌트 하나, 5 계획 §2). */
    public int anchoringTreeDepth() {
        return intValue(object("anchoring"), "anchoring.treeDepth", "treeDepth");
    }

    /** COMPLETED 확인서가 계약일 앵커를 기다리는 일수(파기 절차 파라미터 — 판정 시점의 ACTIVE 룰로 읽는다, 승인 Q10). */
    public int contractLinkWaitDays() {
        return intValue(object("retention"), "retention.contractLinkWaitDays", "contractLinkWaitDays");
    }

    /** 법적 보류 사유 코드(닫힌 목록). */
    public List<LifecycleReasonRule> legalHoldReasons() {
        return lifecycleReasons("legalHoldReasons");
    }

    public int legalHoldReasonTextMaxLength() {
        return intValue("legalHoldReasonTextMaxLength");
    }

    /** 고객 파기 유예: 그 고객의 마지막 확인서 파기 뒤 일수. */
    public int customerGraceDaysAfterLastDestruction() {
        return intValue(object("customerRef"), "customerRef.graceDaysAfterLastDestruction", "graceDaysAfterLastDestruction");
    }

    /** 확인서가 한 번도 없었던 고객의 파기: 등록 뒤 일수. */
    public int customerAbandonedDays() {
        return intValue(object("customerRef"), "customerRef.abandonedDays", "abandonedDays");
    }

    /** 영수증 없는 앵커를 verify tenant가 발견으로 올리기까지의 일수. */
    public int unstampedAnchorAlertDays() {
        return intValue(object("verify"), "verify.unstampedAnchorAlertDays", "unstampedAnchorAlertDays");
    }

    /** 보존기한 앵커(3B 수용심사 §3-3). GLOBAL 전용 키. */
    public List<RetentionAnchor> retentionAnchors() {
        return List.copyOf(strings("retentionAnchors", RetentionAnchor::valueOf));
    }

    /** 무효 사유 코드 목록(닫힌 목록, 3B 수용심사 §2-2). */
    public List<LifecycleReasonRule> voidReasons() {
        return lifecycleReasons("voidReasons");
    }

    /** 정정 사유 코드 목록(닫힌 목록, 3B 수용심사 §2-2). */
    public List<LifecycleReasonRule> supersedeReasons() {
        return lifecycleReasons("supersedeReasons");
    }

    /** 무효·정정 사유 텍스트 상한. */
    public int lifecycleReasonTextMaxLength() {
        return intValue("lifecycleReasonTextMaxLength");
    }

    private List<LifecycleReasonRule> lifecycleReasons(String key) {
        List<LifecycleReasonRule> out = new ArrayList<>();
        for (JsonNode n : array(key)) {
            out.add(new LifecycleReasonRule(text(n, key + "[].code", "code"), text(n, key + "[].label", "label"),
                    n.path("requiresText").asBoolean(false)));
        }
        return List.copyOf(out);
    }

    public boolean retainUnlinked() {
        return boolValue("retainUnlinked");
    }

    /** 검증 규칙과 실행 단계(룰 순서대로). ID 중복·빈 단계·모르는 단계는 예외(기본값 없음). */
    public List<ValidationStep> validations() {
        List<ValidationStep> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode n : array("validations")) {
            String id = text(n, "validations[].id", "id");
            if (!seen.add(id)) {
                throw missing("validations[] (duplicate id " + id + ")");
            }
            JsonNode stages = n.get("stages");
            if (stages == null || !stages.isArray() || stages.isEmpty()) {
                throw missing("validations[" + id + "].stages");
            }
            Set<ValidationStage> parsed = EnumSet.noneOf(ValidationStage.class);
            for (JsonNode s : stages) {
                if (!s.isString()) {
                    throw missing("validations[" + id + "].stages[]");
                }
                parsed.add(ValidationStage.valueOf(s.asString()));
            }
            out.add(new ValidationStep(id, parsed));
        }
        return List.copyOf(out);
    }

    /** {@code stage}에서 실행할 규칙 ID(룰 순서대로). */
    public List<String> validationsFor(ValidationStage stage) {
        return validations().stream().filter(v -> v.runsIn(stage)).map(ValidationStep::id).toList();
    }

    // ------------------------------------------------------------------ 예외 승인·마스킹

    /**
     * 산출불가·임시등록·검증 오버라이드의 예외 승인 주체(설계서 §6.5). {@code managerConfirmMode}와 무관하게 항상 필요하며
     * 서명이 아니라 review 기록이다. GLOBAL 전용 키(사규가 열 수 없다).
     */
    public SignerRole exceptionApprovalRole() {
        return SignerRole.valueOf(text(object("exceptionApproval"), "exceptionApproval.role", "role"));
    }

    /** 표시용 부분 마스킹 규칙(설계서 §9, TODO(confirm#12)). */
    public MaskingRule masking(PiiField field) {
        JsonNode rule = object("masking").get(field.ruleKey());
        String path = "masking." + field.ruleKey();
        if (rule == null || !rule.isObject()) {
            throw missing(path);
        }
        return new MaskingRule(intValue(rule, path + ".keepFirst", "keepFirst"), intValue(rule, path + ".keepLast", "keepLast"),
                text(rule, path + ".maskChar", "maskChar"));
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
