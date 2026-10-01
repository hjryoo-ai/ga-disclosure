package com.ga.disclosure.seal.canonical;

import com.ga.disclosure.domain.disclosure.GradeSource;
import com.ga.disclosure.domain.disclosure.ItemGrade;
import com.ga.disclosure.domain.grade.RatioLabel;
import com.ga.disclosure.rules.template.BindingView;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 봉인 본문 위의 결속 단면(렌더 시점). 렌더러의 입력은 봉인 본문 + 서식 + 확인서 번호뿐이다 — 이 뷰는 그 셋만으로 결속 값을 낸다(3B 계획 §2,
 * 승인 Q11). 성명은 본문에 이미 있고(봉인 유스케이스가 복호화해 실었다) 복호화 경로는 없다(승인 B3).
 *
 * <p>본문은 항목값의 출처를 싣지 않으므로(수용심사 §3-3) {@link Item#enteredByAgent}는 "값이 있음"이다 — 봉인 본문의 값은 봉인 검증(출처 포함)을
 * 이미 통과한 값이다.
 */
public final class CanonicalView implements BindingView {

    private final JsonNode doc;
    private final String disclosureNo;
    private final List<ItemView> items;

    public CanonicalView(CanonicalDocument document, String disclosureNo) {
        this.doc = Objects.requireNonNull(document, "document").json();
        this.disclosureNo = Objects.requireNonNull(disclosureNo, "disclosureNo");
        List<ItemView> views = new ArrayList<>();
        doc.path("items").forEach(i -> views.add(new ItemView(i)));
        this.items = List.copyOf(views);
    }

    @Override
    public Optional<String> disclosureNo() {
        return Optional.of(disclosureNo);
    }

    @Override
    public LocalDate consultDate() {
        return LocalDate.parse(doc.path("consultDate").asString());
    }

    @Override
    public String agentId() {
        return doc.path("agentId").asString();
    }

    @Override
    public Optional<String> customerName() {
        return Optional.of(doc.path("customerName").asString());
    }

    @Override
    public Optional<String> productGroupName() {
        return Optional.of(doc.path("productGroup").path("name").asString());
    }

    @Override
    public List<String> panelInsurerNames() {
        List<String> out = new ArrayList<>();
        doc.path("panel").forEach(p -> out.add(p.path("insurerName").asString()));
        return out;
    }

    @Override
    public List<? extends Item> items() {
        return items;
    }

    /** 각주용 canonical 해시 표기의 입력을 렌더러가 받기 위해 본문 단위 값만 노출한다. */
    public String tenantId() {
        return doc.path("tenantId").asString();
    }

    private Optional<String> panelName(String insurerCode) {
        for (JsonNode p : doc.path("panel")) {
            if (p.path("insurerCode").asString().equals(insurerCode)) {
                return Optional.of(p.path("insurerName").asString());
            }
        }
        return Optional.empty();
    }

    private final class ItemView implements Item {

        private final JsonNode item;

        ItemView(JsonNode item) {
            this.item = item;
        }

        @Override
        public Optional<String> insurerName() {
            return panelName(item.path("insurerCode").asString());
        }

        @Override
        public String productName() {
            return item.path("productName").asString();
        }

        @Override
        public Optional<JsonNode> fieldValue(String code) {
            JsonNode v = item.path("fieldValues").get(code);
            return Optional.ofNullable(v).map(JsonNode::deepCopy);
        }

        @Override
        public boolean enteredByAgent(String code) {
            return item.path("fieldValues").has(code);
        }

        @Override
        public Optional<ItemGrade> grade() {
            JsonNode g = item.path("grade");
            return Optional.of(switch (g.path("status").asString()) {
                case "OK" -> new ItemGrade.Ok(g.path("grade").asString(), g.path("gradeLabel").asString(), g.path("gradeOrdinal").asInt(),
                        g.path("rankInSet").asInt(), g.path("tie").asBoolean(), new RatioLabel(g.path("ratioToAvg").asString()));
                case "UNAVAILABLE" -> new ItemGrade.Unavailable(g.path("reason").asString(), GradeSource.valueOf(g.path("source").asString()));
                default -> throw new IllegalStateException("unknown grade status in a schema-valid document");
            });
        }

        @Override
        public boolean recommended() {
            return item.path("recommended").asBoolean();
        }

        @Override
        public List<String> reasonLabels() {
            List<String> out = new ArrayList<>();
            item.path("recommendation").path("reasons").forEach(r -> out.add(r.path("label").asString()));
            return out;
        }

        @Override
        public Optional<String> reasonText() {
            JsonNode t = item.path("recommendation").path("text");
            return t.isString() ? Optional.of(t.asString()) : Optional.empty();
        }
    }
}
