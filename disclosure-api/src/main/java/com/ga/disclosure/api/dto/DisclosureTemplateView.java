package com.ga.disclosure.api.dto;

import java.util.List;

/**
 * 확인서가 고정한 서식의 화면 문구(계약 {@code DisclosureTemplate}, Phase 7 승인 Q3): 버전 ID·내용 해시·제목·항목·섹션뿐 — 상태·검증 결과 없음.
 * {@code pinned}는 언제나 참이다(확인서는 초안 생성 때 서식을 고정한다).
 */
public record DisclosureTemplateView(String templateId, int version, String bundleHash, boolean pinned, String title, List<Field> fields,
                                     List<Section> sections) {

    public record Field(String code, String label, boolean required, int order, String section, String unavailableText) {
    }

    public record Section(String code, String label, List<String> fields) {
    }
}
