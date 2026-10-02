package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.enums.ArtifactKind;
import com.ga.disclosure.domain.enums.SignatureEvidenceKind;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.Sha256;
import com.ga.disclosure.workflow.artifact.ArtifactRecord;
import com.ga.disclosure.workflow.artifact.DocumentCryptoPort;
import com.ga.disclosure.workflow.artifact.DocumentRecordStore;
import com.ga.disclosure.workflow.artifact.LockedObject;
import com.ga.disclosure.workflow.artifact.SignatureEvidenceRecord;
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
 * {@link DocumentRecordStore} 어댑터(V7 {@code document_key}·{@code document_artifact}, V8 {@code signature_evidence}). 쓰기 SQL은
 * {@link #insertKey}·{@link #insertArtifact}·{@link #insertEvidence}·보존 기록 두 메서드에만(SealWriteScanTest). 키 파기·행 삭제 경로는 없다
 * (GD092·GD093·GD105·GD030). 재적용·잔여물 정리는 산출물과 서명 증거를 한 경로로 본다(4 계획 승인 Q2).
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
    public void insertEvidence(SignatureEvidenceRecord r) {
        Map<String, Object> p = new HashMap<>();
        p.put("signatureId", r.signatureId());
        p.put("kind", r.kind().name());
        p.put("disclosureId", r.disclosureId().value());
        p.put("storageKey", r.storageKey());
        p.put("sha256", r.sha256().hex());
        p.put("bytes", r.bytes());
        p.put("cipherSha256", r.cipherSha256().hex());
        p.put("cipherBytes", r.cipherBytes());
        p.put("keyId", r.keyId());
        p.put("createdAt", Timestamp.from(r.createdAt()));
        update("""
                INSERT INTO signature_evidence (tenant_id, signature_id, kind, disclosure_id, storage_key, sha256, bytes, cipher_sha256, cipher_bytes,
                                                key_id, created_at)
                VALUES (:tenantId, :signatureId, :kind, :disclosureId, :storageKey, :sha256, :bytes, :cipherSha256, :cipherBytes, :keyId, :createdAt)
                """, p);
    }

    @Override
    public List<SignatureEvidenceRecord> evidence(DisclosureId disclosure) {
        return query("""
                SELECT disclosure_id, signature_id, kind, storage_key, sha256, bytes, cipher_sha256, cipher_bytes, key_id, created_at,
                       retention_applied_at
                  FROM signature_evidence
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :disclosureId
                 ORDER BY created_at, signature_id, kind
                """, Map.of("disclosureId", disclosure.value()), (rs, n) -> evidence(rs));
    }

    @Override
    public boolean markRetentionApplied(LockedObject object, Instant at, LocalDate until) {
        return switch (object) {
            case ArtifactRecord a -> markArtifactRetention(a, at, until);
            case SignatureEvidenceRecord e -> markEvidenceRetention(e, at, until);
        };
    }

    private boolean markEvidenceRetention(SignatureEvidenceRecord e, Instant at, LocalDate until) {
        return update("""
                UPDATE signature_evidence
                   SET retention_applied_at = coalesce(retention_applied_at, :at),
                       retention_applied_until = :until
                 WHERE tenant_id = :tenantId
                   AND signature_id = :signatureId
                   AND kind = :kind
                   AND (retention_applied_until IS NULL OR retention_applied_until < :until)
                """, Map.of("signatureId", e.signatureId(), "kind", e.kind().name(), "at", Timestamp.from(at), "until", until)) == 1;
    }

    private boolean markArtifactRetention(ArtifactRecord a, Instant at, LocalDate until) {
        DisclosureId disclosure = a.disclosureId();
        ArtifactKind kind = a.kind();
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

    /** 산출물과 서명 증거를 한 목록으로(만든 순서) — 재적용이 같은 경로로 처리한다. */
    @Override
    public List<Unretained> unretained(int limit) {
        return query("""
                SELECT a.disclosure_id, NULL::uuid AS signature_id, a.kind, a.storage_key, a.sha256, a.bytes, a.cipher_sha256, a.cipher_bytes,
                       a.key_id, a.created_at, a.retention_applied_at, d.retention_until, 'ARTIFACT' AS source
                  FROM document_artifact a
                  JOIN disclosure d ON d.tenant_id = a.tenant_id AND d.disclosure_id = a.disclosure_id
                 WHERE a.tenant_id = :tenantId
                   AND d.tenant_id = :tenantId
                   AND (a.retention_applied_until IS NULL OR a.retention_applied_until < d.retention_until)
                UNION ALL
                SELECT e.disclosure_id, e.signature_id, e.kind, e.storage_key, e.sha256, e.bytes, e.cipher_sha256, e.cipher_bytes,
                       e.key_id, e.created_at, e.retention_applied_at, d.retention_until, 'EVIDENCE' AS source
                  FROM signature_evidence e
                  JOIN disclosure d ON d.tenant_id = e.tenant_id AND d.disclosure_id = e.disclosure_id
                 WHERE e.tenant_id = :tenantId
                   AND d.tenant_id = :tenantId
                   AND (e.retention_applied_until IS NULL OR e.retention_applied_until < d.retention_until)
                 ORDER BY created_at, disclosure_id, storage_key
                 LIMIT :limit
                """, Map.of("limit", limit), (rs, n) -> new Unretained(rs.getString("source").equals("ARTIFACT") ? artifact(rs) : evidence(rs),
                rs.getObject("retention_until", LocalDate.class)));
    }

    @Override
    public boolean referenced(String storageKey) {
        return queryAtMostOne("""
                SELECT 1 AS hit
                  FROM document_artifact
                 WHERE tenant_id = :tenantId
                   AND storage_key = :storageKey
                UNION ALL
                SELECT 1 AS hit
                  FROM signature_evidence
                 WHERE tenant_id = :tenantId
                   AND storage_key = :storageKey
                 LIMIT 1
                """, Map.of("storageKey", storageKey), (rs, n) -> Boolean.TRUE).isPresent();
    }

    private static SignatureEvidenceRecord evidence(ResultSet rs) throws SQLException {
        Timestamp retained = rs.getTimestamp("retention_applied_at");
        return new SignatureEvidenceRecord(DisclosureId.of(rs.getObject("disclosure_id", UUID.class)), rs.getObject("signature_id", UUID.class),
                SignatureEvidenceKind.valueOf(rs.getString("kind")), rs.getString("storage_key"), Sha256.of(rs.getString("sha256")),
                rs.getLong("bytes"), Sha256.of(rs.getString("cipher_sha256")), rs.getLong("cipher_bytes"), rs.getString("key_id"),
                rs.getTimestamp("created_at").toInstant(), retained == null ? null : retained.toInstant());
    }

    private static ArtifactRecord artifact(ResultSet rs) throws SQLException {
        Timestamp retained = rs.getTimestamp("retention_applied_at");
        return new ArtifactRecord(DisclosureId.of(rs.getObject("disclosure_id", UUID.class)), ArtifactKind.valueOf(rs.getString("kind")),
                rs.getString("storage_key"), Sha256.of(rs.getString("sha256")), rs.getLong("bytes"), Sha256.of(rs.getString("cipher_sha256")),
                rs.getLong("cipher_bytes"), rs.getString("key_id"), rs.getTimestamp("created_at").toInstant(),
                retained == null ? null : retained.toInstant());
    }
}
