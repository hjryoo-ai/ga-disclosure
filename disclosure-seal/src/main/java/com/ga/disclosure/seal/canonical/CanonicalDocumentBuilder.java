package com.ga.disclosure.seal.canonical;

import com.ga.disclosure.domain.disclosure.ItemGrade;
import com.ga.disclosure.domain.grade.GradeSnapshot;
import com.ga.disclosure.domain.pii.CustomerName;
import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.platform.canonical.Canonicalizer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * 봉인 본문 빌더(3A 수용심사 §3-3, 3B 계획 §2). 구성은 {@code contracts/seal/v1/canonical.schema.json}이 정본이고, 산출물은 그 스키마로 검증한 뒤
 * JCS로 직렬화한다({@link CanonicalDocument}). 번호·상태·시각·해시·체인·메타·출처(origin)는 넣지 않는다. 시계·난수·환경을 읽지 않는다 —
 * 같은 입력이면 어느 JVM에서든 같은 바이트다.
 *
 * <p>고객 성명은 봉인 유스케이스가 복호화한 봉투로 받아 여기서만 문자열로 꺼낸다(아키텍처 허용 목록 {@code seal.canonical}). 성명은 봉인 본문의
 * 일부이고 PDF에 인쇄되는 유일한 개인정보다.
 */
public final class CanonicalDocumentBuilder {

    public static final int CANONICAL_VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CanonicalDocumentBuilder() {
    }

    public static CanonicalDocument build(CanonicalInput in, Sensitive<CustomerName> customerName) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(customerName, "customerName");
        ObjectNode doc = JSON.createObjectNode();
        doc.put("canonicalVersion", CANONICAL_VERSION);
        doc.put("tenantId", in.tenantId().value());
        doc.put("disclosureId", in.disclosureId().value().toString());
        doc.put("version", in.version());
        if (in.supersedesIdOrNull() == null) {
            doc.putNull("supersedesId");
        } else {
            doc.put("supersedesId", in.supersedesIdOrNull().value().toString());
        }
        doc.put("agentId", in.agentId());
        doc.put("customerRef", in.customerRef().value());
        doc.put("customerName", customerName.reveal(CustomerName::canonical));
        doc.put("consultDate", in.consultDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
        doc.putObject("productGroup").put("code", in.groupCode().value()).put("name", in.groupName());
        doc.put("issuerMode", in.issuerMode().name());
        ObjectNode pinned = doc.putObject("pinned").put("ruleVersionId", in.ruleVersionId().value());
        if (in.tenantRuleVersionIdOrNull() == null) {
            pinned.putNull("tenantRuleVersionId");
        } else {
            pinned.put("tenantRuleVersionId", in.tenantRuleVersionIdOrNull().value());
        }
        pinned.put("templateId", in.template().templateId()).put("templateVersion", in.template().version());
        GradeSnapshot s = in.snapshot().snapshot();
        ObjectNode snapshot = doc.putObject("snapshot")
                .put("snapshotId", s.snapshotId().value())
                .put("gradingPolicyVersionId", s.gradingPolicyVersionId())
                .put("rankingPolicyVersionId", s.rankingPolicyVersionId())
                .put("tieBreak", s.tieBreak().name());
        snapshot.set("basis", Canonicalizer.parseStrict(in.snapshot().basisCanonicalJson()));
        snapshot.put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(in.snapshot().generatedAt()));
        ArrayNode panel = doc.putArray("panel");
        in.panel().forEach(p -> panel.addObject().put("insurerCode", p.code().value()).put("insurerName", p.name()));
        ArrayNode items = doc.putArray("items");
        int expected = 1;
        for (CanonicalInput.Item i : in.items()) {
            if (i.itemNo() != expected++) {
                throw new IllegalArgumentException("items must be numbered 1..n in order");
            }
            items.add(item(i));
        }
        return CanonicalDocument.of(doc);
    }

    private static ObjectNode item(CanonicalInput.Item i) {
        ObjectNode n = JSON.createObjectNode().put("itemNo", i.itemNo());
        if (i.productKeyOrNull() == null) {
            n.putNull("productKey");
        } else {
            n.put("productKey", i.productKeyOrNull().value());
        }
        n.put("insurerCode", i.insurer().value()).put("productName", i.productName()).put("groupCode", i.group().value())
                .put("tempProduct", i.tempProduct());
        if (i.quoteDocNoOrNull() == null) {
            n.putNull("quoteDocNo");
        } else {
            n.put("quoteDocNo", i.quoteDocNoOrNull());
        }
        n.put("recommended", i.recommended()).put("requestedByCustomer", i.requestedByCustomer());
        ObjectNode values = n.putObject("fieldValues");
        // 항목값은 {코드: 값}만(출처는 증거 패키지에만, 수용심사 §3-3). JCS가 키를 정렬하지만 트리도 코드 순으로 만든다.
        FieldCodeOrder.byCode(i.fieldValues()).forEach((code, value) -> values.set(code, Canonicalizer.parseStrict("[" + value.canonicalJson() + "]").get(0)));
        n.set("grade", grade(i.grade()));
        if (i.reasons().isEmpty()) {
            n.putNull("recommendation");
        } else {
            ObjectNode r = n.putObject("recommendation");
            ArrayNode reasons = r.putArray("reasons");
            i.reasons().forEach(x -> reasons.addObject().put("code", x.code()).put("label", x.label()));
            if (i.reasonTextOrNull() == null) {
                r.putNull("text");
            } else {
                r.put("text", i.reasonTextOrNull());
            }
        }
        return n;
    }

    private static ObjectNode grade(ItemGrade g) {
        ObjectNode n = JSON.createObjectNode();
        switch (g) {
            case ItemGrade.Ok ok -> n.put("status", "OK").put("source", "ENGINE").put("grade", ok.gradeCode()).put("gradeLabel", ok.gradeLabel())
                    .put("gradeOrdinal", ok.gradeOrdinal()).put("rankInSet", ok.rankInSet()).put("ratioToAvg", ok.ratioToAvg().value())
                    .put("tie", ok.tie());
            case ItemGrade.Unavailable u -> n.put("status", "UNAVAILABLE").put("source", u.source().name()).put("reason", u.reason());
        }
        return n;
    }
}
