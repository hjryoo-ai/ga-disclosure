package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.Sha256;
import com.ga.disclosure.workflow.artifact.ArtifactRecord;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link DocumentRecordStore} 어댑터(V7 {@code document_key}·{@code document_artifact}). 쓰기 SQL은 {@link #insertKey}·{@link #insertArtifact}·
 * {@link #markRetentionApplied}에만(SealWriteScanTest). 키 파기·행 삭제 경로는 없다(GD092·GD093·GD030).
 */
@Repository
public class DocumentRecordRepository extends TenantScopedRepository implements DocumentRecordStore {

    public DocumentRecordRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public void insertKey(DisclosureId disclosure, DocumentCryptoPort.StoredKey key, Instant createdAt) {
        update("""
                INSERT INTO document_key (tenant_id, key_id, disclosure_id, kek_key_id, wrapped_dek, created_at)
                VALUES (:tenantId, :keyId, :disclosureId, :kekKeyId, :wrapped, :createdAt)
                """, Map.of("keyId", key.keyId(), "disclosureId", disclosure.value(), "kekKeyId", key.kekKeyId(), "wrapped", key.wrappedDek(),
                "createdAt", Timestamp.from(createdAt)));
    }

    @Override
    public void insertArtifact(ArtifactRecord r) {
        Map<String, Object> p = new HashMap<>();
        p.put("disclosureId", r.disclosureId().value());
        p.put("kind", r.kind().name());
        p.put("storageKey", r.storageKey());
        p.put("sha256", r.sha256().hex());
        p.put("bytes", r.bytes());
        p.put("cipherSha256", r.cipherSha256().hex());
        p.put("cipherBytes", r.cipherBytes());
        p.put("keyId", r.keyId());
        p.put("createdAt", Timestamp.from(r.createdAt()));
        update("""
                INSERT INTO document_artifact (tenant_id, disclosure_id, kind, storage_key, sha256, bytes, created_at, cipher_sha256, cipher_bytes,
                                               key_id)
                VALUES (:tenantId, :disclosureId, :kind, :storageKey, :sha256, :bytes, :createdAt, :cipherSha256, :cipherBytes, :keyId)
                """, p);
    }

    @Override
    public List<ArtifactRecord> artifacts(DisclosureId disclosure) {
        return query("""
                SELECT disclosure_id, kind, storage_key, sha256, bytes, cipher_sha256, cipher_bytes, key_id, created_at, retention_applied_at
                  FROM document_artifact
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :disclosureId
                 ORDER BY kind
                """, Map.of("disclosureId", disclosure.value()), (rs, n) -> artifact(rs));
    }

    @Override
    public KeyLookup key(DisclosureId disclosure) {
        return queryAtMostOne("""
                SELECT key_id, kek_key_id, wrapped_dek, shredded_at
                  FROM document_key
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :disclosureId
                """, Map.of("disclosureId", disclosure.value()), (rs, n) -> {
            byte[] wrapped = rs.getBytes("wrapped_dek");
            return wrapped == null
                    ? (KeyLookup) new KeyLookup.Shredded(rs.getString("key_id"), rs.getTimestamp("shredded_at").toInstant())
                    : new KeyLookup.Live(new DocumentCryptoPort.StoredKey(rs.getString("key_id"), rs.getString("kek_key_id"), wrapped));
        }).orElseGet(KeyLookup.Missing::new);
    }

    @Override
    public boolean markRetentionApplied(DisclosureId disclosure, ArtifactKind kind, Instant at, LocalDate until) {
        return update("""
                UPDATE document_artifact
                   SET retention_applied_at = coalesce(retention_applied_at, :at),
                       retention_applied_until = :until
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :disclosureId
                   AND kind = :kind
                   AND (retention_applied_until IS NULL OR retention_applied_until < :until)
                """, Map.of("disclosureId", disclosure.value(), "kind", kind.name(), "at", Timestamp.from(at), "until", until)) == 1;
    }

    @Override
    public List<Unretained> unretained(int limit) {
        return query("""
                SELECT a.disclosure_id, a.kind, a.storage_key, a.sha256, a.bytes, a.cipher_sha256, a.cipher_bytes, a.key_id, a.created_at,
                       a.retention_applied_at, d.retention_until
                  FROM document_artifact a
                  JOIN disclosure d ON d.tenant_id = a.tenant_id AND d.disclosure_id = a.disclosure_id
                 WHERE a.tenant_id = :tenantId
                   AND d.tenant_id = :tenantId
                   AND (a.retention_applied_until IS NULL OR a.retention_applied_until < d.retention_until)
                 ORDER BY a.created_at, a.disclosure_id, a.kind
                 LIMIT :limit
                """, Map.of("limit", limit), (rs, n) -> new Unretained(artifact(rs), rs.getObject("retention_until", LocalDate.class)));
    }

    @Override
    public boolean referenced(String storageKey) {
        return queryAtMostOne("""
                SELECT 1 AS hit
                  FROM document_artifact
                 WHERE tenant_id = :tenantId
                   AND storage_key = :storageKey
                 LIMIT 1
                """, Map.of("storageKey", storageKey), (rs, n) -> Boolean.TRUE).isPresent();
    }

    private static ArtifactRecord artifact(ResultSet rs) throws SQLException {
        Timestamp retained = rs.getTimestamp("retention_applied_at");
        return new ArtifactRecord(DisclosureId.of(rs.getObject("disclosure_id", UUID.class)), ArtifactKind.valueOf(rs.getString("kind")),
                rs.getString("storage_key"), Sha256.of(rs.getString("sha256")), rs.getLong("bytes"), Sha256.of(rs.getString("cipher_sha256")),
                rs.getLong("cipher_bytes"), rs.getString("key_id"), rs.getTimestamp("created_at").toInstant(),
                retained == null ? null : retained.toInstant());
    }
}
