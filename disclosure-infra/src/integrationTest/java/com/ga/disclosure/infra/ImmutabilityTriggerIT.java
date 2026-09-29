package com.ga.disclosure.infra;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.infra.testing.PostgresHarness;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.FieldSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.ga.disclosure.infra.TriggerAssertions.assertAllowed;
import static com.ga.disclosure.infra.TriggerAssertions.assertRejected;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * C7: 봉인 이후 상태(SEALED·PARTIALLY_SIGNED·COMPLETED·VOID·SUPERSEDED·EXPIRED)의 disclosure 본문 컬럼 UPDATE·행 DELETE 거부,
 * 메타 컬럼 UPDATE 허용, DRAFT~REASONED는 UPDATE 허용; 봉인된 부모의 disclosure_item·recommendation INSERT·UPDATE·DELETE 거부;
 * 봉인 이후 → 가변 상태 status UPDATE 거부(6×4 전수), 봉인 이후 상태 간 UPDATE 허용; superseded_by_id 두 번째 쓰기 거부.
 * 모든 시도는 disclosure_app으로, 트랜잭션은 시도마다 롤백한다(시드는 disclosure_migrator로 커밋).
 */
class ImmutabilityTriggerIT {

    private static final PostgresHarness DB = PostgresHarness.get();
    private static final String T = SeedData.uniqueTenant("IMM");

    static final java.util.List<String> STATUSES = SeedData.ALL_STATUSES;

    /** 본문 컬럼 → 값을 바꾸는 SET 식. tenant_id는 RLS WITH CHECK가 먼저 막으므로 별도로 다루지 않는다. */
    static final Map<String, String> BODY = new LinkedHashMap<>();
    /** 메타 컬럼 → SET 식(status는 상태별로 따로 만든다). */
    static final Map<String, String> META = new LinkedHashMap<>();

    static {
        BODY.put("disclosure_id", "gen_random_uuid()");
        BODY.put("agent_id", "'AGENT-X'");
        BODY.put("customer_ref", "'C-X'");
        BODY.put("group_code", "'PG-X'");
        BODY.put("template_id", "'TPL-X'");
        BODY.put("template_version", "template_version + 1");
        BODY.put("rule_version_id", "'DISC-X'");
        BODY.put("consult_date", "consult_date + 1");
        BODY.put("grade_snapshot_id", "'GRD-X'");
        BODY.put("issuer_mode", "'ASSOC'");
        BODY.put("version", "version + 1");
        BODY.put("supersedes_id", "gen_random_uuid()");
        BODY.put("disclosure_no", "'X-2099-' || substr(md5(random()::text), 1, 6)");
        BODY.put("sealed_at", "TIMESTAMPTZ '2030-01-01 00:00:00+00'");
        BODY.put("canonical_hash", "repeat('9', 64)");
        BODY.put("pdf_hash", "repeat('8', 64)");
        BODY.put("chain_hash", "repeat('7', 64)");
        BODY.put("chain_seq", "coalesce(chain_seq, 0) + 1");

        META.put("superseded_by_id", "gen_random_uuid()");
        META.put("completed_at", "TIMESTAMPTZ '2026-09-24 00:00:00+09'");
        META.put("voided_at", "TIMESTAMPTZ '2026-09-24 00:00:00+09'");
        META.put("void_reason", "'상담 취소'");
        META.put("policy_no", "'POL-9'");
        META.put("contract_date", "DATE '2026-10-01'");
        META.put("retention_until", "DATE '2031-10-01'");
    }

    @BeforeAll
    static void seedTenant() {
        DB.seed(T, c -> SeedData.tenant(c, T));
    }

    static Stream<Arguments> statusTimesBody() {
        return STATUSES.stream().flatMap(s -> BODY.keySet().stream().map(col -> Arguments.of(s, col)));
    }

    static Stream<Arguments> statusTimesMeta() {
        return STATUSES.stream().flatMap(s -> META.keySet().stream().map(col -> Arguments.of(s, col)));
    }

    static Stream<Arguments> sealedTimesMutable() {
        return SeedData.SEALED_STATUSES.stream().flatMap(s -> SeedData.MUTABLE_STATUSES.stream().map(m -> Arguments.of(s, m)));
    }

    static Stream<Arguments> sealedTimesOtherSealed() {
        return SeedData.SEALED_STATUSES.stream().flatMap(s -> SeedData.SEALED_STATUSES.stream()
                .filter(o -> !o.equals(s)).map(o -> Arguments.of(s, o)));
    }

    static Stream<Arguments> statusTimesChildOperation() {
        return STATUSES.stream().flatMap(s -> Stream.of("disclosure_item", "recommendation")
                .flatMap(t -> Stream.of("INSERT", "UPDATE", "DELETE").map(op -> Arguments.of(s, t, op))));
    }

    private static UUID seedDisclosure(String status) {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> id[0] = SeedData.disclosure(c, T, status, SeedData.hash('a')));
        return id[0];
    }

    // ------------------------------------------------------------------ 헤더 본문 / 메타

    @ParameterizedTest(name = "{0} × body {1}")
    @MethodSource("statusTimesBody")
    void bodyColumnUpdateAllowedOnlyWhileMutable(String status, String column) {
        UUID id = seedDisclosure(status);
        String sql = "UPDATE disclosure SET " + column + " = " + BODY.get(column) + " WHERE tenant_id = ? AND disclosure_id = ?";
        if (SeedData.MUTABLE_STATUSES.contains(status)) {
            assertAllowed(DB, T, sql, T, id);
        } else {
            assertRejected(DB, T, "GD001", sql, T, id);
        }
    }

    @ParameterizedTest(name = "{0} × meta {1}")
    @MethodSource("statusTimesMeta")
    void metaColumnUpdateAlwaysAllowed(String status, String column) {
        UUID id = seedDisclosure(status);
        assertAllowed(DB, T, "UPDATE disclosure SET " + column + " = " + META.get(column) + " WHERE tenant_id = ? AND disclosure_id = ?", T, id);
    }

    @ParameterizedTest
    @FieldSource("STATUSES")
    void metaStatusChangeWithinSameClassAllowedAndAllMetaAtOnce(String status) {
        UUID id = seedDisclosure(status);
        String next = SeedData.MUTABLE_STATUSES.contains(status)
                ? (status.equals("DRAFT") ? "COMPARED" : "DRAFT")
                : (status.equals("VOID") ? "EXPIRED" : "VOID");
        assertAllowed(DB, T, "UPDATE disclosure SET status = ? WHERE tenant_id = ? AND disclosure_id = ?", next, T, id);

        StringBuilder all = new StringBuilder("UPDATE disclosure SET status = ?");
        META.forEach((col, expr) -> all.append(", ").append(col).append(" = ").append(expr));
        all.append(" WHERE tenant_id = ? AND disclosure_id = ?");
        assertAllowed(DB, T, all.toString(), next, T, id);
    }

    @ParameterizedTest
    @FieldSource("STATUSES")
    void bodyAndMetaTogetherRejectedAfterSealing(String status) {
        UUID id = seedDisclosure(status);
        String sql = "UPDATE disclosure SET policy_no = 'POL-1', agent_id = 'AGENT-Y' WHERE tenant_id = ? AND disclosure_id = ?";
        if (SeedData.MUTABLE_STATUSES.contains(status)) {
            assertAllowed(DB, T, sql, T, id);
        } else {
            assertRejected(DB, T, "GD001", sql, T, id);
        }
    }

    @ParameterizedTest
    @FieldSource("STATUSES")
    void deleteAlwaysRejected(String status) {
        UUID id = seedDisclosure(status);
        assertRejected(DB, T, "GD002", "DELETE FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", T, id);
    }

    // ------------------------------------------------------------------ 상태 회귀 / 봉인 이후 상태 간 전이

    @ParameterizedTest(name = "{0} → {1} rejected")
    @MethodSource("sealedTimesMutable")
    void sealedCannotRegressToMutable(String from, String to) {
        UUID id = seedDisclosure(from);
        assertRejected(DB, T, "GD003", "UPDATE disclosure SET status = ? WHERE tenant_id = ? AND disclosure_id = ?", to, T, id);
    }

    @ParameterizedTest(name = "{0} → {1} allowed")
    @MethodSource("sealedTimesOtherSealed")
    void sealedToSealedAllowed(String from, String to) {
        UUID id = seedDisclosure(from);
        assertAllowed(DB, T, "UPDATE disclosure SET status = ? WHERE tenant_id = ? AND disclosure_id = ?", to, T, id);
    }

    @ParameterizedTest
    @FieldSource("STATUSES")
    void supersededByIdIsWriteOnce(String status) {
        UUID id = seedDisclosure(status);
        DB.asAppCommitting(T, c -> SeedData.exec(c,
                "UPDATE disclosure SET superseded_by_id = gen_random_uuid() WHERE tenant_id = ? AND disclosure_id = ?", T, id));
        assertRejected(DB, T, "GD004",
                "UPDATE disclosure SET superseded_by_id = gen_random_uuid() WHERE tenant_id = ? AND disclosure_id = ?", T, id);
        assertRejected(DB, T, "GD004",
                "UPDATE disclosure SET superseded_by_id = NULL WHERE tenant_id = ? AND disclosure_id = ?", T, id);
        assertAllowed(DB, T, "UPDATE disclosure SET superseded_by_id = superseded_by_id, policy_no = 'P' WHERE tenant_id = ? AND disclosure_id = ?", T, id);
    }

    // ------------------------------------------------------------------ 비교 항목 / 추천사유

    @ParameterizedTest(name = "{0} × {1} {2}")
    @MethodSource("statusTimesChildOperation")
    void childRowsChangeOnlyWhileParentMutable(String status, String table, String op) {
        UUID[] id = new UUID[1];
        DB.seed(T, c -> {
            id[0] = SeedData.disclosure(c, T, "DRAFT", null);
            SeedData.item(c, T, id[0], 1, "0.84");
            SeedData.recommendation(c, T, id[0], 1);
            if (!status.equals("DRAFT")) {
                SeedData.exec(c, """
                        UPDATE disclosure SET status = ?, disclosure_no = ?, canonical_hash = ?
                         WHERE tenant_id = ? AND disclosure_id = ?
                        """, status, T + "-2026-" + id[0].toString().substring(0, 6), SeedData.hash('a'), T, id[0]);
            }
        });
        String sql = switch (table + ":" + op) {
            case "disclosure_item:INSERT" -> """
                    INSERT INTO disclosure_item (tenant_id, disclosure_id, item_no, insurer_code, product_name, is_recommended, field_values)
                    VALUES (?, ?, 2, 'INS-B', '끼워넣기', true, '{}'::jsonb)""";
            case "disclosure_item:UPDATE" -> "UPDATE disclosure_item SET field_values = '{\"PREMIUM\": 1}'::jsonb WHERE tenant_id = ? AND disclosure_id = ?";
            case "disclosure_item:DELETE" -> "DELETE FROM disclosure_item WHERE tenant_id = ? AND disclosure_id = ?";
            case "recommendation:INSERT" -> "INSERT INTO recommendation (tenant_id, disclosure_id, item_no, reason_codes) VALUES (?, ?, 2, ARRAY['OTHER'])";
            case "recommendation:UPDATE" -> "UPDATE recommendation SET reason_text = '변경' WHERE tenant_id = ? AND disclosure_id = ?";
            case "recommendation:DELETE" -> "DELETE FROM recommendation WHERE tenant_id = ? AND disclosure_id = ?";
            default -> throw new IllegalArgumentException(table + op);
        };
        if (SeedData.MUTABLE_STATUSES.contains(status)) {
            assertAllowed(DB, T, sql, T, id[0]);
        } else {
            assertRejected(DB, T, "GD010", sql, T, id[0]);
        }
    }

    @Test
    void childRowForMissingParentRejected() {
        assertRejected(DB, T, "GD011",
                "INSERT INTO recommendation (tenant_id, disclosure_id, item_no, reason_codes) VALUES (?, ?, 1, ARRAY['OTHER'])",
                T, UUID.randomUUID());
    }

    @Test
    void childRowCannotBeMovedFromSealedParentToDraftParent() {
        UUID[] ids = new UUID[2];
        DB.seed(T, c -> {
            ids[0] = SeedData.disclosure(c, T, "DRAFT", null);
            SeedData.item(c, T, ids[0], 1, "0.84");
            SeedData.exec(c, "UPDATE disclosure SET status = 'SEALED', canonical_hash = ? WHERE tenant_id = ? AND disclosure_id = ?",
                    SeedData.hash('a'), T, ids[0]);
            ids[1] = SeedData.disclosure(c, T, "DRAFT", null);
        });
        assertRejected(DB, T, "GD010", "UPDATE disclosure_item SET disclosure_id = ? WHERE tenant_id = ? AND disclosure_id = ?",
                ids[1], T, ids[0]);
    }

    // ------------------------------------------------------------------ 소유자도 예외 없음 / 도메인 정의와 대조

    @Test
    void ownerRoleIsAlsoBoundByTriggers() {
        UUID id = seedDisclosure("COMPLETED");
        assertThat(TriggerAssertions.sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c,
                "UPDATE disclosure SET agent_id = 'AGENT-Z' WHERE tenant_id = ? AND disclosure_id = ?", T, id))))
                .isEqualTo("GD001");
        assertThat(TriggerAssertions.sqlStateOf(() -> DB.seed(T, c -> SeedData.exec(c,
                "DELETE FROM disclosure WHERE tenant_id = ? AND disclosure_id = ?", T, id))))
                .isEqualTo("GD002");
    }

    @ParameterizedTest
    @EnumSource(DisclosureStatus.class)
    void databaseMutableSetMatchesDomainEnum(DisclosureStatus status) {
        boolean dbMutable = DB.asApp(T, c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT ga_is_mutable_status(?)")) {
                ps.setString(1, status.name());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getBoolean(1);
                }
            }
        });
        assertThat(dbMutable).isEqualTo(status.isMutable());
        assertThat(SeedData.MUTABLE_STATUSES.contains(status.name())).isEqualTo(status.isMutable());
    }
}
