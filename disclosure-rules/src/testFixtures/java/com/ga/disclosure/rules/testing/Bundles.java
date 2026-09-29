package com.ga.disclosure.rules.testing;

import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.disclosure.domain.enums.RuleStatus;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.bundle.Bundle;
import com.ga.disclosure.rules.bundle.BundleLoader;
import com.ga.disclosure.rules.bundle.RuleBundle;
import com.ga.disclosure.rules.bundle.TemplateBundle;
import com.ga.disclosure.rules.template.FormTemplate;
import com.ga.disclosure.rules.version.RuleVersion;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;

/** 테스트 픽스처: 클래스패스의 정본 번들(ga-contracts/rules/bundles/…)을 읽어 포트 레코드로 바꾼다. */
public final class Bundles {

    public static final String DISC_2026_07 = "rules/DISC-2026-07.bundle.json";
    public static final String DISC_2027_01 = "rules/DISC-2027-01.bundle.json";
    public static final String STANDARD_V1 = "templates/STANDARD-v1.bundle.json";

    private Bundles() {
    }

    public static String text(String relative) {
        try (InputStream in = Bundles.class.getResourceAsStream("/ga-contracts/rules/bundles/" + relative)) {
            if (in == null) {
                throw new IllegalArgumentException("no bundle " + relative);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static Bundle load(String relative) {
        return BundleLoader.parse(relative, text(relative));
    }

    public static RuleBundle rule(String relative) {
        return (RuleBundle) load(relative);
    }

    public static TemplateBundle template(String relative) {
        return (TemplateBundle) load(relative);
    }

    /** 번들 룰의 GLOBAL 복제본(상태·적용 종료일 지정). */
    public static RuleVersion global(RuleBundle bundle, RuleStatus status, LocalDate applyTo) {
        return new RuleVersion(bundle.ruleVersionId(), RuleScope.GLOBAL, bundle.applyFrom(), applyTo, status, "OPERATOR:test",
                Instant.parse("2026-06-30T00:00:00Z"), bundle.body(), bundle.bundleId(), bundle.bodyHash());
    }

    /** 임의 본문의 GLOBAL 룰(해시는 자리값 — 해석기는 해시를 보지 않는다). */
    public static RuleVersion global(String id, LocalDate from, LocalDate to, RuleStatus status, JsonNode body) {
        return new RuleVersion(RuleVersionId.of(id), RuleScope.GLOBAL, from, to, status, "OPERATOR:test",
                Instant.parse("2026-06-30T00:00:00Z"), body, id + "@000000000000", "0".repeat(64));
    }

    public static RuleVersion tenant(String id, LocalDate from, LocalDate to, RuleStatus status, JsonNode body) {
        return new RuleVersion(RuleVersionId.of(id), RuleScope.TENANT, from, to, status,
                status == RuleStatus.DRAFT ? null : "COMPLIANCE:test", status == RuleStatus.DRAFT ? null : Instant.parse("2026-06-30T00:00:00Z"),
                body, null, null);
    }

    public static FormTemplate template(TemplateBundle bundle, LocalDate applyTo) {
        JsonNode body = bundle.body();
        return new FormTemplate(bundle.template(), bundle.templateType(), bundle.applyFrom(), applyTo, body.get("fields"),
                body.get("layout"), body.get("pendingConfirmation"), bundle.bundleId(), bundle.bodyHash());
    }
}
