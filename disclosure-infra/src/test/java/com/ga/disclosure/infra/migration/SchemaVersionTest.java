package com.ga.disclosure.infra.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 스키마 버전(가드의 대조 기준): 자리마다 정수 비교 — 문자열 비교면 9 &gt; 22가 된다. */
class SchemaVersionTest {

    @Test
    void comparesNumericallyPerPart() {
        assertThat(SchemaVersion.parse("9")).isLessThan(SchemaVersion.parse("22"));
        assertThat(SchemaVersion.parse("22")).isLessThan(SchemaVersion.parse("22.1"));
        assertThat(SchemaVersion.parse("1_2")).isEqualTo(SchemaVersion.parse("1.2"));
        assertThat(SchemaVersion.parse("22.0")).isEqualTo(SchemaVersion.parse("22")).hasSameHashCodeAs(SchemaVersion.parse("22"));
        assertThat(SchemaVersion.parse("22.0").toString()).isEqualTo("22.0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "v22", "22a", "-1", "1..2", "1.2.3.4.5", "1234567"})
    void rejectsWhatIsNotAVersion(String text) {
        assertThatThrownBy(() -> SchemaVersion.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsOnlyVersionedMigrationFileNames() {
        assertThat(SchemaMigrator.versionOf("V22__health_backup_roles.sql")).contains(SchemaVersion.parse("22"));
        assertThat(SchemaMigrator.versionOf("V1_1__x.sql")).contains(SchemaVersion.parse("1.1"));
        assertThat(SchemaMigrator.versionOf("R__repeatable.sql")).isEqualTo(Optional.empty());
        assertThat(SchemaMigrator.versionOf("V22_health.sql")).isEmpty();
        assertThat(SchemaMigrator.versionOf("V22__x.sql.bak")).isEmpty();
    }

    @Test
    void theBundledVersionIsFoundOnTheClasspath() {
        assertThat(SchemaMigrator.bundledVersion()).isGreaterThanOrEqualTo(SchemaVersion.parse("22"));   // 정확한 값은 SchemaVersionGuardIT가 파일 목록과 대조
    }
}
