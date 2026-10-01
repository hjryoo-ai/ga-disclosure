package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.workflow.catalog.PanelEntry;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * 애그리게이트 밖에서 주입되는 판단 재료(3A 계획 §2): 테넌트 대형 GA 여부, 위탁 패널 판정 함수(Phase 2 포트 어댑터), 확인서에 고정된
 * 서식, 그리고 서식 결속이 가리키는 상담일 기준 사실(3B) — 유사상품군 이름, 상담일 위탁 패널(코드 순), 고정 룰의 추천사유 라벨.
 * 애그리게이트는 저장소를 부르지 않는다.
 *
 * @param productGroupNameOrNull 상담일 카탈로그의 상품군 이름(없으면 {@code null} — HEADER_PRODUCT_GROUP 결속이 비어 검증이 잡는다)
 * @param panelOnConsultDate     상담일에 위탁 중인 보험사(코드 순)
 * @param reasonLabels           고정 룰 {@code reasonCodes}의 코드 → 라벨
 */
public record DisclosureContext(boolean largeGa, BiPredicate<InsurerCode, LocalDate> panel, TemplateResolution template,
                                String productGroupNameOrNull, List<PanelEntry> panelOnConsultDate, Map<ReasonCode, String> reasonLabels) {

    public DisclosureContext {
        Objects.requireNonNull(panel, "panel");
        Objects.requireNonNull(template, "template");
        panelOnConsultDate = List.copyOf(panelOnConsultDate);
        reasonLabels = Map.copyOf(reasonLabels);
    }

    public Optional<String> productGroupName() {
        return Optional.ofNullable(productGroupNameOrNull);
    }

    /** 상담일 패널에서 그 보험사의 이름. */
    public Optional<String> insurerName(InsurerCode insurer) {
        return panelOnConsultDate.stream().filter(p -> p.insurer().equals(insurer)).map(PanelEntry::insurerName).findFirst();
    }
}
