package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C13: disclosure_item.ratio_to_avg는 DB 왕복 후 원문과 바이트 동일하다(정규화 없음).
 * 컬럼이 NUMERIC이었다면 "0.840"→"0.840"(scale 3 고정) 또는 "0.84"→"0.840", "84%"는 입력 자체가 실패한다.
 */
class RatioLabelRoundTripIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("RATIO");

    @BeforeAll
    static void seedTenant() {
        DB.seed(T, c -> SeedData.tenant(c, T));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.84", "0.840", "1.3700", "1.02", "137%", "84%", " 0.84 ", "0", "-0.00", "1e-3", "N/A", "０.８４"})
    void ratioToAvgRoundTripsByteForByte(String original) {
        UUID id = UUID.randomUUID();
        String read = DB.asApp(T, c -> {
            SeedData.exec(c, """
                    INSERT INTO disclosure (tenant_id, disclosure_id, agent_id, customer_ref, group_code, template_id,
                                            template_version, issuer_mode, status, consult_date)
                    VALUES (?, ?, 'AGENT-1', 'C-1', 'PG-1', 'STANDARD', 1, 'SELF', 'GRADED', DATE '2026-09-23')
                    """, T, id);
            SeedData.item(c, T, id, 1, original);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT ratio_to_avg FROM disclosure_item WHERE tenant_id = ? AND disclosure_id = ? AND item_no = 1")) {
                ps.setString(1, T);
                ps.setObject(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getString(1);
                }
            }
        });
        assertThat(read.getBytes(StandardCharsets.UTF_8)).isEqualTo(original.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void columnIsTextWithoutFormatCheck() {
        DB.seed(T, c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT data_type,
                           (SELECT count(*) FROM pg_constraint k
                             WHERE k.conrelid = 'disclosure_item'::regclass AND k.contype = 'c'
                               AND pg_get_constraintdef(k.oid) ILIKE '%ratio_to_avg%') AS checks,
                           col_description('disclosure_item'::regclass, ordinal_position) AS comment
                      FROM information_schema.columns
                     WHERE table_name = 'disclosure_item' AND column_name = 'ratio_to_avg'
                    """); ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("data_type")).isEqualTo("text");
                assertThat(rs.getLong("checks")).isZero();
                assertThat(rs.getString("comment")).contains("NUMERIC 금지");
            }
        });
    }
}
