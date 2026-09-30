package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.disclosure.FieldValue;
import com.ga.disclosure.domain.disclosure.GradeSource;
import com.ga.disclosure.domain.disclosure.ItemDraft;
import com.ga.disclosure.domain.disclosure.ItemGrade;
import com.ga.disclosure.domain.disclosure.Recommendation;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.IssuerMode;
import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.grade.EngineSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshotItem;
import com.ga.disclosure.domain.grade.RatioLabel;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.domain.vo.SnapshotId;
import com.ga.disclosure.domain.vo.TemplateRef;
import com.ga.disclosure.workflow.disclosure.CanonicalValue;
import com.ga.disclosure.workflow.disclosure.Disclosure;
import com.ga.disclosure.workflow.disclosure.DisclosureItem;
import com.ga.disclosure.workflow.disclosure.DisclosureRecord;
import com.ga.disclosure.workflow.disclosure.DisclosureStore;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link DisclosureStore} 어댑터: {@code disclosure}·{@code disclosure_item}·{@code recommendation}. 상태를 바꾸는 SQL은 {@link #save}에만,
 * 행을 만드는 SQL은 {@link #insert}·{@link #save}에만 있다 — 아키텍처 스캔({@code DisclosureWriteScanTest})이 강제한다.
 *
 * <p>항목값({@code field_values})은 {@code {code: {value, origin}}}, 엔진 {@code ratio_to_avg}는 원문 그대로 저장한다. 스냅샷은 헤더 6개 컬럼과
 * 엔진 출처 항목 등급으로 복원한다(항목 순서). 봉인 이후 행의 변경은 V3 트리거가 거부한다.
 */
@Repository
public class DisclosureRepository extends TenantScopedRepository implements DisclosureStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public DisclosureRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public void insert(Disclosure d) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", d.id().value());
        params.put("agentId", d.agentId());
        params.put("customerRef", d.customerRef().value());
        params.put("groupCode", d.groupCode().value());
        params.put("templateId", d.template().templateId());
        params.put("templateVersion", d.template().version());
        params.put("ruleVersionId", d.ruleVersionId().value());
        params.put("tenantRuleVersionId", d.tenantRuleVersionId().map(RuleVersionId::value).orElse(null));
        params.put("issuerMode", d.issuerMode().name());
        params.put("status", d.status().name());
        params.put("consultDate", d.consultDate());
        update("""
                INSERT INTO disclosure (tenant_id, disclosure_id, agent_id, customer_ref, group_code, template_id, template_version,
                                        rule_version_id, tenant_rule_version_id, issuer_mode, status, consult_date)
                VALUES (:tenantId, :id, :agentId, :customerRef, :groupCode, :templateId, :templateVersion,
                        :ruleVersionId, :tenantRuleVersionId, :issuerMode, :status, :consultDate)
                """, params);
        writeChildren(d);
    }

    @Override
    public Optional<DisclosureRecord> loadForUpdate(DisclosureId id) {
        Optional<Header> header = queryAtMostOne("""
                SELECT disclosure_id, agent_id, customer_ref, group_code, template_id, template_version, rule_version_id,
                       tenant_rule_version_id, issuer_mode, status, consult_date, grade_snapshot_id, grading_policy_version_id,
                       ranking_policy_version_id, tie_break, grade_basis::text AS grade_basis, snapshot_generated_at
                  FROM disclosure
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :id
                   FOR UPDATE
                """, Map.of("id", id.value()), (rs, n) -> header(rs));
        return header.map(this::withChildren);
    }

    /**
     * 애그리게이트 상태를 저장한다: 헤더의 상태·스냅샷 컬럼, 그리고 항목·추천사유 전부를 지우고 다시 쓴다(가변 상태에서만 — V3 트리거).
     * 상태 컬럼을 바꾸는 유일한 경로다.
     */
    @Override
    public void save(Disclosure d) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", d.id().value());
        params.put("status", d.status().name());
        Optional<EngineSnapshot> s = d.engineSnapshot();
        params.put("snapshotId", s.map(x -> x.snapshot().snapshotId().value()).orElse(null));
        params.put("grading", s.map(x -> x.snapshot().gradingPolicyVersionId()).orElse(null));
        params.put("ranking", s.map(x -> x.snapshot().rankingPolicyVersionId()).orElse(null));
        params.put("tieBreak", s.map(x -> x.snapshot().tieBreak().name()).orElse(null));
        params.put("basis", s.map(EngineSnapshot::basisCanonicalJson).orElse(null));
        params.put("generatedAt", s.map(x -> Timestamp.from(x.generatedAt())).orElse(null));
        int updated = update("""
                UPDATE disclosure
                   SET status = :status,
                       grade_snapshot_id = :snapshotId,
                       grading_policy_version_id = :grading,
                       ranking_policy_version_id = :ranking,
                       tie_break = :tieBreak,
                       grade_basis = CAST(:basis AS jsonb),
                       snapshot_generated_at = :generatedAt
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :id
                """, params);
        if (updated != 1) {
            throw new IllegalStateException("disclosure " + d.id() + " was not saved (" + updated + " rows)");
        }
        update("""
                DELETE FROM recommendation
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :id
                """, Map.of("id", d.id().value()));
        update("""
                DELETE FROM disclosure_item
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :id
                """, Map.of("id", d.id().value()));
        writeChildren(d);
    }

    private void writeChildren(Disclosure d) {
        for (DisclosureItem item : d.disclosureItems()) {
            ItemDraft draft = item.draft();
            Map<String, Object> p = new HashMap<>();
            p.put("id", d.id().value());
            p.put("itemNo", (short) item.itemNo());
            p.put("productKey", draft.productKey().map(ProductKey::value).orElse(null));
            p.put("insurer", draft.insurer().value());
            p.put("group", draft.group().value());
            p.put("productName", draft.productName());
            p.put("temp", draft.tempProduct());
            p.put("quote", draft.quoteDocNo().orElse(null));
            p.put("recommended", draft.recommended());
            p.put("requested", draft.requestedByCustomer());
            p.put("fieldValues", fieldValuesJson(draft.fieldValues()));
            gradeParams(item.grade(), p);
            update("""
                    INSERT INTO disclosure_item (tenant_id, disclosure_id, item_no, product_key, insurer_code, group_code, product_name, temp_product,
                                                 quote_doc_no, is_recommended, requested_by_customer, field_values, grade, grade_label,
                                                 grade_ordinal, rank_in_set, ratio_to_avg, grade_status, tie, unavailable_reason, grade_source)
                    VALUES (:tenantId, :id, :itemNo, :productKey, :insurer, :group, :productName, :temp, :quote, :recommended, :requested,
                            CAST(:fieldValues AS jsonb), :grade, :gradeLabel, :gradeOrdinal, :rankInSet, :ratioToAvg, :gradeStatus, :tie,
                            :unavailableReason, :gradeSource)
                    """, p);
            if (item.recommendation().isPresent()) {
                Recommendation r = item.recommendation().get();
                Map<String, Object> rp = new HashMap<>();
                rp.put("id", d.id().value());
                rp.put("itemNo", (short) item.itemNo());
                rp.put("codes", r.codes().stream().map(ReasonCode::value).toArray(String[]::new));
                rp.put("text", r.text().orElse(null));
                update("""
                        INSERT INTO recommendation (tenant_id, disclosure_id, item_no, reason_codes, reason_text)
                        VALUES (:tenantId, :id, :itemNo, :codes, :text)
                        """, rp);
            }
        }
    }

    private static void gradeParams(Optional<ItemGrade> grade, Map<String, Object> p) {
        p.put("grade", null);
        p.put("gradeLabel", null);
        p.put("gradeOrdinal", null);
        p.put("rankInSet", null);
        p.put("ratioToAvg", null);
        p.put("gradeStatus", null);
        p.put("tie", null);
        p.put("unavailableReason", null);
        p.put("gradeSource", null);
        if (grade.isEmpty()) {
            return;
        }
        p.put("gradeSource", grade.get().source().name());
        switch (grade.get()) {
            case ItemGrade.Ok ok -> {
                p.put("grade", ok.gradeCode());
                p.put("gradeLabel", ok.gradeLabel());
                p.put("gradeOrdinal", (short) ok.gradeOrdinal());
                p.put("rankInSet", (short) ok.rankInSet());
                p.put("ratioToAvg", ok.ratioToAvg().value());
                p.put("gradeStatus", "OK");
                p.put("tie", ok.tie());
            }
            case ItemGrade.Unavailable u -> {
                p.put("gradeStatus", "UNAVAILABLE");
                p.put("unavailableReason", u.reason());
            }
        }
    }

    private static String fieldValuesJson(Map<String, FieldValue> values) {
        ObjectNode root = JSON.createObjectNode();
        values.forEach((code, v) -> root.putObject(code).put("origin", v.origin().name())
                .set("value", Canonicalizer.parseStrict(v.canonicalJson())));
        return JSON.writeValueAsString(root);
    }

    // ------------------------------------------------------------------ 읽기

    private record Header(DisclosureId id, String agentId, CustomerRef customerRef, GroupCode group, TemplateRef template,
                          RuleVersionId rule, RuleVersionId tenantRuleOrNull, IssuerMode issuerMode, DisclosureStatus status,
                          java.time.LocalDate consultDate, String snapshotIdOrNull, String grading, String ranking, String tieBreak,
                          String basis, java.time.Instant generatedAt) {
    }

    private static Header header(ResultSet rs) throws SQLException {
        String tenantRule = rs.getString("tenant_rule_version_id");
        Timestamp generated = rs.getTimestamp("snapshot_generated_at");
        return new Header(DisclosureId.of(rs.getObject("disclosure_id", UUID.class)), rs.getString("agent_id"),
                CustomerRef.of(rs.getString("customer_ref")), GroupCode.of(rs.getString("group_code")),
                TemplateRef.of(rs.getString("template_id"), rs.getInt("template_version")), RuleVersionId.of(rs.getString("rule_version_id")),
                tenantRule == null ? null : RuleVersionId.of(tenantRule), IssuerMode.valueOf(rs.getString("issuer_mode")),
                DisclosureStatus.valueOf(rs.getString("status")), rs.getObject("consult_date", java.time.LocalDate.class),
                rs.getString("grade_snapshot_id"), rs.getString("grading_policy_version_id"), rs.getString("ranking_policy_version_id"),
                rs.getString("tie_break"), rs.getString("grade_basis"), generated == null ? null : generated.toInstant());
    }

    private record Row(DisclosureItem item, GradeSnapshotItem engineOrNull) {
    }

    private DisclosureRecord withChildren(Header h) {
        Map<Integer, Recommendation> recs = new HashMap<>();
        query("""
                SELECT item_no, reason_codes, reason_text
                  FROM recommendation
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :id
                """, Map.of("id", h.id().value()), (rs, n) -> {
            Array codes = rs.getArray("reason_codes");
            recs.put(rs.getInt("item_no"), new Recommendation(Arrays.stream((String[]) codes.getArray()).map(ReasonCode::of).toList(),
                    rs.getString("reason_text")));
            return Boolean.TRUE;
        });
        List<Row> rows = query("""
                SELECT item_no, product_key, insurer_code, group_code, product_name, temp_product, quote_doc_no, is_recommended,
                       requested_by_customer, field_values::text AS field_values, grade, grade_label, grade_ordinal, rank_in_set,
                       ratio_to_avg, grade_status, tie, unavailable_reason, grade_source
                  FROM disclosure_item
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :id
                 ORDER BY item_no
                """, Map.of("id", h.id().value()), (rs, n) -> row(rs, recs));
        List<DisclosureItem> items = new ArrayList<>();
        List<GradeSnapshotItem> engineItems = new ArrayList<>();
        for (Row r : rows) {
            items.add(r.item());
            if (r.engineOrNull() != null) {
                engineItems.add(r.engineOrNull());
            }
        }
        EngineSnapshot snapshot = h.snapshotIdOrNull() == null ? null : new EngineSnapshot(
                new GradeSnapshot(SnapshotId.of(h.snapshotIdOrNull()), h.grading(), h.ranking(), TieBreak.valueOf(h.tieBreak()), engineItems),
                new String(Canonicalizer.canonicalize(Canonicalizer.parseStrict(h.basis())), StandardCharsets.UTF_8), h.generatedAt());
        return new DisclosureRecord(h.id(), h.agentId(), h.customerRef(), h.group(), h.consultDate(), h.rule(), h.tenantRuleOrNull(),
                h.template(), h.issuerMode(), h.status(), items, snapshot);
    }

    private static Row row(ResultSet rs, Map<Integer, Recommendation> recs) throws SQLException {
        int itemNo = rs.getInt("item_no");
        String key = rs.getString("product_key");
        InsurerCode insurer = InsurerCode.of(rs.getString("insurer_code"));
        Map<String, FieldValue> values = new LinkedHashMap<>();
        JsonNode fv = JSON.readTree(rs.getString("field_values"));
        for (String code : fv.propertyNames()) {
            JsonNode entry = fv.get(code);
            values.put(code, new FieldValue(CanonicalValue.of(entry.get("value")), FieldValue.Origin.valueOf(entry.get("origin").asString())));
        }
        boolean temp = rs.getBoolean("temp_product");
        ItemDraft draft = new ItemDraft(key == null ? null : ProductKey.parse(key), insurer, GroupCode.of(rs.getString("group_code")),
                rs.getString("product_name"),
                temp, rs.getString("quote_doc_no"), rs.getBoolean("is_recommended"), rs.getBoolean("requested_by_customer"), values);
        String status = rs.getString("grade_status");
        ItemGrade grade = null;
        GradeSnapshotItem engine = null;
        if ("OK".equals(status)) {
            RatioLabel ratio = new RatioLabel(rs.getString("ratio_to_avg"));
            grade = new ItemGrade.Ok(rs.getString("grade"), rs.getString("grade_label"), rs.getInt("grade_ordinal"), rs.getInt("rank_in_set"),
                    rs.getBoolean("tie"), ratio);
            engine = GradeSnapshotItem.ok(ProductKey.parse(key), rs.getString("grade"), rs.getString("grade_label"), rs.getInt("grade_ordinal"),
                    rs.getInt("rank_in_set"), rs.getBoolean("tie"), ratio);
        } else if ("UNAVAILABLE".equals(status)) {
            GradeSource source = GradeSource.valueOf(rs.getString("grade_source"));
            grade = new ItemGrade.Unavailable(rs.getString("unavailable_reason"), source);
            if (source == GradeSource.ENGINE) {
                engine = GradeSnapshotItem.unavailable(ProductKey.parse(key), rs.getString("unavailable_reason"));
            }
        }
        return new Row(new DisclosureItem(itemNo, draft, grade, recs.get(itemNo)), engine);
    }
}
