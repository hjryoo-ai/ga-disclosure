package com.ga.disclosure.rules.template;

import com.ga.disclosure.domain.enums.IdentityMethod;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignatureMethod;
import com.ga.disclosure.domain.enums.SignerRole;
import tools.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 서명본 PDF의 서명 외관 페이지 문구(서식 {@code layout.signaturePage}, 설계서 §6.5 v1.9). 역할·채널·방법·본인확인 수단은 코드의 닫힌
 * 어휘이고 인쇄 문구는 전부 서식 데이터다 — 렌더러에 문구 리터럴이 없다. 빠진 키는 기본값 없이 예외(스키마가 먼저 막는다).
 * 문구는 협회 서식 확인 전 가정(TODO(confirm#2), Phase 7 전 확정 — 3B 수용심사 §3-6).
 */
public record SignaturePageLayout(String title, Columns columns, String originalPdfLabel, Map<SignerRole, String> roleLabels,
                                  Map<SignatureChannel, String> channelLabels, Map<SignatureMethod, String> methodLabels,
                                  Map<IdentityMethod, String> identityLabels, String passLabel, String failLabel) {

    /** 표 머리 문구. */
    public record Columns(String role, String channel, String method, String signedAt, String identity, String image) {
    }

    public SignaturePageLayout {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(originalPdfLabel, "originalPdfLabel");
        roleLabels = Collections.unmodifiableMap(new EnumMap<>(roleLabels));
        channelLabels = Collections.unmodifiableMap(new EnumMap<>(channelLabels));
        methodLabels = Collections.unmodifiableMap(new EnumMap<>(methodLabels));
        identityLabels = Collections.unmodifiableMap(new EnumMap<>(identityLabels));
        Objects.requireNonNull(passLabel, "passLabel");
        Objects.requireNonNull(failLabel, "failLabel");
    }

    /** 서식의 {@code layout}에서 읽는다. */
    public static SignaturePageLayout of(TemplateResolution template) {
        JsonNode page = template.layout().path("signaturePage");
        if (!page.isObject()) {
            throw new IllegalStateException("template " + template.ref() + " has no layout.signaturePage");
        }
        JsonNode c = page.path("columns");
        return new SignaturePageLayout(text(page, "title"),
                new Columns(text(c, "role"), text(c, "channel"), text(c, "method"), text(c, "signedAt"), text(c, "identity"), text(c, "image")),
                text(page, "originalPdfLabel"),
                labels(page.path("roleLabels"), SignerRole.class, "roleLabels"),
                labels(page.path("channelLabels"), SignatureChannel.class, "channelLabels"),
                labels(page.path("methodLabels"), SignatureMethod.class, "methodLabels"),
                labels(page.path("identityLabels"), IdentityMethod.class, "identityLabels"),
                text(page.path("resultLabels"), "PASS"), text(page.path("resultLabels"), "FAIL"));
    }

    public String role(SignerRole role) {
        return require(roleLabels, role, "roleLabels");
    }

    public String channel(SignatureChannel channel) {
        return require(channelLabels, channel, "channelLabels");
    }

    public String method(SignatureMethod method) {
        return require(methodLabels, method, "methodLabels");
    }

    public String identity(IdentityMethod method) {
        return require(identityLabels, method, "identityLabels");
    }

    private static <E extends Enum<E>> String require(Map<E, String> labels, E key, String what) {
        String label = labels.get(key);
        if (label == null) {
            throw new IllegalStateException("layout.signaturePage." + what + " has no label for " + key);
        }
        return label;
    }

    private static <E extends Enum<E>> Map<E, String> labels(JsonNode node, Class<E> type, String what) {
        if (!node.isObject()) {
            throw new IllegalStateException("layout.signaturePage." + what + " is missing");
        }
        Map<E, String> out = new EnumMap<>(type);
        for (String name : node.propertyNames()) {
            out.put(Enum.valueOf(type, name), text(node, name));
        }
        return out;
    }

    private static String text(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || !v.isString() || v.asString().isEmpty()) {
            throw new IllegalStateException("layout.signaturePage key missing: " + key);
        }
        return v.asString();
    }
}
