package com.ga.disclosure.rules.resolve;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 화면 미리보기 워터마크(룰 {@code preview.watermark}, GLOBAL 전용 — Phase 7 계획 ⑥·승인 Q5 권장): 문구와 역할 표기. 치환자는 닫힌 집합
 * {@value #ROLE}·{@value #AT}뿐이다(스키마 pattern과 같은 검사 — 다른 중괄호가 있으면 거부).
 */
public record PreviewWatermarkRule(String text, Map<String, String> roleLabels) {

    public static final String ROLE = "{role}";
    public static final String AT = "{at}";
    private static final Pattern CLOSED = Pattern.compile("^(?:[^{}]|\\{role}|\\{at})+$");

    public PreviewWatermarkRule {
        Objects.requireNonNull(text, "text");
        if (!CLOSED.matcher(text).matches()) {
            throw new IllegalArgumentException("preview.watermark.text has a placeholder outside {role}, {at}");
        }
        roleLabels = Map.copyOf(roleLabels);
    }

    /** 열람자 역할(허가를 준 역할 이름)과 시각 표기를 넣은 문구. 표기가 없는 역할은 예외(룰 스키마가 사람 역할 셋을 요구한다). */
    public String render(String role, String at) {
        String label = roleLabels.get(Objects.requireNonNull(role, "role"));
        if (label == null) {
            throw new IllegalStateException("preview.watermark.roleLabels has no label for " + role);
        }
        return text.replace(ROLE, label).replace(AT, Objects.requireNonNull(at, "at"));
    }
}
