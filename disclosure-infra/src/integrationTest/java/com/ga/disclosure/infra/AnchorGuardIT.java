package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static com.ga.disclosure.infra.TriggerAssertions.assertAllowed;
import static com.ga.disclosure.infra.TriggerAssertions.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V9 앵커·영수증의 DB 재계산(5 계획 §1.1·§1.2·§2): 앵커는 테넌트별 무간격 seq, 날짜·봉인 위치는 앞으로만, 두 머리는 실재 값, 잎은
 * SHA-256(0x00 ‖ JCS(레코드))를 DB가 다시 계산한다(GD110). 영수증은 잎 + 경로로 다시 계산한 루트가 맞아야 한다(GD111). 이 테스트의 잎·루트
 * 계산은 SQL과 독립인 Java 식이다(JCS 바이트를 직접 만든다).
 */
class AnchorGuardIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("ANC");
    private static final String ZERO = "0".repeat(64);
    private static final HexFormat HEX = HexFormat.of();
    private static String sealHead2;

    @BeforeAll
    static void seed() {
        DB.seed(T, c -> {
            SeedData.tenant(c, T);
            SeedData.disclosure(c, T, "SEALED", SeedData.hash('a'));
            SeedData.disclosure(c, T, "SEALED", SeedData.hash('b'));
            for (long seq = 1; seq <= 3; seq++) {
                SeedData.auditLog(c, T, seq);
            }
        });
        sealHead2 = DB.asApp(T, c -> SeedData.call(c, "SELECT chain_hash FROM disclosure WHERE tenant_id = ? AND chain_seq = 2", T));
    }

    static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) {
                md.update(p);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 키 정렬(UTF-16) 그대로 쓴 JCS 바이트 — 값은 패턴 제한 문자·정수·hex뿐이라 이스케이프가 없다. */
    static String leaf(String tenant, long seq, String date, long sealSeq, String sealHead, long auditSeq, String auditHead) {
        String jcs = "{\"anchorDate\":\"" + date + "\",\"anchorSeq\":" + seq + ",\"auditHead\":\"" + auditHead + "\",\"auditSeq\":" + auditSeq
                + ",\"sealChainHead\":\"" + sealHead + "\",\"sealChainSeq\":" + sealSeq + ",\"tenantId\":\"" + tenant + "\",\"v\":1}";
        return HEX.formatHex(sha256(new byte[] {0}, jcs.getBytes(StandardCharsets.UTF_8)));
    }

    private static final String INSERT = """
            INSERT INTO anchor (tenant_id, anchor_seq, anchor_date, seal_chain_seq, seal_chain_head, audit_seq, audit_head, leaf_hash, created_at)
            VALUES (?, ?, CAST(? AS date), ?, ?, ?, ?, ?, now())
            """;

    private static Object[] row(long seq, String date, long sealSeq, String sealHead, long auditSeq, String auditHead, String leaf) {
        return new Object[] {T, seq, date, sealSeq, sealHead, auditSeq, auditHead, leaf};
    }

    private static Object[] valid(long seq, String date, long sealSeq, String sealHead, long auditSeq) {
        String auditHead = auditSeq == 0 ? ZERO : SeedData.hash('1');
        return row(seq, date, sealSeq, sealHead, auditSeq, auditHead, leaf(T, seq, date, sealSeq, sealHead, auditSeq, auditHead));
    }

    @Test
    void anchorsFollowTheChainsAndTheLeafFormula() {
        // 롤백되는 문장: 각 거부가 서로 독립이도록 첫 앵커가 없는 상태에서 판정한다
        assertRejected(DB, T, "GD110", INSERT, valid(2, "2026-10-01", 0, ZERO, 0));                          // 첫 seq는 1
        assertRejected(DB, T, "GD110", INSERT, valid(1, "2026-10-01", 2, SeedData.hash('9'), 0));            // 봉인 머리 불일치
        assertRejected(DB, T, "GD110", INSERT, valid(1, "2026-10-01", 7, SeedData.hash('9'), 0));            // 없는 chain_seq
        assertRejected(DB, T, "GD110", INSERT, row(1, "2026-10-01", 0, ZERO, 3, SeedData.hash('8'),
                leaf(T, 1, "2026-10-01", 0, ZERO, 3, SeedData.hash('8'))));                                       // 감사 머리 불일치
        String jcsOnly = HEX.formatHex(sha256(("{\"anchorDate\":\"2026-10-01\",\"anchorSeq\":1,\"auditHead\":\"" + ZERO
                + "\",\"auditSeq\":0,\"sealChainHead\":\"" + ZERO + "\",\"sealChainSeq\":0,\"tenantId\":\"" + T + "\",\"v\":1}")
                .getBytes(StandardCharsets.UTF_8)));
        assertRejected(DB, T, "GD110", INSERT, row(1, "2026-10-01", 0, ZERO, 0, ZERO, jcsOnly));              // 도메인 접두 없는 잎
        assertRejected(DB, T, "GD110", INSERT, row(1, "2026-10-01", 0, ZERO, 0, ZERO,
                leaf(T, 1, "2026-10-02", 0, ZERO, 0, ZERO)));                                                     // 다른 날짜의 잎
        assertAllowed(DB, T, INSERT, valid(1, "2026-10-01", 2, sealHead2, 3));

        DB.asAppCommitting(T, c -> SeedData.exec(c, INSERT, valid(1, "2026-10-01", 1,
                DB.asApp(T, x -> SeedData.call(x, "SELECT chain_hash FROM disclosure WHERE tenant_id = ? AND chain_seq = 1", T)), 2)));
        assertRejected(DB, T, "GD110", INSERT, valid(3, "2026-10-02", 2, sealHead2, 3));                      // 간격
        assertRejected(DB, T, "GD110", INSERT, valid(2, "2026-10-01", 2, sealHead2, 3));                      // 같은 날짜(유일보다 트리거가 먼저)
        assertRejected(DB, T, "GD110", INSERT, valid(2, "2026-09-30", 2, sealHead2, 3));                      // 날짜 역행
        assertRejected(DB, T, "GD110", INSERT, valid(2, "2026-10-02", 0, ZERO, 3));                           // 봉인 위치 역행
        assertAllowed(DB, T, INSERT, valid(2, "2026-10-02", 2, sealHead2, 3));
    }

    // ------------------------------------------------------------------ 영수증

    private static final String RECEIPT = """
            INSERT INTO anchor_receipt (tenant_id, anchor_seq, batch_id, root_hash, tree_depth, leaf_index, merkle_path, tsa_token,
                                        tsa_gen_time, tsa_policy_oid, tsa_serial, created_at)
            VALUES (?, 1, gen_random_uuid(), ?, ?, ?, CAST(? AS jsonb), decode('3000', 'hex'), now(), '1.2.3.4', '0a', now())
            """;

    static String root(String leafHex, int index, List<String> path) {
        byte[] node = HEX.parseHex(leafHex);
        for (int i = 0; i < path.size(); i++) {
            byte[] sib = HEX.parseHex(path.get(i));
            node = ((index >> i) & 1) == 0 ? sha256(new byte[] {1}, node, sib) : sha256(new byte[] {1}, sib, node);
        }
        return HEX.formatHex(node);
    }

    private static String json(List<String> path) {
        return "[" + String.join(",", path.stream().map(h -> "\"" + h + "\"").toList()) + "]";
    }

    @Test
    void receiptsMustReachTheRootFromTheirLeaf() {
        String t = SeedData.uniqueTenant("ANR");
        DB.seed(t, c -> {
            SeedData.tenant(c, t);
            SeedData.auditLog(c, t, 1);
            SeedData.anchor(c, t);
        });
        String leaf = DB.asApp(t, c -> SeedData.call(c, "SELECT leaf_hash FROM anchor WHERE tenant_id = ? AND anchor_seq = 1", t));
        List<String> path = List.of(SeedData.hash('5'), SeedData.hash('6'), SeedData.hash('7'));
        String good = root(leaf, 5, path);
        assertRejected(DB, t, "GD111", RECEIPT, t, good, (short) 3, 4, json(path));                      // 방향 비트가 다르면 다른 루트
        assertRejected(DB, t, "GD111", RECEIPT, t, SeedData.hash('e'), (short) 3, 5, json(path));        // 루트 위조
        assertRejected(DB, t, "GD111", RECEIPT, t, good, (short) 4, 5, json(path));                      // 경로 길이 ≠ 깊이
        assertRejected(DB, t, "GD111", RECEIPT, t, good, (short) 3, 8, json(path));                      // 색인 ≥ 2^깊이
        assertRejected(DB, t, "GD111", RECEIPT, t, good, (short) 3, 5,
                json(List.of(SeedData.hash('5'), "XYZ", SeedData.hash('7'))));                             // hex 아님
        assertRejected(DB, t, "GD111", RECEIPT, t, good, (short) 3, 5,
                json(List.of(SeedData.hash('6'), SeedData.hash('5'), SeedData.hash('7'))));                // 형제 순서 바뀜
        DB.asAppCommitting(t, c -> SeedData.exec(c, RECEIPT, t, good, (short) 3, 5, json(path)));
        String stored = DB.asApp(t, c -> SeedData.call(c, "SELECT root_hash FROM anchor_receipt WHERE tenant_id = ?", t));
        assertThat(stored).isEqualTo(good);
        assertRejected(DB, t, "23505", RECEIPT, t, good, (short) 3, 5, json(path));                      // 앵커당 1건
    }
}
