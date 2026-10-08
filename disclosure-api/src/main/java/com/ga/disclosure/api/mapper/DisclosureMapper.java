package com.ga.disclosure.api.mapper;

import com.ga.disclosure.api.dto.DisclosureDetail;
import com.ga.disclosure.api.dto.DisclosurePage;
import com.ga.disclosure.api.dto.DisclosureSummary;
import com.ga.disclosure.api.error.MalformedRequestException;
import com.ga.disclosure.api.error.NotFoundException;
import com.ga.disclosure.domain.disclosure.ItemGrade;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.disclosure.DisclosureItem;
import com.ga.disclosure.workflow.disclosure.DisclosureLookup;
import com.ga.disclosure.workflow.disclosure.DisclosureQueryService;
import com.ga.disclosure.workflow.disclosure.DisclosureRecord;
import com.ga.disclosure.workflow.page.Page;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 확인서 조회 DTO 변환(6A 계획 §4.1). 경로 ID 형식이 틀리면 없는 자원과 같은 404. */
public final class DisclosureMapper {

    private DisclosureMapper() {
    }

    public static DisclosureId id(String path) {
        try {
            return DisclosureId.of(UUID.fromString(path));
        } catch (IllegalArgumentException e) {
            throw new NotFoundException();
        }
    }

    public static Optional<DisclosureStatus> status(String statusOrNull) {
        if (statusOrNull == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(DisclosureStatus.valueOf(statusOrNull));
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException("status");
        }
    }

    public static DisclosurePage page(Page<DisclosureLookup.Listed> page) {
        return new DisclosurePage(page.items().stream().map(DisclosureMapper::summary).toList(), page.next().orElse(null));
    }

    static DisclosureSummary summary(DisclosureLookup.Listed l) {
        return new DisclosureSummary(l.id().value().toString(), l.disclosureNo().orElse(null), l.version(), l.status().name(), l.agentId(),
                l.customerRef().value(), l.group().value(), l.consultDate().toString(), text(l.sealedAt()), text(l.destroyedAt()));
    }

    public static DisclosureDetail detail(DisclosureQueryService.Detail detail) {
        DisclosureRecord d = detail.disclosure();
        var seal = d.sealOrNull();
        var snapshot = d.snapshotOrNull();
        return new DisclosureDetail(d.id().value().toString(), seal == null ? null : seal.number().value(), d.lineage().version(),
                d.lineage().supersedesIdOrNull() == null ? null : d.lineage().supersedesIdOrNull().value().toString(),
                d.supersededByOrNull() == null ? null : d.supersededByOrNull().value().toString(), d.status().name(), d.agentId(),
                d.customerRef().value(), d.groupCode().value(), d.consultDate().toString(), d.ruleVersionId().value(),
                d.tenantRuleVersionIdOrNull() == null ? null : d.tenantRuleVersionIdOrNull().value(), d.template().templateId(),
                d.template().version(), d.issuerMode().name(), d.items().stream().map(DisclosureMapper::item).toList(),
                snapshot == null ? null : new DisclosureDetail.Snapshot(snapshot.snapshot().snapshotId().value(),
                        snapshot.snapshot().gradingPolicyVersionId(), snapshot.snapshot().rankingPolicyVersionId(), snapshot.snapshot().tieBreak().name(),
                        snapshot.generatedAt().toString()),
                seal == null ? null : new DisclosureDetail.Seal(seal.number().value(), seal.sealedAt().toString(), seal.canonicalHash().hex(),
                        seal.pdfHash().hex(), seal.chainSeq(), seal.retentionUntil().toString()),
                d.voidOrNull() == null ? null : d.voidOrNull().at().toString(), d.voidOrNull() == null ? null : d.voidOrNull().reason().code(),
                d.supersedeReasonOrNull() == null ? null : d.supersedeReasonOrNull().code(),
                d.signatures().stream().map(s -> new DisclosureDetail.Signature(s.role().name(), s.signedAt().toString())).toList(),
                d.completedAtOrNull() == null ? null : d.completedAtOrNull().toString(), text(detail.destroyedAt()), detail.asOf().toString());
    }

    private static DisclosureDetail.Item item(DisclosureItem i) {
        var draft = i.draft();
        DisclosureDetail.Grade grade = switch (i.gradeOrNull()) {
            case null -> null;
            case ItemGrade.Ok ok -> new DisclosureDetail.Grade("OK", ok.gradeCode(), ok.gradeLabel(), ok.gradeOrdinal(), ok.rankInSet(), ok.tie(),
                    ok.ratioToAvg() == null ? null : ok.ratioToAvg().value(), null, null);
            case ItemGrade.Unavailable u -> new DisclosureDetail.Grade("UNAVAILABLE", null, null, null, null, null, null, u.reason(), u.source().name());
        };
        var rec = i.recommendationOrNull();
        return new DisclosureDetail.Item(i.itemNo(), draft.productKeyOrNull() == null ? null : draft.productKeyOrNull().value(), draft.insurer().value(),
                draft.group().value(), draft.productName(), draft.tempProduct(), draft.quoteDocNoOrNull(), draft.recommended(),
                draft.requestedByCustomer(), grade,
                rec == null ? null : new DisclosureDetail.Recommendation(rec.codes().stream().map(c -> c.value()).toList(), rec.textOrNull()));
    }

    private static String text(Optional<Instant> at) {
        return at.map(Instant::toString).orElse(null);
    }
}
