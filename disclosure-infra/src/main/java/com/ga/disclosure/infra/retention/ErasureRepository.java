package com.ga.disclosure.infra.retention;

import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.workflow.retention.ErasureReader;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 파기 직전 값의 감사 표현(5 계획 §5.5). 앱 롤로 읽는다(지정 컬럼 SELECT는 V2 그대로). IP는 DB에서 {@code family()}만 꺼내 원문이 앱으로 오지 않는다.
 * 텍스트·JSON 원문은 이 클래스 안에서 해시로 바뀌고 밖으로 나가지 않는다.
 */
@Repository
public class ErasureRepository extends TenantScopedRepository implements ErasureReader {

    static final String UTF8 = "sha256-utf8";
    static final String JCS = "sha256-jcs";
    static final String STORED = "sha256-stored";
    static final String PRESENCE = "presence";
    static final String FAMILY = "presence-family";

    public ErasureRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public List<Erased> documentKey(DisclosureId disclosure) {
        return query("""
                SELECT key_id, wrapped_dek
                  FROM document_key
                 WHERE tenant_id = :tenantId AND disclosure_id = :id AND wrapped_dek IS NOT NULL
                """, Map.of("id", disclosure.value()),
                (rs, n) -> new Erased("document_key", "wrapped_dek", rs.getString("key_id"), STORED, Sha256.of(rs.getBytes("wrapped_dek"))));
    }

    @Override
    public List<Erased> disclosure(DisclosureId disclosure) {
        Map<String, Object> id = Map.of("id", disclosure.value());
        List<Erased> out = new ArrayList<>();
        query("""
                SELECT void_reason_text, supersede_reason_text, policy_no
                  FROM disclosure
                 WHERE tenant_id = :tenantId AND disclosure_id = :id
                """, id, (rs, n) -> {
            text(out, "disclosure", "void_reason_text", disclosure.toString(), rs.getString("void_reason_text"));
            text(out, "disclosure", "supersede_reason_text", disclosure.toString(), rs.getString("supersede_reason_text"));
            text(out, "disclosure", "policy_no", disclosure.toString(), rs.getString("policy_no"));
            return null;
        });
        query("""
                SELECT item_no, reason_text
                  FROM recommendation
                 WHERE tenant_id = :tenantId AND disclosure_id = :id AND reason_text IS NOT NULL
                 ORDER BY item_no
                """, id, (rs, n) -> {
            text(out, "recommendation", "reason_text", disclosure + "/" + rs.getInt("item_no"), rs.getString("reason_text"));
            return null;
        });
        query("""
                SELECT review_id, reason
                  FROM review
                 WHERE tenant_id = :tenantId AND disclosure_id = :id AND reason IS NOT NULL
                 ORDER BY review_id
                """, id, (rs, n) -> {
            text(out, "review", "reason", rs.getString("review_id"), rs.getString("reason"));
            return null;
        });
        query("""
                SELECT signature_id, device IS NOT NULL AS has_device, family(ip) AS ip_family, view_evidence::text AS view_evidence
                  FROM signature
                 WHERE tenant_id = :tenantId AND disclosure_id = :id
                 ORDER BY signature_id
                """, id, (rs, n) -> {
            String row = rs.getString("signature_id");
            if (rs.getBoolean("has_device")) {
                out.add(new Erased("signature", "device", row, PRESENCE, "present"));
            }
            int family = rs.getInt("ip_family");
            if (!rs.wasNull()) {
                out.add(new Erased("signature", "ip", row, FAMILY, Integer.toString(family)));
            }
            json(out, "signature", "view_evidence", row, rs.getString("view_evidence"));
            return null;
        });
        query("""
                SELECT session_id, view_evidence::text AS view_evidence
                  FROM sign_session
                 WHERE tenant_id = :tenantId AND disclosure_id = :id AND view_evidence IS NOT NULL
                 ORDER BY session_id
                """, id, (rs, n) -> {
            json(out, "sign_session", "view_evidence", rs.getString("session_id"), rs.getString("view_evidence"));
            return null;
        });
        query("""
                SELECT flag_id, policy_no
                  FROM compliance_flag
                 WHERE tenant_id = :tenantId AND disclosure_id = :id AND policy_no IS NOT NULL
                 ORDER BY flag_id
                """, id, (rs, n) -> {
            text(out, "compliance_flag", "policy_no", rs.getString("flag_id"), rs.getString("policy_no"));
            return null;
        });
        releasedHoldTexts(out, """
                SELECT hold_id, reason_text
                  FROM legal_hold
                 WHERE tenant_id = :tenantId AND disclosure_id = :id AND released_at IS NOT NULL AND reason_text IS NOT NULL
                 ORDER BY hold_id
                """, id);
        return out;
    }

    /** 해제된 보류의 사유 텍스트(V11 — 활성 보류가 있으면 파기 자체가 거부된다). */
    private void releasedHoldTexts(List<Erased> out, String sql, Map<String, Object> params) {
        query(sql, params, (rs, n) -> {
            text(out, "legal_hold", "reason_text", rs.getString("hold_id"), rs.getString("reason_text"));
            return null;
        });
    }

    @Override
    public List<Erased> customer(CustomerRef customer) {
        List<Erased> out = new ArrayList<>();
        query("""
                SELECT name_enc, phone_enc, birth_date_enc, crm_customer_id
                  FROM customer_ref
                 WHERE tenant_id = :tenantId AND customer_ref = :ref
                """, Map.of("ref", customer.value()), (rs, n) -> {
            stored(out, "name_enc", customer.value(), rs.getBytes("name_enc"));
            stored(out, "phone_enc", customer.value(), rs.getBytes("phone_enc"));
            stored(out, "birth_date_enc", customer.value(), rs.getBytes("birth_date_enc"));
            text(out, "customer_ref", "crm_customer_id", customer.value(), rs.getString("crm_customer_id"));
            return null;
        });
        releasedHoldTexts(out, """
                SELECT hold_id, reason_text
                  FROM legal_hold
                 WHERE tenant_id = :tenantId AND customer_ref = :ref AND released_at IS NOT NULL AND reason_text IS NOT NULL
                 ORDER BY hold_id
                """, Map.of("ref", customer.value()));
        return out;
    }

    private static void text(List<Erased> out, String table, String column, String row, String value) {
        if (value != null) {
            out.add(new Erased(table, column, row, UTF8, Sha256.of(value.getBytes(StandardCharsets.UTF_8))));
        }
    }

    private static void json(List<Erased> out, String table, String column, String row, String value) {
        if (value != null) {
            out.add(new Erased(table, column, row, JCS, Sha256.of(Canonicalizer.canonicalize(value))));
        }
    }

    private static void stored(List<Erased> out, String column, String row, byte[] value) {
        if (value != null) {
            out.add(new Erased("customer_ref", column, row, STORED, Sha256.of(value)));
        }
    }
}
