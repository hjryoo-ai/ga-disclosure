package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.workflow.contract.ContractLinkBatch;
import com.ga.disclosure.workflow.contract.ContractLinkStore;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ContractLinkStore} 어댑터(V14·V15·V16). 후보 확인서는 무효·정정됨·폐기·파기를 뺀다. 확인서 현재값·보존기한 쓰기는 메타 컬럼 UPDATE이고 정합은
 * DB가 다시 본다(GD136 현재값 = 활성 연결, GD094 보존기한 연장만).
 */
@Repository
public class ContractLinkRepository extends TenantScopedRepository implements ContractLinkStore {

    private final DisclosureRepository disclosures;

    public ContractLinkRepository(TenantJdbcGateway gateway, DisclosureRepository disclosures) {
        super(gateway);
        this.disclosures = java.util.Objects.requireNonNull(disclosures, "disclosures");
    }

    @Override
    public Optional<LedgerEntry> batch(String source, String batchId) {
        return queryAtMostOne("""
                SELECT content_sha256, received_at, completed_at IS NOT NULL AS completed
                  FROM contract_link_batch
                 WHERE tenant_id = :tenantId AND source = :source AND batch_id = :batchId
                """, Map.of("source", source, "batchId", batchId), (rs, n) -> new LedgerEntry(rs.getString("content_sha256"),
                rs.getTimestamp("received_at").toInstant(), rs.getBoolean("completed")));
    }

    @Override
    public void recordBatch(String source, String batchId, String sha256, int items, Instant receivedAt, String receivedBy) {
        update("""
                INSERT INTO contract_link_batch (tenant_id, source, batch_id, content_sha256, items, received_at, received_by)
                VALUES (:tenantId, :source, :batchId, :sha256, :items, :receivedAt, :receivedBy)
                """, Map.of("source", source, "batchId", batchId, "sha256", sha256, "items", items, "receivedAt", Timestamp.from(receivedAt),
                "receivedBy", receivedBy));
    }

    @Override
    public boolean completeBatch(String source, String batchId, String summaryJson, Instant at) {
        return update("""
                UPDATE contract_link_batch SET summary = CAST(:summary AS jsonb), completed_at = :at
                 WHERE tenant_id = :tenantId AND source = :source AND batch_id = :batchId AND summary IS NULL
                """, Map.of("source", source, "batchId", batchId, "summary", summaryJson, "at", Timestamp.from(at))) == 1;
    }

    @Override
    public Optional<UUID> linkBySource(String source, String sourceRef) {
        return queryAtMostOne("""
                SELECT link_id FROM contract_link WHERE tenant_id = :tenantId AND source = :source AND source_ref = :ref
                """, Map.of("source", source, "ref", sourceRef), (rs, n) -> rs.getObject("link_id", UUID.class));
    }

    @Override
    public Optional<String> unmatchedBySource(String source, String sourceRef) {
        return queryAtMostOne("""
                SELECT reason FROM contract_link_unmatched WHERE tenant_id = :tenantId AND source = :source AND source_ref = :ref
                """, Map.of("source", source, "ref", sourceRef), (rs, n) -> rs.getString("reason"));
    }

    @Override
    public List<Candidate> byApplicationNo(String applicationNo) {
        return query("""
                SELECT d.disclosure_id, d.status, d.disclosure_no, d.customer_ref, d.consult_date, d.rule_version_id, d.tenant_rule_version_id,
                       d.retention_until
                  FROM disclosure d
                 WHERE d.tenant_id = :tenantId
                   AND d.application_no = :applicationNo
                   AND d.status NOT IN ('VOID', 'SUPERSEDED', 'ABANDONED')
                   AND d.destroyed_at IS NULL
                 ORDER BY d.disclosure_id
                """, Map.of("applicationNo", applicationNo), (rs, n) -> candidate(rs));
    }

    @Override
    public List<Candidate> byActivePolicy(String policyNo) {
        return query("""
                SELECT d.disclosure_id, d.status, d.disclosure_no, d.customer_ref, d.consult_date, d.rule_version_id, d.tenant_rule_version_id,
                       d.retention_until
                  FROM disclosure d
                  JOIN contract_link l ON l.tenant_id = d.tenant_id AND l.disclosure_id = d.disclosure_id
                 WHERE d.tenant_id = :tenantId
                   AND l.tenant_id = :tenantId
                   AND l.policy_no = :policyNo
                   AND l.superseded_by IS NULL
                   AND l.carried_to IS NULL
                   AND d.status NOT IN ('VOID', 'SUPERSEDED', 'ABANDONED')
                   AND d.destroyed_at IS NULL
                 ORDER BY d.disclosure_id
                """, Map.of("policyNo", policyNo), (rs, n) -> candidate(rs));
    }

    @Override
    public List<Holder> activePolicyHolders(String policyNo) {
        return query("""
                SELECT l.disclosure_id, d.status, d.customer_ref, l.link_id
                  FROM contract_link l
                  JOIN disclosure d ON d.tenant_id = l.tenant_id AND d.disclosure_id = l.disclosure_id
                 WHERE l.tenant_id = :tenantId
                   AND d.tenant_id = :tenantId
                   AND l.policy_no = :policyNo
                   AND l.superseded_by IS NULL
                   AND l.carried_to IS NULL
                """, Map.of("policyNo", policyNo), (rs, n) -> new Holder(DisclosureId.of(rs.getObject("disclosure_id", UUID.class)),
                rs.getString("status"), rs.getString("customer_ref"), rs.getObject("link_id", UUID.class)));
    }

    @Override
    public void carry(UUID linkId, UUID carriedTo, Instant at) {
        int n = update("""
                UPDATE contract_link SET carried_to = :to, carried_at = :at
                 WHERE tenant_id = :tenantId AND link_id = :linkId AND superseded_by IS NULL AND carried_to IS NULL
                """, Map.of("linkId", linkId, "to", carriedTo, "at", Timestamp.from(at)));
        if (n != 1) {
            throw new IllegalStateException("contract link " + linkId + " is not active");
        }
    }

    private static Candidate candidate(ResultSet rs) throws SQLException {
        return new Candidate(DisclosureId.of(rs.getObject("disclosure_id", UUID.class)), rs.getString("status"),
                Optional.ofNullable(rs.getString("disclosure_no")), rs.getString("customer_ref"), rs.getObject("consult_date", LocalDate.class),
                RuleVersionId.of(rs.getString("rule_version_id")), Optional.ofNullable(rs.getString("tenant_rule_version_id")).map(RuleVersionId::of),
                Optional.ofNullable(rs.getObject("retention_until", LocalDate.class)));
    }

    @Override
    public Optional<ActiveLink> activeLink(DisclosureId disclosure) {
        return queryAtMostOne("""
                SELECT link_id, policy_no, application_no, contract_date, insurer_code, product_key, source, source_ref
                  FROM contract_link
                 WHERE tenant_id = :tenantId AND disclosure_id = :disclosureId AND superseded_by IS NULL AND carried_to IS NULL
                """, Map.of("disclosureId", disclosure.value()), (rs, n) -> new ActiveLink(rs.getObject("link_id", UUID.class), rs.getString("policy_no"),
                Optional.ofNullable(rs.getString("application_no")), rs.getObject("contract_date", LocalDate.class), rs.getString("insurer_code"),
                Optional.ofNullable(rs.getString("product_key")), rs.getString("source"), rs.getString("source_ref")));
    }

    @Override
    public void supersede(UUID linkId, UUID supersededBy, Instant at) {
        int n = update("""
                UPDATE contract_link SET superseded_by = :by, superseded_at = :at
                 WHERE tenant_id = :tenantId AND link_id = :linkId AND superseded_by IS NULL AND carried_to IS NULL
                """, Map.of("linkId", linkId, "by", supersededBy, "at", Timestamp.from(at)));
        if (n != 1) {
            throw new IllegalStateException("contract link " + linkId + " is not active");
        }
    }

    @Override
    public void insertLink(NewLink l) {
        Map<String, Object> p = new HashMap<>();
        p.put("linkId", l.linkId());
        p.put("disclosureId", l.disclosure().value());
        p.put("policyNo", l.policyNo());
        p.put("applicationNo", l.applicationNo().orElse(null));
        p.put("contractDate", Date.valueOf(l.contractDate()));
        p.put("insurerCode", l.insurerCode());
        p.put("productKey", l.productKey().orElse(null));
        p.put("source", l.source());
        p.put("sourceRef", l.sourceRef());
        p.put("receivedAt", Timestamp.from(l.receivedAt()));
        p.put("linkedBy", l.linkedBy());
        update("""
                INSERT INTO contract_link (tenant_id, link_id, disclosure_id, policy_no, application_no, contract_date, insurer_code, product_key,
                                           source, source_ref, received_at, linked_by)
                VALUES (:tenantId, :linkId, :disclosureId, :policyNo, :applicationNo, :contractDate, :insurerCode, :productKey,
                        :source, :sourceRef, :receivedAt, :linkedBy)
                """, p);
    }

    @Override
    public void mirror(DisclosureId disclosure, String policyNo, LocalDate contractDate) {
        disclosures.mirrorContractLink(disclosure, policyNo, contractDate);      // 확인서 쓰기는 확인서 저장소 한 곳(DisclosureWriteScanTest)
    }

    @Override
    public boolean extendRetention(DisclosureId disclosure, LocalDate until) {
        return disclosures.extendRetention(disclosure, until);
    }

    @Override
    public void insertUnmatched(UUID id, ContractLinkBatch.Item item, String reason, String source, String sourceRef, Instant receivedAt) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("policyNo", item.policyNo());
        p.put("contractDate", Date.valueOf(item.contractDate()));
        p.put("insurerCode", item.insurerCode());
        p.put("reason", reason);
        p.put("source", source);
        p.put("sourceRef", sourceRef);
        p.put("receivedAt", Timestamp.from(receivedAt));
        update("""
                INSERT INTO contract_link_unmatched (tenant_id, unmatched_id, policy_no, contract_date, insurer_code, reason, source, source_ref, received_at)
                VALUES (:tenantId, :id, :policyNo, :contractDate, :insurerCode, :reason, :source, :sourceRef, :receivedAt)
                """, p);
    }

    @Override
    public int purgeUnmatched(Instant receivedBefore, int limit) {
        return update("""
                DELETE FROM contract_link_unmatched
                 WHERE tenant_id = :tenantId
                   AND unmatched_id IN (SELECT unmatched_id
                                          FROM contract_link_unmatched
                                         WHERE tenant_id = :tenantId
                                           AND received_at < :before
                                         ORDER BY received_at, unmatched_id
                                         LIMIT :limit)
                """, Map.of("before", Timestamp.from(receivedBefore), "limit", limit));
    }
}
