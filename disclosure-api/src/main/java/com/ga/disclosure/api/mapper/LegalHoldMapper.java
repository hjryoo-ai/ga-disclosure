package com.ga.disclosure.api.mapper;

import com.ga.disclosure.api.dto.LegalHoldPage;
import com.ga.disclosure.api.dto.LegalHoldReceipt;
import com.ga.disclosure.api.dto.LegalHoldRequest;
import com.ga.disclosure.api.dto.LegalHoldView;
import com.ga.disclosure.api.error.MalformedRequestException;
import com.ga.disclosure.api.error.NotFoundException;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.page.Page;
import com.ga.disclosure.workflow.retention.LegalHoldService;
import com.ga.disclosure.workflow.retention.LegalHoldStore;

import java.util.UUID;

/** 법적 보류 DTO 변환. 사유 텍스트는 응답에 싣지 않는다(개인정보 컬럼). */
public final class LegalHoldMapper {

    private LegalHoldMapper() {
    }

    public static UUID holdId(String path) {
        try {
            return UUID.fromString(path);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException();
        }
    }

    public static LegalHoldService.Target target(LegalHoldRequest r) {
        if (r == null || (r.disclosureId() == null) == (r.customerRef() == null)) {
            throw new MalformedRequestException("target");
        }
        try {
            return r.disclosureId() != null ? new LegalHoldService.Target.Disclosure(DisclosureId.of(UUID.fromString(r.disclosureId())))
                    : new LegalHoldService.Target.Customer(CustomerRef.of(r.customerRef()));
        } catch (IllegalArgumentException e) {
            throw new MalformedRequestException(r.disclosureId() != null ? "disclosureId" : "customerRef");
        }
    }

    public static String reasonCode(String code) {
        if (code == null || code.isBlank()) {
            throw new MalformedRequestException("reasonCode");
        }
        return code;
    }

    public static LegalHoldReceipt receipt(LegalHoldService.Outcome o) {
        return new LegalHoldReceipt(o.holdId().toString(), o.storage().applied(), o.storage().failed(), o.storage().unsupported());
    }

    public static LegalHoldPage page(Page<LegalHoldStore.Hold> page) {
        return new LegalHoldPage(page.items().stream().map(LegalHoldMapper::view).toList(), page.next().orElse(null));
    }

    static LegalHoldView view(LegalHoldStore.Hold h) {
        return new LegalHoldView(h.holdId().toString(), h.disclosureOrNull() == null ? null : h.disclosureOrNull().value().toString(),
                h.customerOrNull() == null ? null : h.customerOrNull().value(), h.reasonCode(), h.placedBy(), h.placedAt().toString(),
                h.releasedByOrNull(), h.releasedAtOrNull() == null ? null : h.releasedAtOrNull().toString(), h.releaseReasonCodeOrNull());
    }
}
