package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.IssuerMode;
import com.ga.disclosure.domain.grade.EngineSnapshot;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.domain.vo.TemplateRef;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** 저장소가 읽은 확인서 한 건(애그리게이트 복원 재료). 유스케이스가 고정된 룰·서식으로 {@link DisclosureContext}를 만들어 복원한다. */
public record DisclosureRecord(
        DisclosureId id,
        String agentId,
        CustomerRef customerRef,
        GroupCode groupCode,
        LocalDate consultDate,
        RuleVersionId ruleVersionId,
        RuleVersionId tenantRuleVersionIdOrNull,
        TemplateRef template,
        IssuerMode issuerMode,
        Lineage lineage,
        DisclosureStatus status,
        List<DisclosureItem> items,
        EngineSnapshot snapshotOrNull,
        SealStamp sealOrNull,
        VoidMark voidOrNull,
        DisclosureId supersededByOrNull,
        LifecycleReason supersedeReasonOrNull) {

    public DisclosureRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ruleVersionId, "ruleVersionId");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(lineage, "lineage");
        items = List.copyOf(items);
    }

    public Disclosure restore(DisclosureContext context) {
        return Disclosure.restore(id, agentId, customerRef, groupCode, consultDate, ruleVersionId, tenantRuleVersionIdOrNull, template,
                issuerMode, context, lineage, status, items, snapshotOrNull, sealOrNull, voidOrNull, supersededByOrNull,
                supersedeReasonOrNull);
    }
}
