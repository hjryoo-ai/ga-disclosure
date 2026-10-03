package com.ga.disclosure.audit.anchor;

import com.ga.platform.canonical.Sha256;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 잎 = SHA-256(0x00 ‖ JCS(레코드)) — JCS 바이트를 손으로 쓴 문자열과 대조한다(DB V9 ga_anchor_leaf와 같은 바이트, AnchorGuardIT). */
class AnchorRecordTest {

    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);

    @Test
    void theLeafIsThePrefixedHashOfTheFixedKeyJcs() {
        AnchorRecord r = new AnchorRecord(TenantId.of("DEMO1"), 12, LocalDate.parse("2026-10-03"), 311, A, 4087, B);
        String jcs = "{\"anchorDate\":\"2026-10-03\",\"anchorSeq\":12,\"auditHead\":\"" + B + "\",\"auditSeq\":4087,\"sealChainHead\":\"" + A
                + "\",\"sealChainSeq\":311,\"tenantId\":\"DEMO1\",\"v\":1}";
        assertThat(new String(r.canonical(), StandardCharsets.UTF_8)).isEqualTo(jcs);
        byte[] prefixed = MerkleTreeTest.concat(new byte[]{0}, jcs.getBytes(StandardCharsets.UTF_8));
        assertThat(r.leafHash()).isEqualTo(Sha256.of(prefixed));
    }

    @Test
    void recordsAreValidated() {
        assertThatThrownBy(() -> new AnchorRecord(TenantId.of("DEMO1"), 0, LocalDate.parse("2026-10-03"), 0, A, 0, B))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnchorRecord(TenantId.of("DEMO1"), 1, LocalDate.parse("2026-10-03"), 0, "A".repeat(64), 0, B))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
