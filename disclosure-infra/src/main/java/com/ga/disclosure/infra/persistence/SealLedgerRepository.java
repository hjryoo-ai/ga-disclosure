package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.workflow.disclosure.SealLedgerPort;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;

/** {@link SealLedgerPort} 어댑터(V7 {@code disclosure_counter}·{@code disclosure_chain_head}). 쓰기 SQL은 이 클래스의 두 메서드에만(SealWriteScanTest). */
@Repository
public class SealLedgerRepository extends TenantScopedRepository implements SealLedgerPort {

    public SealLedgerRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public int issueNumber(int year) {
        return queryAtMostOne("""
                INSERT INTO disclosure_counter (tenant_id, year, seq)
                VALUES (:tenantId, :year, 1)
                ON CONFLICT (tenant_id, year) DO UPDATE SET seq = disclosure_counter.seq + 1
                RETURNING seq
                """, Map.of("year", (short) year), (rs, n) -> rs.getInt("seq"))
                .orElseThrow(() -> new IllegalStateException("counter upsert returned no row"));
    }

    @Override
    public Optional<ChainLink> lockChainHead() {
        return queryAtMostOne("""
                SELECT chain_seq, chain_hash
                  FROM disclosure_chain_head
                 WHERE tenant_id = :tenantId
                   FOR UPDATE
                """, Map.of(), (rs, n) -> new ChainLink(rs.getLong("chain_seq"), rs.getString("chain_hash")));
    }

    @Override
    public void advanceChainHead(ChainLink next) {
        int updated = next.seq() == 1
                ? update("""
                        INSERT INTO disclosure_chain_head (tenant_id, chain_seq, chain_hash)
                        VALUES (:tenantId, :seq, :hash)
                        """, Map.of("seq", next.seq(), "hash", next.hash()))
                : update("""
                        UPDATE disclosure_chain_head
                           SET chain_seq = :seq, chain_hash = :hash
                         WHERE tenant_id = :tenantId
                           AND chain_seq = :seq - 1
                        """, Map.of("seq", next.seq(), "hash", next.hash()));
        if (updated != 1) {
            throw new IllegalStateException("chain head did not advance to " + next.seq());
        }
    }
}
