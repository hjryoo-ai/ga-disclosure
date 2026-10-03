package com.ga.disclosure.infra;

import com.ga.disclosure.audit.AuditAction;
import com.ga.disclosure.audit.AuditRecord;
import com.ga.disclosure.audit.verify.FindingCode;
import com.ga.disclosure.audit.verify.VerifyReport;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.infra.persistence.AnchorRepository;
import com.ga.disclosure.rules.resolve.RuleResolver;
import com.ga.disclosure.workflow.verify.TenantVerifier;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G9(CLAUDE.md 절대 규칙 2, 5 계획 §5.5·§5.6): 파기 전후 행 비교 — 지정 컬럼만 NULL이 되고 번호·상태·해시·시각·체인은 그대로. 감사의 지운 값 표현은 파기 전
 * 행에서 따로 계산한 값과 같다(평문 해시·JCS 해시·저장 바이트 해시·존재·IP 계열). 파기 뒤에도 봉인 체인·채번·{@code verify tenant}가 성립하고(파기 건 객체
 * 부재 = 정상), 파기된 확인서의 객체가 다시 나타나거나 감사 없는 {@code destroyed_at}은 잡힌다.
 */
class TombstoneIT {

    /** pii-columns 블록의 ga_disclosure_destroy·ga_document_key_shred 대상 + 묘비 표지. */
    static final Map<String, Set<String>> ERASABLE = Map.of(
            "disclosure", Set.of("void_reason_text", "supersede_reason_text", "policy_no", "destroyed_at", "destroyed_by"),
            "recommendation", Set.of("reason_text"),
            "review", Set.of("reason"),
            "signature", Set.of("device", "ip", "view_evidence"),
            "sign_session", Set.of("view_evidence"),
            "compliance_flag", Set.of("policy_no"),
            "document_key", Set.of("wrapped_dek", "shredded_at", "shredded_by"),
            "legal_hold", Set.of("reason_text"),
            "document_artifact", Set.of(),
            "signature_evidence", Set.of());

    final RetentionSetup r = new RetentionSetup();

    @AfterEach
    void close() {
        r.close();
    }

    List<JsonNode> rows(String table, DisclosureId id) {
        try (Connection c = r.x.w.db.superuserDataSource().getConnection();
             var ps = c.prepareStatement("SELECT to_jsonb(t)::text FROM " + table
                     + " t WHERE t.tenant_id = ? AND t.disclosure_id = ? ORDER BY (to_jsonb(t) - ?::text[])::text")) {
            ps.setString(1, r.x.w.tenant.value());
            ps.setObject(2, id.value());
            ps.setArray(3, c.createArrayOf("text", ERASABLE.get(table).toArray()));   // 지워지는 컬럼은 정렬 키에서 뺀다
            List<JsonNode> out = new ArrayList<>();
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(Canonicalizer.parseStrict(rs.getString(1)));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    TenantVerifier verifier() {
        return new TenantVerifier(r.x.w.audit, r.chain(), new AnchorRepository(r.x.w.gateway), r.x.s.records, r.x.s.cipher, r.x.s.bucket,
                new RuleResolver(r.x.w.rules), r.x.w.flags, r.x.w.tx, RetentionSetup.at(RetentionSetup.AFTER));
    }

    @Test
    void onlyTheDesignatedColumnsBecomeNullAndTheAuditHashesMatchTheErasedValues() {
        DisclosureId id = r.completed();
        r.reconcileAfterRetention();
        var holds = r.holdService(RetentionSetup.at(RetentionSetup.AFTER));         // 해제된 보류의 사유 텍스트도 지운다(V11)
        var placed = holds.place(r.x.w.tenant, RetentionSetup.OPERATOR, new com.ga.disclosure.workflow.retention.LegalHoldService.Target.Disclosure(id),
                "OTHER", "가상 분쟁 메모 — 허구");
        holds.release(r.x.w.tenant, RetentionSetup.RELEASER, placed.holdId(), "CASE_CLOSED");
        Map<String, List<JsonNode>> before = new java.util.TreeMap<>();
        ERASABLE.keySet().forEach(t -> before.put(t, rows(t, id)));
        Set<String> expected = expectedErasure(before);
        assertThat(expected).as("서명·세션·키·보류가 지울 값을 가졌다").anyMatch(e -> e.startsWith("legal_hold.")).hasSizeGreaterThan(3);

        assertThat(r.destroy().destroyed()).hasSize(1);

        for (String table : ERASABLE.keySet()) {
            List<JsonNode> after = rows(table, id);
            assertThat(after).as(table + " rows are kept").hasSameSizeAs(before.get(table));
            for (int i = 0; i < after.size(); i++) {
                JsonNode was = before.get(table).get(i);
                JsonNode now = after.get(i);
                for (Iterator<String> f = was.propertyNames().iterator(); f.hasNext(); ) {
                    String column = f.next();
                    if (ERASABLE.get(table).contains(column)) {
                        if (!column.endsWith("_at") && !column.endsWith("_by")) {
                            assertThat(now.get(column).isNull()).as(table + "." + column + " is erased").isTrue();
                        }
                    } else {
                        assertThat(now.get(column)).as(table + "." + column + " is kept").isEqualTo(was.get(column));
                    }
                }
            }
        }
        assertThat(erasedInAudit(id)).isEqualTo(expected);
    }

    @Test
    void afterDestructionTheChainNumberingAndVerifyStillHold() {
        DisclosureId destroyed = r.completed();
        DisclosureId kept = r.completed();                 // 보존 중인 문서와 섞여 있어도
        r.reconcileAfterRetention();
        r.x.w.db.seed(r.x.w.tenant.value(), c -> com.ga.disclosure.infra.testing.SeedData.exec(c,
                "INSERT INTO legal_hold (tenant_id, hold_id, disclosure_id, reason_code, placed_by, placed_at) VALUES (?, gen_random_uuid(), ?, 'LITIGATION', 'ops', now())",
                r.x.w.tenant.value(), kept.value()));
        assertThat(r.destroy().destroyed()).extracting(d -> d.id()).containsExactly(destroyed);

        VerifyReport report = verifier().run(r.x.w.tenant, RetentionSetup.OPERATOR, null);

        assertThat(report.findings()).isEmpty();
        assertThat(report.counts().disclosures()).isEqualTo(2);
    }

    @Test
    void aReappearingObjectAndAnUnauditedDestructionAreFound() {
        DisclosureId destroyed = r.completed();
        DisclosureId other = r.completed();
        r.reconcileAfterRetention();
        r.x.w.db.seed(r.x.w.tenant.value(), c -> com.ga.disclosure.infra.testing.SeedData.exec(c,
                "INSERT INTO legal_hold (tenant_id, hold_id, disclosure_id, reason_code, placed_by, placed_at) VALUES (?, gen_random_uuid(), ?, 'LITIGATION', 'ops', now())",
                r.x.w.tenant.value(), other.value()));
        r.destroy();
        String key = r.x.w.in(() -> r.x.s.records.artifacts(destroyed)).getFirst().storageKey();
        r.x.s.bucket.put(key, "resurrected".getBytes(StandardCharsets.US_ASCII));
        try (Connection c = r.x.w.db.superuserDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var st = c.createStatement()) {
                st.execute("SET LOCAL session_replication_role = replica");
            }
            try (var ps = c.prepareStatement("UPDATE disclosure SET destroyed_at = now(), destroyed_by = 'nobody' WHERE tenant_id = ? AND disclosure_id = ?")) {
                ps.setString(1, r.x.w.tenant.value());
                ps.setObject(2, other.value());
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }

        VerifyReport report = verifier().run(r.x.w.tenant, RetentionSetup.OPERATOR, null);

        assertThat(report.findings()).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo(FindingCode.OBJECT_NOT_DELETED);
            assertThat(f.where()).containsEntry("storageKey", key);
        });
        assertThat(report.findings()).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo(FindingCode.DESTRUCTION_UNAUDITED);
            assertThat(f.where()).containsEntry("disclosureId", other.toString());
        });
    }

    /** 파기 전 행에서 감사 표현을 따로 계산한다(5 계획 §5.5). */
    private Set<String> expectedErasure(Map<String, List<JsonNode>> before) {
        Set<String> out = new HashSet<>();
        for (JsonNode k : before.get("document_key")) {
            if (!k.get("wrapped_dek").isNull()) {
                out.add("document_key.wrapped_dek=sha256-stored:" + Sha256.of(bytea(k.get("wrapped_dek").asString())));
            }
        }
        for (JsonNode d : before.get("disclosure")) {
            for (String col : List.of("void_reason_text", "supersede_reason_text", "policy_no")) {
                if (!d.get(col).isNull()) {
                    out.add("disclosure." + col + "=sha256-utf8:" + utf8(d.get(col).asString()));
                }
            }
        }
        before.get("recommendation").stream().filter(x -> !x.get("reason_text").isNull())
                .forEach(x -> out.add("recommendation.reason_text=sha256-utf8:" + utf8(x.get("reason_text").asString())));
        before.get("review").stream().filter(x -> !x.get("reason").isNull())
                .forEach(x -> out.add("review.reason=sha256-utf8:" + utf8(x.get("reason").asString())));
        for (JsonNode s : before.get("signature")) {
            if (!s.get("device").isNull()) {
                out.add("signature.device=presence:present");
            }
            if (!s.get("ip").isNull()) {
                out.add("signature.ip=presence-family:" + (s.get("ip").asString().contains(":") ? "6" : "4"));
            }
            if (!s.get("view_evidence").isNull()) {
                out.add("signature.view_evidence=sha256-jcs:" + Sha256.of(Canonicalizer.canonicalize(s.get("view_evidence"))));
            }
        }
        before.get("sign_session").stream().filter(x -> !x.get("view_evidence").isNull())
                .forEach(x -> out.add("sign_session.view_evidence=sha256-jcs:" + Sha256.of(Canonicalizer.canonicalize(x.get("view_evidence")))));
        before.get("compliance_flag").stream().filter(x -> !x.get("policy_no").isNull())
                .forEach(x -> out.add("compliance_flag.policy_no=sha256-utf8:" + utf8(x.get("policy_no").asString())));
        before.get("legal_hold").stream().filter(x -> !x.get("reason_text").isNull())
                .forEach(x -> out.add("legal_hold.reason_text=sha256-utf8:" + utf8(x.get("reason_text").asString())));
        return out;
    }

    private Set<String> erasedInAudit(DisclosureId id) {
        Set<String> out = new HashSet<>();
        for (AuditRecord a : r.x.w.in(r.x.w.audit::readAll)) {
            if (!id.toString().equals(a.entry().targetId())
                    || (a.entry().action() != AuditAction.DISCLOSURE_DESTROYED && a.entry().action() != AuditAction.DOCUMENT_KEY_SHREDDED)) {
                continue;
            }
            for (JsonNode e : a.entry().detail().get("erased")) {
                out.add(e.get("table").asString() + "." + e.get("column").asString() + "=" + e.get("repr").asString() + ":" + e.get("value").asString());
            }
        }
        return out;
    }

    private static String utf8(String s) {
        return Sha256.of(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] bytea(String jsonHex) {
        return HexFormat.of().parseHex(jsonHex.substring(2));                 // to_jsonb(bytea) = "\\x…"
    }
}
