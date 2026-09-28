package com.ga.disclosure.domain.vo;

/**
 * 서식 템플릿 참조(템플릿 ID + 버전).
 *
 * @param templateId 예: {@code STANDARD}
 * @param version    1 이상
 */
public record TemplateRef(String templateId, int version) {

    public TemplateRef {
        Patterns.require(Patterns.UPPER_CODE, templateId, "template id");
        if (version < 1) {
            throw new IllegalArgumentException("template version must be >= 1: " + version);
        }
    }

    public static TemplateRef of(String templateId, int version) {
        return new TemplateRef(templateId, version);
    }
}
