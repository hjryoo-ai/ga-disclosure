package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static com.ga.disclosure.infra.TriggerAssertions.assertRejected;
import static com.ga.disclosure.infra.TriggerAssertions.sqlStateOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * C8: signed_doc_hash ≠ canonical_hash 인 signature INSERT 거부; 부모가 SEALED·PARTIALLY_SIGNED일 때만 INSERT 허용,
 * 나머지 8개 상태 전부 거부; signature·audit_log·document_artifact·audit_anchor UPDATE·DELETE 거부.
 * V7(3B): document_artifact는 Object Lock 적용 기록({@code retention_applied_at} NULL→값 1회)만 UPDATE를 허용한다 — 애플리케이션 롤은 그
 * 컬럼만 UPDATE 권한이 있고(나머지 42501), 소유 롤의 다른 변경은 GD093, 삭제는 여전히 GD030.
 */
class AppendOnlyTriggerIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("APP");
    private static final String CANONICAL = SeedData.hash('a');

    static final List<String> STATUSES = SeedData.ALL_STATUSES;

    private static UUID sealedWithSignature;

    @BeforeAll
    static void seed() {
        DB.seed(T, c -> {
            SeedData.tenant(c, T);
            sealedWithSignature = SeedData.disclosure(c, T, "SEALED", CANONICAL);
            SeedData.signature(c, T, sealedWithSignature, "CUSTOMER", CANONICAL);
            SeedData.artifact(c, T, sealedWithSignature, "PDF");
            SeedData.auditLog(c, T, 1);
            SeedData.anchor(c, T);
        });
    }

    private static UUID seedDisclosure(String status) {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> id[0] = SeedData.disclosure(c, T, status, CANONICAL));
        return id[0];
    }

    private static final String INSERT_SIGNATURE = """
            INSERT INTO signature (tenant_id, signature_id, disclosure_id, signer_role, channel, method, signed_doc_hash,
                                   evidence_key, evidence_hash, signed_at)
            VALUES (?, gen_random_uuid(), ?, 'AGENT', 'TOUCH_PAD', 'DRAWN', ?, 'evidence/key', ?, TIMESTAMPTZ '2026-09-23 10:06:00+09')
            """;

    @ParameterizedTest
    @FieldSource("STATUSES")
    void signatureInsertAllowedOnlyForSealedOrPartiallySigned(String status) {
        UUID id = seedDisclosure(status);
        if (status.equals("SEALED") || status.equals("PARTIALLY_SIGNED")) {
            int inserted = DB.asApp(T, c -> SeedData.exec(c, INSERT_SIGNATURE, T, id, CANONICAL, SeedData.hash('e')));
            assertThat(inserted).isEqualTo(1);
        } else {
            // 봉인 전 상태는 canonical_hash가 NULL이지만, 상태 검사가 먼저 거부한다(GD021).
            assertRejected(DB, T, "GD021", INSERT_SIGNATURE, T, id, CANONICAL, SeedData.hash('e'));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEALED", "PARTIALLY_SIGNED"})
    void signatureWithDifferentDocumentHashRejected(String status) {
        UUID id = seedDisclosure(status);
        assertRejected(DB, T, "GD022", INSERT_SIGNATURE, T, id, SeedData.hash('d'), SeedData.hash('e'));
        assertRejected(DB, T, "GD022", INSERT_SIGNATURE, T, id, CANONICAL.toUpperCase(), SeedData.hash('e'));
    }

    @Test
    void signatureHashOfAnotherVersionRejected() {
        // v1(SUPERSEDED)의 해시로 v2(SEALED)에 서명할 수 없다.
        UUID[] ids = new UUID[2];
        DB.seed(T, c -> {
            ids[0] = SeedData.disclosure(c, T, "SUPERSEDED", SeedData.hash('1'));
            ids[1] = SeedData.disclosure(c, T, "SEALED", SeedData.hash('2'));
        });
        assertRejected(DB, T, "GD022", INSERT_SIGNATURE, T, ids[1], SeedData.hash('1'), SeedData.hash('e'));
    }

    @Test
    void signatureForMissingDisclosureRejected() {
        assertRejected(DB, T, "GD020", INSERT_SIGNATURE, T, UUID.randomUUID(), CANONICAL, SeedData.hash('e'));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE signature SET signed_doc_hash = repeat('f', 64) WHERE tenant_id = ?",
            "UPDATE signature SET signed_at = signed_at WHERE tenant_id = ?",
            "DELETE FROM signature WHERE tenant_id = ?",
            "UPDATE audit_log SET detail = '{}'::jsonb WHERE tenant_id = ?",
            "DELETE FROM audit_log WHERE tenant_id = ?",
            "UPDATE audit_anchor SET head_hash = repeat('0', 64) WHERE tenant_id = ?",
            "DELETE FROM audit_anchor WHERE tenant_id = ?",
    })
    void appendOnlyTablesRejectUpdateAndDelete(String sql) {
        assertRejected(DB, T, "GD030", sql, T);
    }

    @Test
    void documentArtifactChangesOnlyByRecordingRetentionOnce() {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> {
            id[0] = SeedData.disclosure(c, T, "SEALED", SeedData.hash('5'));
            SeedData.artifact(c, T, id[0], "PDF");
        });
        String where = " WHERE tenant_id = ? AND disclosure_id = ?";
        // 애플리케이션 롤: retention_applied_at 외 UPDATE·DELETE 권한이 없다
        assertRejected(DB, T, "42501", "UPDATE document_artifact SET sha256 = repeat('0', 64)" + where, T, id[0]);
        assertRejected(DB, T, "42501", "DELETE FROM document_artifact" + where, T, id[0]);
        // 소유 롤도 트리거가 막는다
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, "UPDATE document_artifact SET sha256 = repeat('0', 64)" + where, T, id[0]))))
                .isEqualTo("GD093");
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, "DELETE FROM document_artifact" + where, T, id[0]))))
                .isEqualTo("GD030");
        // 적용 기록은 NULL → 값 한 번만
        String record = "UPDATE document_artifact SET retention_applied_at = TIMESTAMPTZ '2026-09-23 10:00:05+09'" + where;
        assertRejected(DB, T, "GD093", "UPDATE document_artifact SET retention_applied_at = NULL" + where, T, id[0]);
        DB.asAppCommitting(T, c -> SeedData.exec(c, record, T, id[0]));
        assertRejected(DB, T, "GD093", record, T, id[0]);
        assertRejected(DB, T, "GD093", "UPDATE document_artifact SET retention_applied_at = NULL" + where, T, id[0]);
    }

    @ParameterizedTest
    @ValueSource(strings = {"signature", "audit_log", "document_artifact", "audit_anchor", "disclosure", "disclosure_item", "recommendation"})
    void ownerCannotTruncateOrRewriteHistoryEither(String table) {
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, "TRUNCATE " + table + " CASCADE"))))
                .isEqualTo("GD030");
    }

    @Test
    void ownerCannotUpdateAppendOnlyRows() {
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c,
                "UPDATE signature SET method = 'X' WHERE tenant_id = ?", T))))
                .isEqualTo("GD030");
    }
}
