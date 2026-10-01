package com.ga.disclosure.rules.bundle;

import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.domain.vo.TemplateRef;
import com.ga.disclosure.rules.template.TemplateLayoutCheck;
import com.ga.platform.canonical.CanonicalizationException;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 번들 파일 로더. 순서대로 검사하고 하나라도 어기면 {@link InvalidBundleException}.
 * <ol>
 *   <li>엄격 파싱 — 중복 키 거부(해시 대상 원문이므로).</li>
 *   <li>계약 스키마 {@code rule-bundle.schema.json} — body는 kind에 따라 룰/서식 본문 스키마로 검증된다. 서식은 배치 구조도 본다
 *       ({@link TemplateLayoutCheck}).</li>
 *   <li>{@code bodyHash = SHA-256(JCS(body))}를 계산해 {@code bundleId}의 {@code @} 뒤 12자, 그리고 ID 앞부분
 *       (룰은 {@code ruleVersionId}, 서식은 {@code templateId.v{version}})과 대조.</li>
 * </ol>
 */
public final class BundleLoader {

    private static final int ID_HASH_PREFIX = 12;

    private BundleLoader() {
    }

    public static Bundle parse(String source, String json) {
        JsonNode node;
        try {
            node = Canonicalizer.parseStrict(json);
        } catch (CanonicalizationException e) {
            throw new InvalidBundleException(source, List.of(e.getMessage()));
        }
        List<String> problems = new ArrayList<>(RuleSchemas.validateBundle(node));
        if (!problems.isEmpty()) {
            throw new InvalidBundleException(source, problems);
        }
        JsonNode body = node.get("body");
        if (BundleKind.valueOf(node.get("kind").asString()) == BundleKind.TEMPLATE) {
            problems.addAll(TemplateLayoutCheck.problems(body));
        }
        String bodyHash = Sha256.of(Canonicalizer.canonicalize(body));
        String bundleId = node.get("bundleId").asString();
        LocalDate applyFrom = LocalDate.parse(node.get("applyFrom").asString());
        LocalDate applyTo = node.hasNonNull("applyTo") ? LocalDate.parse(node.get("applyTo").asString()) : null;
        if (applyTo != null && !applyTo.isAfter(applyFrom)) {
            problems.add("applyTo must be after applyFrom");
        }
        JsonNode supersedes = node.get("supersedes");

        Bundle bundle = switch (BundleKind.valueOf(node.get("kind").asString())) {
            case RULE -> new RuleBundle(bundleId, RuleVersionId.of(node.get("ruleVersionId").asString()), applyFrom, applyTo,
                    supersedes == null ? null : RuleVersionId.of(supersedes.get("ruleVersionId").asString()), body, bodyHash);
            case TEMPLATE -> new TemplateBundle(bundleId,
                    TemplateRef.of(node.get("templateId").asString(), node.get("version").asInt()),
                    TemplateType.valueOf(node.get("templateType").asString()), applyFrom, applyTo,
                    supersedes == null ? null : TemplateRef.of(supersedes.get("templateId").asString(), supersedes.get("version").asInt()),
                    body, bodyHash);
        };

        String expectedId = idPrefix(bundle) + "@" + bodyHash.substring(0, ID_HASH_PREFIX);
        if (!expectedId.equals(bundleId)) {
            problems.add("bundleId " + bundleId + " does not match the recomputed " + expectedId
                    + " (bundleId = {id}@{first 12 hex of SHA-256(JCS(body))})");
        }
        if (bundle instanceof TemplateBundle t && t.supersedes() != null && !t.supersedes().templateId().equals(t.template().templateId())) {
            problems.add("a template bundle can only supersede an earlier version of the same template");
        }
        if (!problems.isEmpty()) {
            throw new InvalidBundleException(source, problems);
        }
        return bundle;
    }

    /** 번들 ID의 {@code @} 앞부분: 룰은 {@code ruleVersionId}, 서식은 {@code templateId.v{version}}. */
    public static String idPrefix(Bundle bundle) {
        return switch (bundle) {
            case RuleBundle r -> r.ruleVersionId().value();
            case TemplateBundle t -> t.template().templateId() + ".v" + t.template().version();
        };
    }
}
