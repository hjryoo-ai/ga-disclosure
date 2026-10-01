package com.ga.disclosure.infra;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3B S10: 봉인 체인 — 테넌트별 {@code chain_seq} 1..n 갭 0, {@code chain_hash}를 저장된 canonical·PDF 해시로 처음부터 재계산하면 모두 일치(첫 prev =
 * '0'×64), 체인 머리 = 마지막 고리, 다른 테넌트의 체인은 독립(각자 1부터).
 */
class SealChainIT {

    private record Link(long seq, String canonical, String pdf, String chain) {
    }

    private static List<Link> chain(SealSetup s) {
        return s.w.db.asApp(s.w.tenant.value(), c -> {
            List<Link> out = new ArrayList<>();
            try (var ps = c.prepareStatement("""
                    SELECT chain_seq, canonical_hash, pdf_hash, chain_hash FROM disclosure
                     WHERE tenant_id = ? AND chain_seq IS NOT NULL ORDER BY chain_seq""")) {
                ps.setString(1, s.w.tenant.value());
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Link(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                    }
                }
            }
            return out;
        });
    }

    private static String sha256(String ascii) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ascii.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void chainRecomputesFromGenesisAndTenantsAreIndependent() throws Exception {
        try (SealSetup a = new SealSetup(); SealSetup b = new SealSetup()) {
            for (int i = 0; i < 5; i++) {
                assertThat(a.sealReasoned().sealed()).isTrue();
            }
            for (int i = 0; i < 2; i++) {
                assertThat(b.sealReasoned().sealed()).isTrue();
            }
            for (SealSetup s : List.of(a, b)) {
                List<Link> links = chain(s);
                String prev = "0".repeat(64);
                for (int i = 0; i < links.size(); i++) {
                    Link l = links.get(i);
                    assertThat(l.seq()).as("갭 없음").isEqualTo(i + 1);
                    assertThat(l.chain()).as("고리 %d 재계산", l.seq()).isEqualTo(sha256(prev + l.canonical() + l.pdf()));
                    prev = l.chain();
                }
                assertThat(s.text("SELECT chain_hash FROM disclosure_chain_head WHERE tenant_id = ?", s.w.tenant.value())).isEqualTo(prev);
            }
            assertThat(chain(a)).hasSize(5);
            assertThat(chain(b)).hasSize(2);
        }
    }
}
