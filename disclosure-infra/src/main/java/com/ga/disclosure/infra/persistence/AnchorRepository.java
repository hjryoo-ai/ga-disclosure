package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.audit.AuditChain;
import com.ga.disclosure.audit.anchor.AnchorRecord;
import com.ga.disclosure.audit.chain.SealChain;
import com.ga.disclosure.workflow.anchor.AnchorReceipt;
import com.ga.disclosure.workflow.anchor.AnchorStore;
import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 일일 앵커·영수증(V9 {@code anchor}·{@code anchor_receipt}, append-only). 잎과 두 머리(GD110), 경로 → 루트(GD111)는 DB가 다시 계산해 대조한다.
 * 두 체인 머리는 문장을 나눠 읽는다 — REPEATABLE READ 트랜잭션 안에서 부르면 같은 스냅샷이다.
 */
@Repository
public class AnchorRepository extends TenantScopedRepository implements AnchorStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ANCHOR_COLUMNS = "anchor_seq, anchor_date, seal_chain_seq, seal_chain_head, audit_seq, audit_head, leaf_hash, created_at";
    private static final String RECEIPT_COLUMNS = "anchor_seq, batch_id, root_hash, tree_depth, leaf_index, merkle_path::text AS merkle_path, "
            + "tsa_token, tsa_gen_time, tsa_policy_oid, tsa_serial, created_at";

    public AnchorRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public Optional<StoredAnchor> onDate(LocalDate anchorDate) {
        return queryAtMostOne("SELECT " + ANCHOR_COLUMNS + " FROM anchor WHERE tenant_id = :tenantId AND anchor_date = :anchorDate",
                Map.of("anchorDate", Date.valueOf(anchorDate)), anchorMapper(TenantContext.current()));
    }

    @Override
    public Optional<StoredAnchor> latest() {
        return queryAtMostOne("SELECT " + ANCHOR_COLUMNS + " FROM anchor WHERE tenant_id = :tenantId ORDER BY anchor_seq DESC LIMIT 1",
                Map.of(), anchorMapper(TenantContext.current()));
    }

    @Override
    public ChainHeads heads() {
        record Head(long seq, String hash) {
        }
        Head seal = queryAtMostOne("SELECT chain_seq, chain_hash FROM disclosure_chain_head WHERE tenant_id = :tenantId", Map.of(),
                (rs, n) -> new Head(rs.getLong("chain_seq"), rs.getString("chain_hash"))).orElse(new Head(0, SealChain.ZERO));
        Head audit = queryAtMostOne("SELECT seq, entry_hash FROM audit_log WHERE tenant_id = :tenantId ORDER BY seq DESC LIMIT 1", Map.of(),
                (rs, n) -> new Head(rs.getLong("seq"), rs.getString("entry_hash"))).orElse(new Head(0, AuditChain.GENESIS));
        return new ChainHeads(seal.seq(), seal.hash(), audit.seq(), audit.hash());
    }

    @Override
    public void insert(AnchorRecord record, Instant createdAt) {
        if (!record.tenant().equals(TenantContext.current())) {
            throw new IllegalArgumentException("anchor record belongs to another tenant");
        }
        Map<String, Object> p = new HashMap<>();
        p.put("anchorSeq", record.anchorSeq());
        p.put("anchorDate", Date.valueOf(record.anchorDate()));
        p.put("sealChainSeq", record.sealChainSeq());
        p.put("sealChainHead", record.sealChainHead());
        p.put("auditSeq", record.auditSeq());
        p.put("auditHead", record.auditHead());
        p.put("leafHash", record.leafHash());
        p.put("createdAt", Timestamp.from(createdAt));
        update("""
                INSERT INTO anchor (tenant_id, anchor_seq, anchor_date, seal_chain_seq, seal_chain_head, audit_seq, audit_head, leaf_hash, created_at)
                VALUES (:tenantId, :anchorSeq, :anchorDate, :sealChainSeq, :sealChainHead, :auditSeq, :auditHead, :leafHash, :createdAt)
                """, p);
    }

    @Override
    public List<StoredAnchor> unstamped() {
        return query("SELECT " + ANCHOR_COLUMNS + """
                 FROM anchor a
                WHERE a.tenant_id = :tenantId
                  AND NOT EXISTS (SELECT 1 FROM anchor_receipt r WHERE r.tenant_id = :tenantId AND r.anchor_seq = a.anchor_seq)
                ORDER BY a.anchor_seq
                """, Map.of(), anchorMapper(TenantContext.current()));
    }

    @Override
    public void insertReceipt(AnchorReceipt receipt) {
        ArrayNode path = JSON.createArrayNode();
        receipt.merklePath().forEach(path::add);
        Map<String, Object> p = new HashMap<>();
        p.put("anchorSeq", receipt.anchorSeq());
        p.put("batchId", receipt.batchId());
        p.put("rootHash", receipt.rootHash());
        p.put("treeDepth", receipt.treeDepth());
        p.put("leafIndex", receipt.leafIndex());
        p.put("merklePath", JSON.writeValueAsString(path));
        p.put("tsaToken", receipt.tsaToken());
        p.put("tsaGenTime", Timestamp.from(receipt.tsaGenTime()));
        p.put("tsaPolicyOid", receipt.tsaPolicyOid());
        p.put("tsaSerial", receipt.tsaSerial());
        p.put("createdAt", Timestamp.from(receipt.createdAt()));
        update("""
                INSERT INTO anchor_receipt (tenant_id, anchor_seq, batch_id, root_hash, tree_depth, leaf_index, merkle_path, tsa_token, tsa_gen_time,
                                            tsa_policy_oid, tsa_serial, created_at)
                VALUES (:tenantId, :anchorSeq, :batchId, :rootHash, :treeDepth, :leafIndex, CAST(:merklePath AS jsonb), :tsaToken, :tsaGenTime,
                        :tsaPolicyOid, :tsaSerial, :createdAt)
                """, p);
    }

    @Override
    public Optional<AnchorReceipt> receipt(long anchorSeq) {
        return queryAtMostOne("SELECT " + RECEIPT_COLUMNS + " FROM anchor_receipt WHERE tenant_id = :tenantId AND anchor_seq = :anchorSeq",
                Map.of("anchorSeq", anchorSeq), RECEIPT_MAPPER);
    }

    private static RowMapper<StoredAnchor> anchorMapper(TenantId tenant) {
        return (rs, n) -> new StoredAnchor(new AnchorRecord(tenant, rs.getLong("anchor_seq"), rs.getDate("anchor_date").toLocalDate(),
                rs.getLong("seal_chain_seq"), rs.getString("seal_chain_head"), rs.getLong("audit_seq"), rs.getString("audit_head")),
                rs.getString("leaf_hash"), rs.getTimestamp("created_at").toInstant());
    }

    private static final RowMapper<AnchorReceipt> RECEIPT_MAPPER = (rs, n) -> {
        List<String> path = new ArrayList<>();
        for (JsonNode node : JSON.readTree(rs.getString("merkle_path"))) {
            path.add(node.asString());
        }
        return new AnchorReceipt(rs.getLong("anchor_seq"), rs.getObject("batch_id", UUID.class), rs.getString("root_hash"), rs.getInt("tree_depth"),
                rs.getInt("leaf_index"), path, rs.getBytes("tsa_token"), rs.getTimestamp("tsa_gen_time").toInstant(), rs.getString("tsa_policy_oid"),
                rs.getString("tsa_serial"), rs.getTimestamp("created_at").toInstant());
    };
}
