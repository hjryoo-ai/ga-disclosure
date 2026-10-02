package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.domain.enums.IdentityMethod;
import com.ga.disclosure.domain.enums.SignatureChannel;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.Sha256;
import com.ga.disclosure.sign.session.SessionRevokeReason;
import com.ga.disclosure.sign.session.SessionStatus;
import com.ga.disclosure.sign.session.SignSessionState;
import com.ga.disclosure.workflow.sign.SignSession;
import com.ga.disclosure.workflow.sign.SignSessionStore;
import com.ga.disclosure.workflow.sign.ViewEvidence;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link SignSessionStore} 어댑터(V8 {@code sign_session}). 쓰기는 {@link #insert}(발급)와 {@link #update}(가변 컬럼만) 둘이다(쓰기 스캔
 * 허용 목록). 고정 컬럼·상태 규칙은 트리거 GD101이 다시 지킨다. 토큰 원문은 이 클래스에 들어오지 않는다(해시만).
 */
@Repository
public class SignSessionRepository extends TenantScopedRepository implements SignSessionStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COLUMNS = """
            session_id, disclosure_id, channel, token_hash, issued_by, issued_at, expires_at, signed_doc_hash, signed_pdf_hash, status,
            identity_failures, identity_passed, view_evidence::text AS view_evidence, sent_at, used_at, revoked_at, revoke_reason""";

    public SignSessionRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public void insert(SignSession s) {
        update("""
                INSERT INTO sign_session (tenant_id, session_id, disclosure_id, signer_role, channel, token_hash, issued_by, issued_at, expires_at,
                                          signed_doc_hash, signed_pdf_hash, status)
                VALUES (:tenantId, :sessionId, :disclosureId, 'CUSTOMER', :channel, :tokenHash, :issuedBy, :issuedAt, :expiresAt,
                        :docHash, :pdfHash, 'OPEN')
                """, Map.of("sessionId", s.sessionId(), "disclosureId", s.disclosureId().value(), "channel", s.channel().name(),
                "tokenHash", s.tokenHash().hex(), "issuedBy", s.issuedBy(), "issuedAt", Timestamp.from(s.issuedAt()),
                "expiresAt", Timestamp.from(s.expiresAt()), "docHash", s.signedDocHash().hex(), "pdfHash", s.signedPdfHash().hex()));
    }

    @Override
    public Optional<SignSession> findByToken(Sha256 tokenHash) {
        return queryAtMostOne("SELECT " + COLUMNS + """

                  FROM sign_session
                 WHERE tenant_id = :tenantId
                   AND token_hash = :tokenHash
                """, Map.of("tokenHash", tokenHash.hex()), (rs, n) -> session(rs));
    }

    @Override
    public Optional<SignSession> lock(UUID sessionId) {
        return queryAtMostOne("SELECT " + COLUMNS + """

                  FROM sign_session
                 WHERE tenant_id = :tenantId
                   AND session_id = :sessionId
                   FOR UPDATE
                """, Map.of("sessionId", sessionId), (rs, n) -> session(rs));
    }

    @Override
    public void update(SignSession s) {
        Map<String, Object> p = new HashMap<>();
        p.put("sessionId", s.sessionId());
        p.put("status", s.state().status().name());
        p.put("failures", s.state().identityFailures());
        p.put("passed", s.state().identityPassed().stream().map(Enum::name).sorted().toArray(String[]::new));
        p.put("view", s.view().map(v -> JSON.writeValueAsString(v.toJson())).orElse(null));
        p.put("sentAt", s.sentAt().map(Timestamp::from).orElse(null));
        p.put("usedAt", s.usedAtOrNull() == null ? null : Timestamp.from(s.usedAtOrNull()));
        p.put("revokedAt", s.revokedAtOrNull() == null ? null : Timestamp.from(s.revokedAtOrNull()));
        p.put("revokeReason", s.state().revokeReason() == null ? null : s.state().revokeReason().name());
        int n = update("""
                UPDATE sign_session
                   SET status = :status,
                       identity_failures = :failures,
                       identity_passed = :passed,
                       view_evidence = CAST(:view AS jsonb),
                       sent_at = :sentAt,
                       used_at = :usedAt,
                       revoked_at = :revokedAt,
                       revoke_reason = :revokeReason
                 WHERE tenant_id = :tenantId
                   AND session_id = :sessionId
                """, p);
        if (n != 1) {
            throw new IllegalStateException("sign_session " + s.sessionId() + " was not updated (" + n + " rows)");
        }
    }

    @Override
    public List<SignSession> openFor(DisclosureId disclosure) {
        return query("SELECT " + COLUMNS + """

                  FROM sign_session
                 WHERE tenant_id = :tenantId
                   AND disclosure_id = :disclosureId
                   AND status = 'OPEN'
                 ORDER BY issued_at, session_id
                   FOR UPDATE
                """, Map.of("disclosureId", disclosure.value()), (rs, n) -> session(rs));
    }

    @Override
    public List<SignSession> openElapsed(Instant asOf, int limit) {
        return query("SELECT " + COLUMNS + """

                  FROM sign_session
                 WHERE tenant_id = :tenantId
                   AND status = 'OPEN'
                   AND expires_at < :asOf
                 ORDER BY expires_at, session_id
                 LIMIT :limit
                """, Map.of("asOf", Timestamp.from(asOf), "limit", limit), (rs, n) -> session(rs));
    }

    private static SignSession session(ResultSet rs) throws SQLException {
        Set<IdentityMethod> passed = EnumSet.noneOf(IdentityMethod.class);
        Arrays.stream((String[]) rs.getArray("identity_passed").getArray()).map(IdentityMethod::valueOf).forEach(passed::add);
        String reason = rs.getString("revoke_reason");
        String view = rs.getString("view_evidence");
        SignSessionState state = new SignSessionState(SessionStatus.valueOf(rs.getString("status")), rs.getInt("identity_failures"), passed,
                view != null, reason == null ? null : SessionRevokeReason.valueOf(reason));
        return new SignSession(rs.getObject("session_id", UUID.class), DisclosureId.of(rs.getObject("disclosure_id", UUID.class)),
                SignatureChannel.valueOf(rs.getString("channel")), Sha256.of(rs.getString("token_hash")), rs.getString("issued_by"),
                instant(rs, "issued_at"), instant(rs, "expires_at"), Sha256.of(rs.getString("signed_doc_hash")), Sha256.of(rs.getString("signed_pdf_hash")),
                state, view == null ? null : ViewEvidence.fromJson(JSON.readTree(view)), instant(rs, "sent_at"), instant(rs, "used_at"),
                instant(rs, "revoked_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
