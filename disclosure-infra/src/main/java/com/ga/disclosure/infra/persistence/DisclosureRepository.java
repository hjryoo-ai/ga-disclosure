package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.disclosure.FieldValue;
import com.ga.disclosure.domain.disclosure.GradeSource;
import com.ga.disclosure.domain.disclosure.ItemDraft;
import com.ga.disclosure.domain.disclosure.ItemGrade;
import com.ga.disclosure.domain.disclosure.Recommendation;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.enums.IssuerMode;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.grade.EngineSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshotItem;
import com.ga.disclosure.domain.grade.RatioLabel;
import com.ga.disclosure.domain.vo.ChainHash;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.DisclosureNo;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.domain.vo.Sha256;
import com.ga.disclosure.domain.vo.SnapshotId;
import com.ga.disclosure.domain.vo.TemplateRef;
import com.ga.disclosure.rules.validation.ValidationSubject;
import com.ga.disclosure.workflow.disclosure.CanonicalValue;
import com.ga.disclosure.workflow.disclosure.Disclosure;
import com.ga.disclosure.workflow.disclosure.DisclosureItem;
import com.ga.disclosure.workflow.disclosure.DisclosureLookup;
import com.ga.disclosure.workflow.disclosure.DisclosureRecord;
import com.ga.disclosure.workflow.disclosure.DisclosureStore;
import com.ga.disclosure.workflow.disclosure.LifecycleReason;
import com.ga.disclosure.workflow.disclosure.Lineage;
import com.ga.disclosure.workflow.disclosure.SealStamp;
import com.ga.disclosure.workflow.disclosure.VoidMark;
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
public class DisclosureRepository extends TenantScopedRepository implements DisclosureStore, DisclosureLookup {

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
        params.put("version", d.lineage().version());
        params.put("supersedesId", d.lineage().supersedesIdOrNull() == null ? null : d.lineage().supersedesIdOrNull().value());
        update("""
                INSERT INTO disclosure (tenant_id, disclosure_id, agent_id, customer_ref, group_code, template_id, template_version,
                                        rule_version_id, tenant_rule_version_id, issuer_mode, status, consult_date, version, supersedes_id)
                VALUES (:tenantId, :id, :agentId, :customerRef, :groupCode, :templateId, :templateVersion,
                        :ruleVersionId, :tenantRuleVersionId, :issuerMode, :status, :consultDate, :version, :supersedesId)
                """, params);
        writeChildren(d);
    }

    /** 감사 로그(append-only)에서 고정 때 기록한 유효 룰 본문 해시를 읽는다 — 마지막 생성·재기준 행(4 계획 승인 Q1). */
    @Override
    public Optional<String> pinnedRuleBodyHash(DisclosureId id) {
        return queryAtMostOne("""
                SELECT detail ->> 'ruleBodyHash' AS hash
                  FROM audit_log
                 WHERE tenant_id = :tenantId
                   AND target_kind = 'DISCLOSURE'
                   AND target_id = :targetId
                   AND action IN ('DISCLOSURE_CREATE', 'DISCLOSURE_REBASE')
                 ORDER BY seq DESC
                 LIMIT 1
                """, Map.of("targetId", id.toString()), (rs, n) -> rs.getString("hash"));
    }

    @Override
    public List<DisclosureId> awaitingSignatures(int limit) {
        return query("""
                SELECT disclosure_id
                  FROM disclosure
                 WHERE tenant_id = :tenantId
                   AND status IN ('SEALED', 'PARTIALLY_SIGNED')
                 ORDER BY sealed_at, disclosure_id
                 LIMIT :limit
                """, Map.of("limit", limit), (rs, n) -> DisclosureId.of(rs.getObject("disclosure_id", UUID.class)));
    }

    @Override
    public Optional<DisclosureRecord> loadForUpdate(DisclosureId id) {
        Optional<Header> header = queryAtMostOne("""
                SELECT disclosure_id, agent_id, customer_ref, group_code, template_id, template_version, rule_version_id,
                       tenant_rule_version_id, issuer_mode, status, consult_date, grade_snapshot_id, grading_policy_version_id,
                       ranking_policy_version_id, tie_break, grade_basis::text AS grade_basis, snapshot_generated_at, version, supersedes_id,
                       superseded_by_id, disclosure_no, sealed_at, canonical_hash, pdf_hash, chain_hash, chain_seq, retention_until,
                       voided_at, void_reason_code, void_reason_text, supersede_reason_code, supersede_reason_text, completed_at
                  FROM disclosure
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :id
                   FOR UPDATE
                """, Map.of("id", id.value()), (rs, n) -> header(rs));
        return header.map(this::withChildren);
    }

    @Override
    public List<DisclosureId> findFor(CustomerRef customer, java.time.LocalDate consultDate, GroupCode group) {
        return query("""
                SELECT disclosure_id
                  FROM disclosure
                 WHERE tenant_id = :tenantId
                   AND customer_ref = :customerRef
                   AND consult_date = :consultDate
                   AND group_code = :groupCode
                 ORDER BY disclosure_id
                """, Map.of("customerRef", customer.value(), "consultDate", consultDate, "groupCode", group.value()),
                (rs, n) -> DisclosureId.of(rs.getObject("disclosure_id", UUID.class)));
    }

    @Override
    public List<Summary> summariesFor(CustomerRef customer, java.time.LocalDate consultDate, GroupCode group) {
        return query("""
                SELECT disclosure_id, status, version, supersedes_id
                  FROM disclosure
                 WHERE tenant_id = :tenantId
                   AND customer_ref = :customerRef
                   AND consult_date = :consultDate
                   AND group_code = :groupCode
                 ORDER BY version, disclosure_id
                """, Map.of("customerRef", customer.value(), "consultDate", consultDate, "groupCode", group.value()), (rs, n) -> {
            UUID supersedes = rs.getObject("supersedes_id", UUID.class);
            return new Summary(DisclosureId.of(rs.getObject("disclosure_id", UUID.class)), DisclosureStatus.valueOf(rs.getString("status")),
                    rs.getInt("version"), supersedes == null ? null : DisclosureId.of(supersedes));
        });
    }

    /**
     * 애그리게이트 상태를 저장한다. 상태 컬럼을 바꾸는 유일한 경로다.
     * <ul>
     *   <li>가변 상태로: 헤더의 상태·고정 룰·서식(재기준)·스냅샷 컬럼, 그리고 항목·추천사유 전부를 지우고 다시 쓴다.</li>
     *   <li>봉인 이후 상태로(봉인·무효·정정): 상태·봉인 컬럼·무효·후속 ID만 쓰고 항목은 건드리지 않는다 — 봉인되는 순간 항목은 불변이다(V3 GD010).
     *       이미 봉인된 행의 봉인 컬럼은 같은 값을 다시 쓴다(V3 본문 비교에서 변화 없음).</li>
     * </ul>
     */
    @Override
    public void save(Disclosure d) {
        if (d.status().isSealedOrLater()) {
            Map<String, Object> p = closedParams(d);
            int closed = update("""
                    UPDATE disclosure
                       SET status = :status,
                           disclosure_no = :no,
                           sealed_at = :sealedAt,
                           canonical_hash = :canonicalHash,
                           pdf_hash = :pdfHash,
                           chain_hash = :chainHash,
                           chain_seq = :chainSeq,
                           retention_until = :retentionUntil,
                           completed_at = :completedAt,
                           voided_at = :voidedAt,
                           void_reason_code = :voidReasonCode,
                           void_reason_text = :voidReasonText,
                           superseded_by_id = :supersededBy,
                           supersede_reason_code = :supersedeReasonCode,
                           supersede_reason_text = :supersedeReasonText
                     WHERE tenant_id = :tenantId
                       AND disclosure_id = :id
                    """, p);
            if (closed != 1) {
                throw new IllegalStateException("disclosure " + d.id() + " was not saved (" + closed + " rows)");
            }
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("id", d.id().value());
        params.put("status", d.status().name());
        params.put("ruleVersionId", d.ruleVersionId().value());
        params.put("tenantRuleVersionId", d.tenantRuleVersionId().map(RuleVersionId::value).orElse(null));
        params.put("templateId", d.template().templateId());
        params.put("templateVersion", d.template().version());
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
                       rule_version_id = :ruleVersionId,
                       tenant_rule_version_id = :tenantRuleVersionId,
                       template_id = :templateId,
                       template_version = :templateVersion,
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

    /** 봉인 이후 상태 저장의 파라미터: 상태·봉인 컬럼 7개·무효·후속 ID(SQL은 {@link #save}에만 — DisclosureWriteScanTest). */
    private static Map<String, Object> closedParams(Disclosure d) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", d.id().value());
        p.put("status", d.status().name());
        Optional<SealStamp> seal = d.sealStamp();
        p.put("no", seal.map(x -> x.number().value()).orElse(null));
        p.put("sealedAt", seal.map(x -> Timestamp.from(x.sealedAt())).orElse(null));
        p.put("canonicalHash", seal.map(x -> x.canonicalHash().hex()).orElse(null));
        p.put("pdfHash", seal.map(x -> x.pdfHash().hex()).orElse(null));
        p.put("chainHash", seal.map(x -> x.chainHash().toString()).orElse(null));
        p.put("chainSeq", seal.map(SealStamp::chainSeq).orElse(null));
        p.put("retentionUntil", seal.map(SealStamp::retentionUntil).orElse(null));
        p.put("completedAt", d.completedAt().map(Timestamp::from).orElse(null));
        p.put("voidedAt", d.voidMark().map(v -> Timestamp.from(v.at())).orElse(null));
        p.put("voidReasonCode", d.voidMark().map(v -> v.reason().code()).orElse(null));
        p.put("voidReasonText", d.voidMark().map(v -> v.reason().textOrNull()).orElse(null));
        p.put("supersededBy", d.supersededBy().map(DisclosureId::value).orElse(null));
        p.put("supersedeReasonCode", d.supersedeReason().map(LifecycleReason::code).orElse(null));
        p.put("supersedeReasonText", d.supersedeReason().map(LifecycleReason::textOrNull).orElse(null));
        return p;
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
                          String basis, java.time.Instant generatedAt, Lineage lineage,
                          SealStamp sealOrNull, VoidMark voidOrNull,
                          DisclosureId supersededByOrNull, LifecycleReason supersedeReasonOrNull, java.time.Instant completedAtOrNull) {
    }

    private static Header header(ResultSet rs) throws SQLException {
        String tenantRule = rs.getString("tenant_rule_version_id");
        Timestamp generated = rs.getTimestamp("snapshot_generated_at");
        UUID supersedes = rs.getObject("supersedes_id", UUID.class);
        UUID supersededBy = rs.getObject("superseded_by_id", UUID.class);
        String no = rs.getString("disclosure_no");
        SealStamp seal = no == null ? null : new SealStamp(
                DisclosureNo.parse(no), rs.getTimestamp("sealed_at").toInstant(),
                Sha256.of(rs.getString("canonical_hash")), Sha256.of(rs.getString("pdf_hash")),
                ChainHash.of(rs.getString("chain_hash")), rs.getLong("chain_seq"),
                rs.getObject("retention_until", java.time.LocalDate.class));
        Timestamp voidedAt = rs.getTimestamp("voided_at");
        VoidMark voidMark = voidedAt == null ? null
                : new VoidMark(voidedAt.toInstant(), new LifecycleReason(rs.getString("void_reason_code"), rs.getString("void_reason_text")));
        String supersedeCode = rs.getString("supersede_reason_code");
        LifecycleReason supersedeReason = supersedeCode == null ? null : new LifecycleReason(supersedeCode, rs.getString("supersede_reason_text"));
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new Header(DisclosureId.of(rs.getObject("disclosure_id", UUID.class)), rs.getString("agent_id"),
                CustomerRef.of(rs.getString("customer_ref")), GroupCode.of(rs.getString("group_code")),
                TemplateRef.of(rs.getString("template_id"), rs.getInt("template_version")), RuleVersionId.of(rs.getString("rule_version_id")),
                tenantRule == null ? null : RuleVersionId.of(tenantRule), IssuerMode.valueOf(rs.getString("issuer_mode")),
                DisclosureStatus.valueOf(rs.getString("status")), rs.getObject("consult_date", java.time.LocalDate.class),
                rs.getString("grade_snapshot_id"), rs.getString("grading_policy_version_id"), rs.getString("ranking_policy_version_id"),
                rs.getString("tie_break"), rs.getString("grade_basis"), generated == null ? null : generated.toInstant(),
                new Lineage(rs.getInt("version"), supersedes == null ? null : DisclosureId.of(supersedes)),
                seal, voidMark, supersededBy == null ? null : DisclosureId.of(supersededBy), supersedeReason,
                completedAt == null ? null : completedAt.toInstant());
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
        // 서명 목록(역할·시각): 서명 레코드 본체는 서명 저장소가 쓰고 읽는다 — 애그리게이트는 완료 조건(R-SIGNER-SET)에 필요한 것만
        List<ValidationSubject.SignatureMark> signatures = query("""
                SELECT signer_role, signed_at
                  FROM signature
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :id
                 ORDER BY signed_at, signature_id
                """, Map.of("id", h.id().value()), (rs, n) -> new ValidationSubject.SignatureMark(SignerRole.valueOf(rs.getString("signer_role")),
                rs.getTimestamp("signed_at").toInstant()));
        return new DisclosureRecord(h.id(), h.agentId(), h.customerRef(), h.group(), h.consultDate(), h.rule(), h.tenantRuleOrNull(),
                h.template(), h.issuerMode(), h.lineage(), h.status(), items, snapshot, h.sealOrNull(), h.voidOrNull(), h.supersededByOrNull(),
                h.supersedeReasonOrNull(), signatures, h.completedAtOrNull());
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
