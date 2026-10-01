package com.ga.disclosure.seal.canonical;

import com.ga.disclosure.domain.disclosure.FieldValue;
import com.ga.disclosure.domain.disclosure.ItemGrade;
import com.ga.disclosure.domain.enums.IssuerMode;
import com.ga.disclosure.domain.grade.EngineSnapshot;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.domain.vo.TemplateRef;
import com.ga.platform.core.tenant.TenantId;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 봉인 본문의 재료(성명 제외 — 성명은 {@link CanonicalDocumentBuilder#build}에 봉투째 따로 넘긴다). 봉인 유스케이스가 고정 룰·서식·상담일
 * 카탈로그로 해석한 결과(상품군 이름·패널·사유 라벨)까지 채워 넘긴다 — 빌더는 저장소를 모른다.
 */
public record CanonicalInput(
        TenantId tenantId,
        DisclosureId disclosureId,
        int version,
        DisclosureId supersedesIdOrNull,
        String agentId,
        CustomerRef customerRef,
        LocalDate consultDate,
        GroupCode groupCode,
        String groupName,
        IssuerMode issuerMode,
        RuleVersionId ruleVersionId,
        RuleVersionId tenantRuleVersionIdOrNull,
        TemplateRef template,
        EngineSnapshot snapshot,
        List<PanelInsurer> panel,
        List<Item> items) {

    public CanonicalInput {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(disclosureId, "disclosureId");
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(customerRef, "customerRef");
        Objects.requireNonNull(consultDate, "consultDate");
        Objects.requireNonNull(groupCode, "groupCode");
        Objects.requireNonNull(groupName, "groupName");
        Objects.requireNonNull(issuerMode, "issuerMode");
        Objects.requireNonNull(ruleVersionId, "ruleVersionId");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(snapshot, "snapshot");
        panel = List.copyOf(panel);
        items = List.copyOf(items);
    }

    /** 상담일 위탁 패널의 보험사 1건. */
    public record PanelInsurer(InsurerCode code, String name) {
        public PanelInsurer {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(name, "name");
        }
    }

    /** 추천사유 1건: 코드와 고정 룰의 라벨. */
    public record Reason(String code, String label) {
        public Reason {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(label, "label");
        }
    }

    /**
     * 비교 항목 1건(항목 번호 순).
     *
     * @param reasons 추천사유(라벨 포함). 사유가 없으면 빈 목록 — 본문에서는 {@code recommendation: null}
     */
    public record Item(int itemNo, ProductKey productKeyOrNull, InsurerCode insurer, String productName, GroupCode group, boolean tempProduct,
                       String quoteDocNoOrNull, boolean recommended, boolean requestedByCustomer, Map<String, FieldValue> fieldValues,
                       ItemGrade grade, List<Reason> reasons, String reasonTextOrNull) {
        public Item {
            Objects.requireNonNull(insurer, "insurer");
            Objects.requireNonNull(productName, "productName");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(grade, "a sealed item is graded");
            fieldValues = Map.copyOf(fieldValues);
            reasons = List.copyOf(reasons);
        }
    }
}
