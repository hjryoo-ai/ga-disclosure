package com.ga.disclosure.api.mapper;

import com.ga.disclosure.api.dto.GateDecisionView;
import com.ga.disclosure.api.dto.GateRequest;
import com.ga.disclosure.api.error.MalformedRequestException;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.workflow.gate.GateDecision;
import com.ga.disclosure.workflow.gate.GateQuery;

import java.util.regex.Pattern;

/** 게이트 DTO 변환. 형식 오류는 400(필드 이름만 — 값 없음): 번호는 정확히 하나, 고객 가명은 {@code CR-} + 32자리 16진. */
public final class GateMapper {

    private static final Pattern CUSTOMER_REF = Pattern.compile("CR-[0-9a-f]{32}");

    private GateMapper() {
    }

    public static GateQuery query(GateRequest r) {
        if (r == null || (r.applicationNo() == null) == (r.policyNo() == null)) {
            throw new MalformedRequestException("identifier");
        }
        if (r.customerRef() == null || !CUSTOMER_REF.matcher(r.customerRef()).matches()) {
            throw new MalformedRequestException("customerRef");
        }
        GateQuery.Kind kind = r.applicationNo() != null ? GateQuery.Kind.APPLICATION_NO : GateQuery.Kind.POLICY_NO;
        String number = r.applicationNo() != null ? r.applicationNo() : r.policyNo();
        if (!GateQuery.NUMBER.matcher(number).matches()) {
            throw new MalformedRequestException(kind == GateQuery.Kind.APPLICATION_NO ? "applicationNo" : "policyNo");
        }
        return new GateQuery(kind, number, CustomerRef.of(r.customerRef()));
    }

    public static GateDecisionView view(GateDecision d) {
        return new GateDecisionView(d.decision().name(), d.reason().name(), d.disclosureNo().orElse(null),
                d.pendingRoles().stream().map(Enum::name).toList(), d.ruleVersionId().map(RuleVersionId::value).orElse(null));
    }
}
