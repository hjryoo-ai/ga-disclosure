package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.ga.disclosure.infra.TriggerAssertions.sqlStateOf;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V23(8 계획 승인 Q9 — 렌더러 판 고정): 판은 산출물을 기록할 때 반드시 적고(기본값 없음), 한 문서의 산출물은 같은 판이며(GD093), 기록된 판은 바뀌지
 * 않는다 — 앱 롤은 그 컬럼 UPDATE 권한부터 없고(42501), 소유자도 가드가 거부한다(GD093). 옛 행은 판 1.
 */
class V23RendererVersionIT {

    private static final PostgresHarness DB = PostgresHarness.get();

    private static final String INSERT = """
            INSERT INTO document_artifact (tenant_id, disclosure_id, kind, storage_key, sha256, bytes, created_at, cipher_sha256, cipher_bytes, key_id,
                                           renderer_version)
            VALUES (?, ?, ?, ? || '/' || ?::text || '/' || ? || '/' || repeat('9', 64), repeat('f', 64), 10, now(), repeat('9', 64), 39, ?, ?)""";

    @Test
    void theVersionIsWrittenOnceAndTheSameForEveryArtifactOfADocument() {
        String t = SeedData.uniqueTenant("V23");
        UUID[] sealed = new UUID[1];
        DB.seed(t, c -> {
            SeedData.tenant(c, t);
            sealed[0] = SeedData.disclosure(c, t, "SEALED", SeedData.hash('7'));
            SeedData.artifact(c, t, sealed[0], "CANONICAL_JSON");     // 시험 재료는 판 1(옛 문서)
        });
        String key = SeedData.documentKeyId(sealed[0]);
        UUID d = sealed[0];

        // 판을 빠뜨리면 NOT NULL — 조용한 기본값이 없다
        assertThat(sqlStateOf(() -> DB.seed(t, c -> SeedData.exec(c, """
                INSERT INTO document_artifact (tenant_id, disclosure_id, kind, storage_key, sha256, bytes, created_at, cipher_sha256, cipher_bytes, key_id)
                VALUES (?, ?, 'PDF', ? || '/' || ?::text || '/PDF/' || repeat('9', 64), repeat('f', 64), 10, now(), repeat('9', 64), 39, ?)""",
                t, d, t, d, key)))).isEqualTo("23502");
        // 같은 문서의 다른 판 — 봉인 때 정해진 판을 따라야 한다
        assertThat(sqlStateOf(() -> DB.seed(t, c -> SeedData.exec(c, INSERT, t, d, "PDF", t, d, "PDF", key, (short) 2)))).isEqualTo("GD093");
        // 판 0은 CHECK(가드가 먼저 도는 기존 행 없는 새 문서에서)
        UUID[] fresh = new UUID[1];
        DB.seed(t, c -> {
            fresh[0] = SeedData.disclosure(c, t, "SEALED", SeedData.hash('6'));
            SeedData.documentKey(c, t, fresh[0]);
        });
        assertThat(sqlStateOf(() -> DB.seed(t, c -> SeedData.exec(c, INSERT, t, fresh[0], "PDF", t, fresh[0], "PDF",
                SeedData.documentKeyId(fresh[0]), (short) 0)))).isEqualTo("23514");
        DB.seed(t, c -> SeedData.exec(c, INSERT, t, d, "PDF", t, d, "PDF", key, (short) 1));

        // write-once: 앱 롤은 권한이 없고, 소유자도 가드가 거부한다
        assertThat(sqlStateOf(() -> DB.asApp(t, c -> {
            try (var st = c.prepareStatement("UPDATE document_artifact SET renderer_version = 2 WHERE tenant_id = ? AND disclosure_id = ?")) {
                st.setString(1, t);
                st.setObject(2, d);
                return st.executeUpdate();
            }
        }))).isEqualTo("42501");
        assertThat(sqlStateOf(() -> DB.seed(t, c -> SeedData.exec(c,
                "UPDATE document_artifact SET renderer_version = 2 WHERE tenant_id = ? AND disclosure_id = ? AND kind = 'PDF'", t, d)))).isEqualTo("GD093");
        assertThat(DB.<String>asApp(t, c -> SeedData.call(c,
                "SELECT string_agg(kind || ':' || renderer_version, ',' ORDER BY kind) FROM document_artifact WHERE tenant_id = ? AND disclosure_id = ?", t, d)))
                .isEqualTo("CANONICAL_JSON:1,PDF:1");
    }

    /** 기본값은 마이그레이션 때만 — 지금 컬럼에 기본값이 없다(새 INSERT가 판을 적게 한다). */
    @Test
    void theColumnHasNoDefaultAfterTheMigration() {
        assertThat(DB.<String>asApp(null, c -> SeedData.call(c,
                "SELECT coalesce(column_default, 'none') FROM information_schema.columns WHERE table_name = 'document_artifact' AND column_name = 'renderer_version'")))
                .isEqualTo("none");
    }
}
