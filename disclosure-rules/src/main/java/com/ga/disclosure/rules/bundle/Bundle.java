package com.ga.disclosure.rules.bundle;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;

/**
 * 규제 번들 1건(contracts/rules/bundles/…). 룰 번들과 서식 번들 두 종류다. {@link #bodyHash()} = SHA-256(JCS(body))이며
 * {@link #bundleId()}의 {@code @} 뒤 12자와 같다(로더가 검증).
 */
public sealed interface Bundle permits RuleBundle, TemplateBundle {

    String bundleId();

    LocalDate applyFrom();

    /** 번들이 스스로 닫힌 구간을 선언할 때만(보통 NULL — 후속 번들의 supersedes가 닫는다). */
    LocalDate applyTo();

    /** 방어적 복사본. */
    JsonNode body();

    String bodyHash();

    BundleKind kind();
}
