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
 * 나머지 8개 상태 전부 거부; signature·audit_log·document_artifact·anchor(V9, V1 audit_anchor 대체) UPDATE·DELETE 거부.
 * V7(3B): document_artifact는 Object Lock 적용 기록({@code retention_applied_at} NULL→값 1회)만 UPDATE를 허용한다 — 애플리케이션 롤은 그
 * 컬럼만 UPDATE 권한이 있고(나머지 42501), 소유 롤의 다른 변경은 GD093, 삭제는 여전히 GD030.
 * V8(Phase 4): 서명은 PDF 해시도 묶는다(GD102). 애플리케이션 롤은 signature UPDATE·DELETE 권한 자체가 없고(42501), 소유 롤은 트리거가
 * 막는다(GD030). 보존 적용 기록은 {@code retention_applied_until}이 증가 방향으로만 바뀐다.
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
            SeedData.anchorReceipt(c, T);
        });
    }

    private static UUID seedDisclosure(String status) {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> id[0] = SeedData.disclosure(c, T, status, CANONICAL));
        return id[0];
    }

    private static final String INSERT_SIGNATURE = """
            INSERT INTO signature (tenant_id, signature_id, disclosure_id, signer_role, signer_subject, channel, method, signed_doc_hash,
                                   signed_pdf_hash, identity_check, signed_at)
            VALUES (?, gen_random_uuid(), ?, 'AGENT', 'agent@seed', 'SSO', 'DRAWN', ?, ?, '[]'::jsonb, TIMESTAMPTZ '2026-09-23 10:06:00+09')
            """;

    @ParameterizedTest
    @FieldSource("STATUSES")
    void signatureInsertAllowedOnlyForSealedOrPartiallySigned(String status) {
        UUID id = seedDisclosure(status);
        if (status.equals("SEALED") || status.equals("PARTIALLY_SIGNED")) {
            int inserted = DB.asApp(T, c -> SeedData.exec(c, INSERT_SIGNATURE, T, id, CANONICAL, SeedData.PDF_HASH));
            assertThat(inserted).isEqualTo(1);
        } else {
            // 봉인 전 상태는 canonical_hash가 NULL이지만, 상태 검사가 먼저 거부한다(GD021).
            assertRejected(DB, T, "GD021", INSERT_SIGNATURE, T, id, CANONICAL, SeedData.PDF_HASH);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEALED", "PARTIALLY_SIGNED"})
    void signatureWithDifferentDocumentHashRejected(String status) {
        UUID id = seedDisclosure(status);
        assertRejected(DB, T, "GD022", INSERT_SIGNATURE, T, id, SeedData.hash('d'), SeedData.PDF_HASH);
        assertRejected(DB, T, "GD022", INSERT_SIGNATURE, T, id, CANONICAL.toUpperCase(), SeedData.PDF_HASH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SEALED", "PARTIALLY_SIGNED"})
    void signatureWithDifferentPdfHashRejected(String status) {
        UUID id = seedDisclosure(status);
        assertRejected(DB, T, "GD102", INSERT_SIGNATURE, T, id, CANONICAL, SeedData.hash('e'));
        assertRejected(DB, T, "GD102", INSERT_SIGNATURE, T, id, CANONICAL, SeedData.PDF_HASH.toUpperCase());
    }

    @Test
    void signatureHashOfAnotherVersionRejected() {
        // v1(SUPERSEDED)의 해시로 v2(SEALED)에 서명할 수 없다.
        UUID[] ids = new UUID[2];
        DB.seed(T, c -> {
            ids[0] = SeedData.disclosure(c, T, "SUPERSEDED", SeedData.hash('1'));
            ids[1] = SeedData.disclosure(c, T, "SEALED", SeedData.hash('2'));
        });
        assertRejected(DB, T, "GD022", INSERT_SIGNATURE, T, ids[1], SeedData.hash('1'), SeedData.PDF_HASH);
    }

    @Test
    void signatureForMissingDisclosureRejected() {
        assertRejected(DB, T, "GD020", INSERT_SIGNATURE, T, UUID.randomUUID(), CANONICAL, SeedData.PDF_HASH);
    }

    static final List<String> SIGNATURE_CHANGES = List.of(
            "UPDATE signature SET signed_doc_hash = repeat('f', 64) WHERE tenant_id = ?",
            "UPDATE signature SET signed_at = signed_at WHERE tenant_id = ?",
            "DELETE FROM signature WHERE tenant_id = ?");

    /** V8: 애플리케이션 롤은 권한이 없고(42501), 소유 롤은 트리거가 막는다(GD030). */
    @ParameterizedTest
    @FieldSource("SIGNATURE_CHANGES")
    void signatureRowsCannotChange(String sql) {
        assertRejected(DB, T, "42501", sql, T);
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, sql, T)))).isEqualTo("GD030");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE audit_log SET detail = '{}'::jsonb WHERE tenant_id = ?",
            "DELETE FROM audit_log WHERE tenant_id = ?",
    })
    void appendOnlyTablesRejectUpdateAndDelete(String sql) {
        assertRejected(DB, T, "GD030", sql, T);
    }

    /** V9: 앵커·영수증은 애플리케이션 롤에 삽입·조회만 있고(42501), 소유 롤은 트리거가 막는다(GD030). */
    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE anchor SET leaf_hash = repeat('0', 64) WHERE tenant_id = ?",
            "DELETE FROM anchor WHERE tenant_id = ?",
            "UPDATE anchor_receipt SET root_hash = repeat('0', 64) WHERE tenant_id = ?",
            "DELETE FROM anchor_receipt WHERE tenant_id = ?",
    })
    void anchorsAndReceiptsCannotChange(String sql) {
        assertRejected(DB, T, "42501", sql, T);
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, sql, T)))).isEqualTo("GD030");
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
        // 첫 적용 시각은 NULL → 값 한 번만, 적용 기한은 함께 쓰고(V8 CHECK) 이후 증가 방향으로만
        String record = "UPDATE document_artifact SET retention_applied_at = TIMESTAMPTZ '2026-09-23 10:00:05+09', "
                + "retention_applied_until = DATE '2031-09-23'" + where;
        assertRejected(DB, T, "GD093", "UPDATE document_artifact SET retention_applied_at = NULL" + where, T, id[0]);
        // 기한 없는 적용 기록: 트리거(BEFORE)가 CHECK보다 먼저 거부한다
        assertRejected(DB, T, "GD093", "UPDATE document_artifact SET retention_applied_at = TIMESTAMPTZ '2026-09-23 10:00:05+09'" + where,
                T, id[0]);
        DB.asAppCommitting(T, c -> SeedData.exec(c, record, T, id[0]));
        assertRejected(DB, T, "GD093", record, T, id[0]);
        assertRejected(DB, T, "GD093", "UPDATE document_artifact SET retention_applied_at = NULL, retention_applied_until = NULL" + where,
                T, id[0]);
        assertRejected(DB, T, "GD093", "UPDATE document_artifact SET retention_applied_at = TIMESTAMPTZ '2026-09-24 10:00:05+09', "
                + "retention_applied_until = DATE '2036-09-23'" + where, T, id[0]);
        assertRejected(DB, T, "GD093", "UPDATE document_artifact SET retention_applied_until = DATE '2031-09-22'" + where, T, id[0]);
        // 완료 때 보존기한이 연장되면 기한만 늘린다
        DB.asAppCommitting(T, c -> SeedData.exec(c, "UPDATE document_artifact SET retention_applied_until = DATE '2036-09-23'" + where, T, id[0]));
        assertRejected(DB, T, "GD093", "UPDATE document_artifact SET retention_applied_until = DATE '2031-09-23'" + where, T, id[0]);
    }

    @ParameterizedTest
    @ValueSource(strings = {"signature", "audit_log", "document_artifact", "anchor", "anchor_receipt", "disclosure", "disclosure_item", "recommendation"})
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
