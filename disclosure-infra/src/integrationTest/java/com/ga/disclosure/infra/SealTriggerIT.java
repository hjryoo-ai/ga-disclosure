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
 * 3B S4·S8: V7 트리거 — 채번 카운터(GD090), 봉인 체인 머리(GD091), 문서 데이터 키와 파기 경로(GD092), 산출물(GD093), 승인의 룰 버전 귀속
 * (GD081). 애플리케이션 롤(disclosure_app)로 시도하고 롤백한다. 소유 롤만 할 수 있는 경로(파기 함수)는 소유 롤로 확인한다.
 */
class SealTriggerIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("V7TR");
    private static UUID sealed;
    private static UUID draft;

    @BeforeAll
    static void seed() {
        DB.seed(T, c -> {
            SeedData.tenant(c, T);
            sealed = SeedData.disclosure(c, T, "SEALED", SeedData.hash('a'));
            SeedData.artifact(c, T, sealed, "PDF");
            draft = SeedData.disclosure(c, T, "REASONED", null);
        });
    }

    // ------------------------------------------------------------------ 카운터

    @Test
    void counterStartsAtOneAndAdvancesByExactlyOne() {
        // 시드 봉인이 (T, 2026) 행을 만들었다(같은 클래스의 다른 테스트가 커밋한 봉인만큼 앞서 있을 수 있다 — 현재 값에서 상대로 본다)
        long current = DB.asApp(T, c -> SeedData.longValue(c, "SELECT seq FROM disclosure_counter WHERE tenant_id = ? AND year = 2026", T));
        assertAllowed(DB, T, "UPDATE disclosure_counter SET seq = seq + 1 WHERE tenant_id = ? AND year = 2026", T);
        assertRejected(DB, T, "GD090", "UPDATE disclosure_counter SET seq = seq + 2 WHERE tenant_id = ? AND year = 2026", T);
        assertRejected(DB, T, "GD090", "UPDATE disclosure_counter SET seq = seq WHERE tenant_id = ? AND year = 2026", T);
        assertRejected(DB, T, "GD090", "UPDATE disclosure_counter SET year = 2027, seq = seq + 1 WHERE tenant_id = ? AND year = 2026", T);
        assertRejected(DB, T, "GD090", "INSERT INTO disclosure_counter (tenant_id, year, seq) VALUES (?, 2030, 5)", T);
        assertAllowed(DB, T, "INSERT INTO disclosure_counter (tenant_id, year, seq) VALUES (?, 2030, 1)", T);
        // 채번 문장 그대로(경합 시 갱신 경로) — +1
        long next = DB.asApp(T, c -> SeedData.longValue(c, """
                INSERT INTO disclosure_counter (tenant_id, year, seq) VALUES (?, 2026, 1)
                ON CONFLICT (tenant_id, year) DO UPDATE SET seq = disclosure_counter.seq + 1 RETURNING seq""", T));
        assertThat(next).isEqualTo(current + 1);
    }

    @Test
    void counterRowsAreNeverRemoved() {
        assertRejected(DB, T, "42501", "DELETE FROM disclosure_counter WHERE tenant_id = ?", T);
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, "DELETE FROM disclosure_counter WHERE tenant_id = ?", T))))
                .isEqualTo("GD090");
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, "TRUNCATE disclosure_counter")))).isEqualTo("GD090");
    }

    // ------------------------------------------------------------------ 체인 머리

    @Test
    void chainHeadAdvancesOnlyToAnExistingSeal() {
        String headHash = DB.asApp(T, c -> {
            try (var ps = c.prepareStatement("SELECT chain_hash FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?")) {
                ps.setString(1, T);
                ps.setObject(2, sealed);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
        // 머리 = 시드 봉인(1). 실재하지 않는 봉인을 가리키거나 +1이 아니면 거부
        assertRejected(DB, T, "GD091", "UPDATE disclosure_chain_head SET chain_seq = 2, chain_hash = repeat('d', 64) WHERE tenant_id = ?", T);
        assertRejected(DB, T, "GD091", "UPDATE disclosure_chain_head SET chain_seq = 3 WHERE tenant_id = ?", T);
        assertRejected(DB, T, "GD091", "UPDATE disclosure_chain_head SET chain_hash = repeat('d', 64) WHERE tenant_id = ?", T);
        assertRejected(DB, T, "GD091", "UPDATE disclosure_chain_head SET chain_hash = ? WHERE tenant_id = ?", headHash, T);   // 제자리 갱신도 없다
        // 다른 테넌트의 머리 INSERT는 1부터, 그것도 실재하는 봉인을 가리켜야 한다
        String other = SeedData.uniqueTenant("V7TR_B");
        DB.seed(other, c -> SeedData.tenant(c, other));
        assertRejected(DB, other, "GD091", "INSERT INTO disclosure_chain_head (tenant_id, chain_seq, chain_hash) VALUES (?, 1, ?)", other,
                headHash);
        assertRejected(DB, other, "GD091", "INSERT INTO disclosure_chain_head (tenant_id, chain_seq, chain_hash) VALUES (?, 2, ?)", other,
                headHash);
        assertRejected(DB, T, "42501", "DELETE FROM disclosure_chain_head WHERE tenant_id = ?", T);
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, "DELETE FROM disclosure_chain_head WHERE tenant_id = ?", T))))
                .isEqualTo("GD091");
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, "TRUNCATE disclosure_chain_head")))).isEqualTo("GD091");
    }

    // ------------------------------------------------------------------ 문서 키

    private static final String INSERT_KEY = """
            INSERT INTO document_key (tenant_id, key_id, disclosure_id, kek_key_id, wrapped_dek, created_at)
            VALUES (?, ?, ?, 'KEK-1', decode('01' || repeat('00', 28), 'hex'), now())""";

    @Test
    void documentKeyOnlyForSealedDisclosuresAndOnePerDocument() {
        assertRejected(DB, T, "GD092", INSERT_KEY, T, SeedData.documentKeyId(draft), draft);
        assertRejected(DB, T, "23505", INSERT_KEY, T, "DOC-" + "e".repeat(32), sealed);            // 문서당 키 1개
        assertRejected(DB, T, "23514", INSERT_KEY, T, "DEK-" + "e".repeat(32), sealed);            // 키 ID 형식
        assertRejected(DB, T, "GD092", """
                INSERT INTO document_key (tenant_id, key_id, disclosure_id, kek_key_id, wrapped_dek, created_at, shredded_at, shredded_by)
                VALUES (?, ?, ?, 'KEK-1', NULL, now(), now(), 'x')""", T, "DOC-" + "f".repeat(32), sealed);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE document_key SET wrapped_dek = NULL, shredded_at = now(), shredded_by = 'app' WHERE tenant_id = ?",
            "UPDATE document_key SET kek_key_id = 'KEK-2' WHERE tenant_id = ?",
            "DELETE FROM document_key WHERE tenant_id = ?"})
    void applicationCannotChangeOrRemoveDocumentKeys(String sql) {
        assertRejected(DB, T, "42501", sql, T);
    }

    @Test
    void ownerChangesKeysOnlyThroughTheShredFunction() {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> {
            id[0] = SeedData.disclosure(c, T, "SEALED", SeedData.hash('7'));
            SeedData.documentKey(c, T, id[0]);
        });
        String where = " WHERE tenant_id = ? AND disclosure_id = ?";
        for (String sql : new String[] {
                "UPDATE document_key SET kek_key_id = 'KEK-2'" + where,
                "UPDATE document_key SET wrapped_dek = decode('01' || repeat('11', 28), 'hex')" + where,
                "DELETE FROM document_key" + where}) {
            assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, sql, T, id[0])))).as(sql).isEqualTo("GD092");
        }
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c, "TRUNCATE document_key CASCADE")))).isIn("GD092", "GD030");
        // 애플리케이션 롤은 파기 함수를 실행할 수 없다(EXECUTE 부여 없음 — Phase 5 파기 배치 전용 롤이 받는다)
        assertRejected(DB, T, "42501", "SELECT ga_shred_document_key(?, ?, now(), 'app')", T, id[0]);
        // 소유 롤: 파기 → 감싼 키 NULL, 시각·주체 기록, 다시 부르면 대상 없음
        String[] shredded = new String[2];
        DB.seed(T, c -> {
            try (var ps = c.prepareStatement("SELECT ga_shred_document_key(?, ?, TIMESTAMPTZ '2031-09-24 00:00:00+09', 'RETENTION:test')")) {
                ps.setString(1, T);
                ps.setObject(2, id[0]);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    shredded[0] = rs.getString(1);
                }
            }
            try (var ps = c.prepareStatement("SELECT ga_shred_document_key(?, ?, now(), 'again')")) {
                ps.setString(1, T);
                ps.setObject(2, id[0]);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    shredded[1] = rs.getString(1);
                }
            }
        });
        assertThat(shredded[0]).isEqualTo(SeedData.documentKeyId(id[0]));
        assertThat(shredded[1]).isNull();
        long live = DB.asApp(T, c -> SeedData.longValue(c,
                "SELECT count(*) FROM document_key WHERE tenant_id = ? AND disclosure_id = ? AND wrapped_dek IS NOT NULL", T, id[0]));
        assertThat(live).isZero();
        // 파기된 키는 되살릴 수 없다
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c,
                "UPDATE document_key SET wrapped_dek = decode('01' || repeat('00', 28), 'hex'), shredded_at = NULL, shredded_by = NULL" + where,
                T, id[0])))).isEqualTo("GD092");
        // 파기된 키로 새 산출물을 기록할 수 없다
        assertThat(sqlStateOf(() -> DB.seed(T, c -> SeedData.artifact(c, T, id[0], "CANONICAL_JSON")))).isEqualTo("GD093");
    }

    // ------------------------------------------------------------------ 산출물

    private static final String INSERT_ARTIFACT = """
            INSERT INTO document_artifact (tenant_id, disclosure_id, kind, storage_key, sha256, bytes, created_at, cipher_sha256, cipher_bytes, key_id)
            VALUES (?, ?, ?, ? || '/' || ?::text || '/' || ? || '/' || repeat('9', 64), repeat('f', 64), ?, now(), repeat('9', 64), ?, ?)""";

    @Test
    void artifactsAreRecordedOnlyForSealedDocumentsWithTheirOwnKey() {
        String sealedKey = SeedData.documentKeyId(sealed);
        assertRejected(DB, T, "GD093", INSERT_ARTIFACT, T, draft, "PDF", T, draft, "PDF", 10L, 39L, sealedKey);   // 봉인 전 확인서
        UUID[] other = new UUID[1];
        DB.seed(T, c -> other[0] = SeedData.disclosure(c, T, "SEALED", SeedData.hash('8')));
        assertRejected(DB, T, "GD093", INSERT_ARTIFACT, T, other[0], "PDF", T, other[0], "PDF", 10L, 39L, sealedKey);  // 남의 키
        assertRejected(DB, T, "23514", INSERT_ARTIFACT, T, sealed, "CANONICAL_JSON", T, sealed, "CANONICAL_JSON", 10L, 40L, sealedKey);
        assertRejected(DB, T, "23514", INSERT_ARTIFACT, T, sealed, "CANONICAL_JSON", T, sealed, "PDF", 10L, 39L, sealedKey);  // 객체 키 형식
        assertRejected(DB, T, "23514", INSERT_ARTIFACT, T, sealed, "THUMBNAIL", T, sealed, "THUMBNAIL", 10L, 39L, sealedKey);
        assertAllowed(DB, T, INSERT_ARTIFACT, T, sealed, "CANONICAL_JSON", T, sealed, "CANONICAL_JSON", 10L, 39L, sealedKey);
    }

    // ------------------------------------------------------------------ 승인의 룰 버전 귀속

    private static final String INSERT_REVIEW = """
            INSERT INTO review (tenant_id, review_id, disclosure_id, rule_id, subject_hash, approved_by, approved_role, approved_at, reason,
                                rule_version_id, tenant_rule_version_id)
            VALUES (?, gen_random_uuid(), ?, 'R-TEMP-PRODUCT', repeat('0', 64), 'm', 'MANAGER', now(), '사유', ?, ?)""";

    @Test
    void approvalsCarryThePinnedRuleVersions() {
        assertAllowed(DB, T, INSERT_REVIEW, T, draft, "DISC-2026-07", null);
        assertRejected(DB, T, "GD081", INSERT_REVIEW, T, draft, "DISC-2027-01", null);
        assertRejected(DB, T, "GD081", INSERT_REVIEW, T, draft, "DISC-2026-07", "HOUSE-2026");
        assertRejected(DB, T, "GD081", INSERT_REVIEW, T, draft, null, null);                 // 버전 없는 승인(NOT NULL보다 트리거가 먼저)
    }
}
