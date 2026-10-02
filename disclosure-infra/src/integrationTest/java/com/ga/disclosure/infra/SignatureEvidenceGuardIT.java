package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static com.ga.disclosure.infra.TriggerAssertions.assertAllowed;
import static com.ga.disclosure.infra.TriggerAssertions.assertRejected;
import static com.ga.disclosure.infra.TriggerAssertions.sqlStateOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V8 GD105(승인 Q2): 서명 증거 객체 1개 = 1행. 서명과 같은 확인서, 그 확인서의 살아 있는 문서 키(3B 수용심사 §3-4 — DEK 재사용),
 * 잠금 적용 전 기록. 이후에는 보존 기록만 바뀐다 — 첫 적용 시각 1회, 적용 기한은 증가만(document_artifact와 같은 규칙). 삭제 없음.
 */
class SignatureEvidenceGuardIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("EVD");

    @BeforeAll
    static void seed() {
        DB.seed(T, c -> SeedData.tenant(c, T));
    }

    /** 봉인 확인서 + 문서 키 + 설계사 서명. [0] = 확인서, [1] = 서명. */
    private static UUID[] signed(char canonical) {
        UUID[] ids = new UUID[2];
        DB.seed(T, c -> {
            ids[0] = SeedData.disclosure(c, T, "SEALED", SeedData.hash(canonical));
            SeedData.documentKey(c, T, ids[0]);
            ids[1] = SeedData.signature(c, T, ids[0], "AGENT", SeedData.hash(canonical));
        });
        return ids;
    }

    private static final String INSERT = """
            INSERT INTO signature_evidence (tenant_id, signature_id, kind, disclosure_id, storage_key, sha256, bytes, cipher_sha256,
                                            cipher_bytes, key_id, created_at%s)
            VALUES (?, ?, ?, ?, ? || '/' || ? || '/SIG/' || ? || '/' || ? || '/' || repeat('c', 64), repeat('d', 64), 10, repeat('c', 64), 39, ?,
                    TIMESTAMPTZ '2026-09-23 10:05:00+09'%s)
            """;

    private static Object[] row(UUID disclosure, UUID signature, String kind, String keyId) {
        return new Object[]{T, signature, kind, disclosure, T, disclosure.toString(), signature.toString(), kind, keyId};
    }

    @ParameterizedTest
    @ValueSource(strings = {"STROKES", "IMAGE", "SCAN"})
    void evidenceOfASignatureUnderItsDocumentKey(String kind) {
        UUID[] s = signed('a');
        assertAllowed(DB, T, INSERT.formatted("", ""), row(s[0], s[1], kind, SeedData.documentKeyId(s[0])));
    }

    @Test
    void signatureOfAnotherDisclosureRejected() {
        UUID[] a = signed('a');
        UUID[] b = signed('b');
        // 서명 b를 확인서 a의 증거로(키는 a의 것) — 저장 키 형식은 맞춘다
        assertRejected(DB, T, "GD105", INSERT.formatted("", ""), row(a[0], b[1], "IMAGE", SeedData.documentKeyId(a[0])));
    }

    @Test
    void keyOfAnotherDisclosureRejected() {
        UUID[] a = signed('a');
        UUID[] b = signed('b');
        assertRejected(DB, T, "GD105", INSERT.formatted("", ""), row(a[0], a[1], "IMAGE", SeedData.documentKeyId(b[0])));
    }

    @Test
    void shreddedKeyRejected() {
        UUID[] a = signed('a');
        DB.seed(T, c -> {
            try (var ps = c.prepareStatement("SELECT ga_shred_document_key(?, ?, TIMESTAMPTZ '2031-09-24 00:00:00+09', 'RETENTION:test')")) {
                ps.setString(1, T);
                ps.setObject(2, a[0]);
                ps.execute();
            }
        });
        assertRejected(DB, T, "GD105", INSERT.formatted("", ""), row(a[0], a[1], "IMAGE", SeedData.documentKeyId(a[0])));
    }

    @Test
    void recordedBeforeItsLock() {
        UUID[] a = signed('a');
        assertRejected(DB, T, "GD105",
                INSERT.formatted(", retention_applied_at, retention_applied_until", ", TIMESTAMPTZ '2026-09-23 10:05:01+09', DATE '2031-09-23'"),
                row(a[0], a[1], "IMAGE", SeedData.documentKeyId(a[0])));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "kind|'VIDEO'",
            "storage_key|'elsewhere/key'",
            "sha256|'ABC'",
            "cipher_bytes|40",
            "bytes|0"})
    void columnShapes(String spec) {
        String[] p = spec.split("\\|");
        UUID[] a = signed('a');
        String storageKey = "? || '/' || ? || '/SIG/' || ? || '/IMAGE/' || repeat('c', 64)";
        String sql = """
                INSERT INTO signature_evidence (tenant_id, signature_id, kind, disclosure_id, storage_key, sha256, bytes, cipher_sha256,
                                                cipher_bytes, key_id, created_at)
                VALUES (?, ?, %s, ?, %s, %s, %s, repeat('c', 64), %s, ?, TIMESTAMPTZ '2026-09-23 10:05:00+09')
                """.formatted(p[0].equals("kind") ? p[1] : "'IMAGE'",
                p[0].equals("storage_key") ? p[1] + " || substr(? || ? || ?, 1, 0)" : storageKey,
                p[0].equals("sha256") ? p[1] : "repeat('d', 64)",
                p[0].equals("bytes") ? p[1] : "10",
                p[0].equals("cipher_bytes") ? p[1] : "39");
        assertRejected(DB, T, "23514", sql, T, a[1], a[0], T, a[0].toString(), a[1].toString(), SeedData.documentKeyId(a[0]));
    }

    // ------------------------------------------------------------------ 보존 기록

    @Test
    void changesOnlyByRecordingALongerLock() {
        UUID[] a = signed('a');
        DB.seed(T, c -> SeedData.signatureEvidence(c, T, a[0], a[1], "IMAGE"));
        String where = " WHERE tenant_id = ? AND signature_id = ? AND kind = 'IMAGE'";
        // 애플리케이션 롤은 보존 기록 두 컬럼만 UPDATE 권한이 있다
        assertRejected(DB, T, "42501", "UPDATE signature_evidence SET sha256 = repeat('0', 64)" + where, T, a[1]);
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, "UPDATE signature_evidence SET sha256 = repeat('0', 64)" + where, T, a[1]))))
                .isEqualTo("GD105");
        assertRejected(DB, T, "GD105", "UPDATE signature_evidence SET retention_applied_at = TIMESTAMPTZ '2026-09-23 10:05:01+09'" + where, T, a[1]);
        String record = "UPDATE signature_evidence SET retention_applied_at = TIMESTAMPTZ '2026-09-23 10:05:01+09', "
                + "retention_applied_until = DATE '2031-09-23'" + where;
        DB.asAppCommitting(T, c -> SeedData.exec(c, record, T, a[1]));
        assertRejected(DB, T, "GD105", record, T, a[1]);
        assertRejected(DB, T, "GD105", "UPDATE signature_evidence SET retention_applied_until = DATE '2031-09-22'" + where, T, a[1]);
        assertRejected(DB, T, "GD105", "UPDATE signature_evidence SET retention_applied_at = TIMESTAMPTZ '2026-09-24 10:05:01+09', "
                + "retention_applied_until = DATE '2036-09-23'" + where, T, a[1]);
        assertRejected(DB, T, "GD105", "UPDATE signature_evidence SET retention_applied_at = NULL, retention_applied_until = NULL" + where, T, a[1]);
        assertAllowed(DB, T, "UPDATE signature_evidence SET retention_applied_until = DATE '2036-09-23'" + where, T, a[1]);
    }

    @Test
    void evidenceIsNeverRemoved() {
        UUID[] a = signed('a');
        DB.seed(T, c -> SeedData.signatureEvidence(c, T, a[0], a[1], "STROKES"));
        String delete = "DELETE FROM signature_evidence WHERE tenant_id = ? AND signature_id = ?";
        assertRejected(DB, T, "42501", delete, T, a[1]);
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, delete, T, a[1])))).isEqualTo("GD105");
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, "TRUNCATE signature_evidence")))).isEqualTo("GD105");
    }
}
