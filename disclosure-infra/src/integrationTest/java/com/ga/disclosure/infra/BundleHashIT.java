package com.ga.disclosure.infra;

import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.domain.vo.TemplateRef;
import com.ga.disclosure.rules.bundle.Bundle;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 C12(DB 쪽): bundle_hash가 ① 번들 파일(파일 텍스트의 body를 JCS) ② DB 저장 컬럼 ③ DB 본문(JSONB)에서 다시 계산한 값의
 * 삼자 일치. JSONB는 키 순서·공백을 원문대로 보존하지 않지만(저장 텍스트는 파일과 다르다) 정규화 해시는 같다 — 그래서 해시 입력은
 * 원문이 아니라 JCS다.
 */
class BundleHashIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Governance g = new Governance();

    @ParameterizedTest
    @ValueSource(strings = {Bundles.DISC_2026_07, Bundles.STANDARD_V1})
    void fileDatabaseColumnAndRecomputationAgree(String relative) {
        TenantId t = g.freshTenant("HSH");
        Bundle bundle = Bundles.load(relative);
        g.distribution.distribute(bundle, t, Governance.OPERATOR);

        JsonNode fileBody = JSON.readTree(Bundles.text(relative)).get("body");
        String fromFile = Sha256.of(Canonicalizer.canonicalize(fileBody));
        String storedColumn;
        JsonNode dbBody;
        String dbText;
        if (relative.startsWith("rules/")) {
            var rule = g.in(t, () -> g.rules.find(RuleVersionId.of("DISC-2026-07"))).orElseThrow();
            storedColumn = rule.bundleHash();
            dbBody = rule.body();
            dbText = g.db.<String>asApp(t.value(), c -> text(c, "SELECT body::text FROM rule_version WHERE tenant_id = ?", t.value()));
        } else {
            var template = g.in(t, () -> g.templates.find(TemplateRef.of("STANDARD", 1))).orElseThrow();
            storedColumn = template.bundleHash();
            dbBody = template.body();
            dbText = g.db.<String>asApp(t.value(), c -> text(c, "SELECT fields::text FROM form_template WHERE tenant_id = ?", t.value()));
        }
        String recomputed = Sha256.of(Canonicalizer.canonicalize(dbBody));

        assertThat(fromFile).isEqualTo(bundle.bodyHash()).isEqualTo(storedColumn).isEqualTo(recomputed);
        assertThat(bundle.bundleId()).endsWith("@" + fromFile.substring(0, 12));
        assertThat(dbText).as("JSONB text is not the file text").isNotEqualTo(fileBody.toString());
    }

    private static String text(java.sql.Connection c, String sql, String tenant) throws java.sql.SQLException {
        try (var ps = c.prepareStatement(sql)) {
            ps.setString(1, tenant);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}
