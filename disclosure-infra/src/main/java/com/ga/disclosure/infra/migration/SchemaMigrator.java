package com.ga.disclosure.infra.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 스키마 마이그레이션(Phase 8, 8 계획 ③ "마이그레이션 = 선행 Job"). 앱은 기동 때 마이그레이션하지 않는다 — 운영자 CLI {@code db migrate}(마이그레이터 롤,
 * 그 Job에만 자격 증명)가 이 클래스로 적용하고, 앱은 {@link #bundledVersion()}과 DB의 최고 성공 버전이 같아야 뜬다(스키마 버전 가드).
 * Flyway를 참조하는 유일한 운영 클래스다(아키텍처 규칙 — 마이그레이션 경로가 둘이 되지 않게). 테넌트 데이터를 읽지 않는다.
 */
public final class SchemaMigrator {

    public static final String LOCATION = "classpath:db/migration";
    private static final Pattern FILE = Pattern.compile("V([0-9]{1,6}(?:[._][0-9]{1,6}){0,3})__[A-Za-z0-9_]+\\.sql");

    private SchemaMigrator() {
    }

    public record Applied(int migrationsExecuted, String targetVersion) {
    }

    /** 마이그레이터 자격으로 적용한다. {@code clean}은 Flyway 기본값대로 꺼져 있다. */
    public static Applied migrate(String url, String username, String password) {
        Objects.requireNonNull(url, "url");
        MigrateResult result = Flyway.configure()
                .dataSource(url, Objects.requireNonNull(username, "username"), Objects.requireNonNull(password, "password"))
                .locations(LOCATION)
                .validateMigrationNaming(true)
                .load()
                .migrate();
        return new Applied(result.migrationsExecuted, result.targetSchemaVersion);
    }

    /** 이 배포물(jar)에 든 최고 {@code V*} 버전. 하나도 없으면 실패한다(공회전 방지). */
    public static SchemaVersion bundledVersion() {
        Resource[] files;
        try {
            files = new PathMatchingResourcePatternResolver(SchemaMigrator.class.getClassLoader())
                    .getResources("classpath*:db/migration/V*__*.sql");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        SchemaVersion max = null;
        for (Resource r : files) {
            Optional<SchemaVersion> v = versionOf(Objects.requireNonNullElse(r.getFilename(), ""));
            if (v.isPresent() && (max == null || v.get().compareTo(max) > 0)) {
                max = v.get();
            }
        }
        if (max == null) {
            throw new IllegalStateException("no migrations found on the classpath (" + LOCATION + ")");
        }
        return max;
    }

    static Optional<SchemaVersion> versionOf(String fileName) {
        Matcher m = FILE.matcher(fileName);
        return m.matches() ? Optional.of(SchemaVersion.parse(m.group(1))) : Optional.empty();
    }
}
