package com.ga.disclosure.audit;

import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 감사 체인 정의: 정규화 엔트리·해시식·체인 시작·재계산 검출. */
class AuditChainTest {

    private static final TenantId T = TenantId.of("T1");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static AuditEntry entry(String target, String detail) {
        return new AuditEntry(Instant.parse("2026-09-29T01:02:03.123456789Z"), "ops@example", "OPERATOR", AuditAction.RULE_DISTRIBUTE,
                "RULE_VERSION", target, JSON.readTree(detail));
    }

    @Test
    void canonicalEntryIsJcsWithMicrosecondInstant() {
        String canonical = new String(AuditChain.canonicalEntry(T, 7, entry("DISC-2026-07", "{\"outcome\":\"INSERTED\",\"a\":1}")),
                StandardCharsets.UTF_8);
        assertThat(canonical).isEqualTo("{\"action\":\"RULE_DISTRIBUTE\",\"actorRole\":\"OPERATOR\",\"actorSubject\":\"ops@example\","
                + "\"at\":\"2026-09-29T01:02:03.123456Z\",\"detail\":{\"a\":1,\"outcome\":\"INSERTED\"},\"seq\":7,"
                + "\"targetId\":\"DISC-2026-07\",\"targetKind\":\"RULE_VERSION\",\"tenantId\":\"T1\"}");
    }

    @Test
    void firstEntryLinksToGenesisAndHashIsPrevConcatCanonical() {
        AuditEntry e = entry("DISC-2026-07", "{}");
        AuditRecord first = AuditChain.next(T, null, e);
        assertThat(first.seq()).isEqualTo(1);
        assertThat(first.prevHash()).isEqualTo("0".repeat(64));
        String expected = Sha256.of((AuditChain.GENESIS + new String(AuditChain.canonicalEntry(T, 1, e), StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8));
        assertThat(first.entryHash()).isEqualTo(expected);
    }

    @Test
    void recomputationFindsGapsBrokenLinksAndEditedContent() {
        List<AuditRecord> chain = new ArrayList<>();
        AuditRecord prev = null;
        for (int i = 0; i < 5; i++) {
            prev = AuditChain.next(T, prev, entry("R-" + i, "{\"i\":" + i + "}"));
            chain.add(prev);
        }
        assertThat(AuditChain.breaks(chain)).isEmpty();

        List<AuditRecord> edited = new ArrayList<>(chain);
        AuditRecord third = edited.get(2);
        edited.set(2, new AuditRecord(T, 3, entry("R-2", "{\"i\":99}"), third.prevHash(), third.entryHash()));
        assertThat(AuditChain.breaks(edited)).containsExactly("seq 3 entryHash does not match its content");

        List<AuditRecord> gap = new ArrayList<>(chain);
        gap.remove(1);
        assertThat(AuditChain.breaks(gap)).contains("seq 3 where 2 was expected", "seq 3 prevHash does not link to the previous entry");
    }
}
