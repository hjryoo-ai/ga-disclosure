package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.enums.SignatureMethod;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.Sha256;
import com.ga.disclosure.workflow.sign.DeviceInfo;
import com.ga.disclosure.workflow.sign.IdentityResult;
import com.ga.disclosure.workflow.sign.ScanMatch;
import com.ga.disclosure.workflow.sign.SignatureStore;
import com.ga.disclosure.workflow.sign.StoredSignature;
import com.ga.disclosure.workflow.sign.ViewEvidence;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link SignatureStore} 어댑터(V8 {@code signature}, append-only). 쓰기는 {@link #insert} 하나다(쓰기 스캔 허용 목록). INSERT 트리거가 부모 상태·두
 * 해시·세션·서명자 집합을 검사한다(GD021·022·102~104). IP는 {@code INET}이고 읽을 때 {@code host()}로 마스크 없이 돌려준다.
 */
@Repository
public class SignatureRepository extends TenantScopedRepository implements SignatureStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public SignatureRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public void insert(StoredSignature s) {
        Map<String, Object> p = new HashMap<>();
        p.put("signatureId", s.signatureId());
        p.put("disclosureId", s.disclosureId().value());
        p.put("role", s.role().name());
        p.put("subject", s.signerSubjectOrNull());
        p.put("channel", s.channel().name());
        p.put("method", s.method().name());
        p.put("docHash", s.signedDocHash().hex());
        p.put("pdfHash", s.signedPdfHash().hex());
        p.put("sessionId", s.sessionIdOrNull());
        p.put("identity", JSON.writeValueAsString(IdentityResult.toJson(s.identityCheck())));
        p.put("view", s.viewOrNull() == null ? null : JSON.writeValueAsString(s.viewOrNull().toJson()));
        p.put("device", s.deviceOrNull() == null ? null : JSON.writeValueAsString(s.deviceOrNull().toJson()));
        p.put("ip", s.ipOrNull());
        p.put("acks", s.acknowledgedFlags().stream().map(UUID::toString).toArray(String[]::new));
        p.put("scan", s.scanMatchOrNull() == null ? null : JSON.writeValueAsString(s.scanMatchOrNull().toJson()));
        p.put("signedAt", Timestamp.from(s.signedAt()));
        update("""
                INSERT INTO signature (tenant_id, signature_id, disclosure_id, signer_role, signer_subject, channel, method, signed_doc_hash,
                                       signed_pdf_hash, session_id, identity_check, view_evidence, device, ip, acknowledged_flags, scan_match,
                                       signed_at)
                VALUES (:tenantId, :signatureId, :disclosureId, :role, :subject, :channel, :method, :docHash, :pdfHash, :sessionId,
                        CAST(:identity AS jsonb), CAST(:view AS jsonb), CAST(:device AS jsonb), CAST(:ip AS inet), CAST(:acks AS uuid[]),
                        CAST(:scan AS jsonb), :signedAt)
                """, p);
    }

    @Override
    public List<StoredSignature> signatures(DisclosureId disclosure) {
        return query("""
                SELECT signature_id, disclosure_id, signer_role, signer_subject, channel, method, signed_doc_hash, signed_pdf_hash, session_id,
                       identity_check::text AS identity_check, view_evidence::text AS view_evidence, device::text AS device, host(ip) AS ip,
                       acknowledged_flags, scan_match::text AS scan_match, signed_at
                  FROM signature
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :disclosureId
                 ORDER BY signed_at, signature_id
                """, Map.of("disclosureId", disclosure.value()), (rs, n) -> signature(rs));
    }

    @Override
    public int distinctCustomersByDevice(String fingerprint, LocalDate day) {
        return count("s.device ->> 'fingerprint' = :key", fingerprint, day);
    }

    @Override
    public int distinctCustomersByIp(String ip, LocalDate day) {
        return count("s.ip = CAST(:key AS inet)", ip, day);
    }

    /** 같은 테넌트 REMOTE_LINK 고객 서명 중 키가 같고 서명일(Asia/Seoul)이 {@code day}인 서명의 서로 다른 고객 수. */
    private int count(String keyPredicate, String key, LocalDate day) {
        return query("""
                SELECT count(DISTINCT d.customer_ref) AS customers
                  FROM signature s
                  JOIN disclosure d ON d.tenant_id = s.tenant_id AND d.disclosure_id = s.disclosure_id
                 WHERE s.tenant_id = :tenantId
                   AND d.tenant_id = :tenantId
                   AND s.signer_role = 'CUSTOMER'
                   AND s.channel = 'REMOTE_LINK'
                   AND (s.signed_at AT TIME ZONE 'Asia/Seoul')::date = :day
                   AND\s""" + keyPredicate, Map.of("key", key, "day", day), (rs, n) -> rs.getInt("customers")).getFirst();
    }

    private static StoredSignature signature(ResultSet rs) throws SQLException {
        String view = rs.getString("view_evidence");
        String device = rs.getString("device");
        String scan = rs.getString("scan_match");
        UUID[] acks = (UUID[]) rs.getArray("acknowledged_flags").getArray();
        return new StoredSignature(rs.getObject("signature_id", UUID.class), DisclosureId.of(rs.getObject("disclosure_id", UUID.class)),
                SignerRole.valueOf(rs.getString("signer_role")), rs.getString("signer_subject"), SignatureChannel.valueOf(rs.getString("channel")),
                SignatureMethod.valueOf(rs.getString("method")), Sha256.of(rs.getString("signed_doc_hash")), Sha256.of(rs.getString("signed_pdf_hash")),
                rs.getObject("session_id", UUID.class), IdentityResult.fromJson(JSON.readTree(rs.getString("identity_check"))),
                view == null ? null : ViewEvidence.fromJson(JSON.readTree(view)), device == null ? null : DeviceInfo.fromJson(JSON.readTree(device)),
                rs.getString("ip"), Arrays.asList(acks), scan == null ? null : ScanMatch.fromJson(JSON.readTree(scan)),
                rs.getTimestamp("signed_at").toInstant());
    }
}
