package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.verify.SealChainReader;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 봉인 체인 고리 읽기(V7 봉인 컬럼, 해시만). 파기된 확인서도 묘비로 남는다(V9 — 체인 컬럼은 파기 대상이 아니다). */
@Repository
public class SealChainRepository extends TenantScopedRepository implements SealChainReader {

    private static final String COLUMNS = "chain_seq, disclosure_id, disclosure_no, canonical_hash, pdf_hash, chain_hash, sealed_at, destroyed_at";
    private static final RowMapper<ChainRow> MAPPER = (rs, n) -> {
        Timestamp destroyed = rs.getTimestamp("destroyed_at");
        return new ChainRow(rs.getLong("chain_seq"), DisclosureId.of(rs.getObject("disclosure_id", UUID.class)), rs.getString("disclosure_no"),
                rs.getString("canonical_hash"), rs.getString("pdf_hash"), rs.getString("chain_hash"), rs.getTimestamp("sealed_at").toInstant(),
                destroyed == null ? null : destroyed.toInstant());
    };

    public SealChainRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public List<ChainRow> links(long fromSeq, long toSeq) {
        return query("SELECT " + COLUMNS + " FROM disclosure WHERE tenant_id = :tenantId AND chain_seq BETWEEN :fromSeq AND :toSeq ORDER BY chain_seq",
                Map.of("fromSeq", fromSeq, "toSeq", toSeq), MAPPER);
    }

    @Override
    public Optional<ChainRow> byDisclosure(DisclosureId id) {
        return queryAtMostOne("SELECT " + COLUMNS + " FROM disclosure WHERE tenant_id = :tenantId AND disclosure_id = :id AND chain_seq IS NOT NULL",
                Map.of("id", id.value()), MAPPER);
    }

    @Override
    public long headSeq() {
        return queryAtMostOne("SELECT chain_seq FROM disclosure_chain_head WHERE tenant_id = :tenantId", Map.of(), (rs, n) -> rs.getLong("chain_seq"))
                .orElse(0L);
    }
}
