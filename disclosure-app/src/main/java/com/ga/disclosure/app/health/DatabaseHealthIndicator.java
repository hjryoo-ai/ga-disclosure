package com.ga.disclosure.app.health;

import com.ga.disclosure.infra.migration.SchemaVersion;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * DB 헬스와 스키마 버전 조회(Phase 8, 8 계획 승인 Q1 — 직접 접근 허용 목록의 다섯째 항목). 전용 롤 {@code disclosure_health}(V22 — {@code CONNECT}·스키마
 * USAGE와 {@code flyway_schema_history (version, success)} SELECT만, 테넌트 표 권한 0)로 <b>풀 없이</b> 매번 연결한다. 실행하는 SQL은 {@link #SQL}의
 * 두 문장뿐 — 테넌트 데이터를 읽지 않는다. 앱 롤을 쓰지 않는 이유: 앱 롤 연결은 지금 {@code TenantScopedRepository}의 바인딩 경로로만 쓰인다(CLAUDE.md
 * 규칙 5 "각 항목은 전용 롤") — 지시문의 "앱 롤"은 이 규칙과 충돌해 전용 롤로 정했다(8 계획 Q1).
 * <p>상태: 연결·{@code SELECT 1}·최고 성공 버전이 배포물 버전과 같으면 UP. 그 밖은 DOWN, 상세는 SQLSTATE와 버전 번호뿐(메시지·URL·롤 이름 0).
 */
public final class DatabaseHealthIndicator implements HealthIndicator {

    static final String PING = "SELECT 1";
    static final String VERSIONS = "SELECT version FROM public.flyway_schema_history WHERE success AND version IS NOT NULL";
    /** 이 클래스가 실행하는 SQL 전부(시험이 대조한다). */
    static final List<String> SQL = List.of(PING, VERSIONS);
    /** 이력 표가 아직 없다(빈 DB — 마이그레이션 전). */
    private static final String UNDEFINED_TABLE = "42P01";

    private final String url;
    private final String username;
    private final String password;
    private final SchemaVersion bundled;

    public DatabaseHealthIndicator(String url, String username, String password, SchemaVersion bundled) {
        this.url = Objects.requireNonNull(url, "url");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
        this.bundled = Objects.requireNonNull(bundled, "bundled");
    }

    public SchemaVersion bundledVersion() {
        return bundled;
    }

    /**
     * DB의 최고 성공 마이그레이션 버전(이력 표가 없으면 empty). 연결·권한 실패는 {@link IllegalStateException} {@code "unreadable (SQLSTATE)"} —
     * V22 전 DB는 헬스 롤에 스키마 USAGE가 없어 {@code 42501}이다(곧 "V22보다 오래됨").
     */
    public Optional<SchemaVersion> appliedVersion() {
        try (Connection c = connect()) {
            return applied(c);
        } catch (SQLException e) {
            throw new IllegalStateException("unreadable (" + e.getSQLState() + ")");
        }
    }

    @Override
    public Health health() {
        try (Connection c = connect()) {
            try (PreparedStatement ps = c.prepareStatement(PING); ResultSet rs = ps.executeQuery()) {
                rs.next();
            }
            Optional<SchemaVersion> applied = applied(c);
            Health.Builder builder = applied.filter(bundled::equals).isPresent() ? Health.up() : Health.down();
            return builder.withDetail("schema", applied.map(SchemaVersion::toString).orElse("none"))
                    .withDetail("application", bundled.toString()).build();
        } catch (SQLException e) {
            return Health.down().withDetail("sqlState", String.valueOf(e.getSQLState())).build();
        }
    }

    private Connection connect() throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", username);
        p.setProperty("password", password);
        p.setProperty("connectTimeout", "3");
        p.setProperty("socketTimeout", "5");
        p.setProperty("ApplicationName", "ga-health");
        return DriverManager.getConnection(url, p);
    }

    private static Optional<SchemaVersion> applied(Connection c) throws SQLException {
        SchemaVersion max = null;
        try (PreparedStatement ps = c.prepareStatement(VERSIONS); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                SchemaVersion v = SchemaVersion.parse(rs.getString(1));
                if (max == null || v.compareTo(max) > 0) {
                    max = v;
                }
            }
        } catch (SQLException e) {
            if (UNDEFINED_TABLE.equals(e.getSQLState())) {
                return Optional.empty();
            }
            throw e;
        }
        return Optional.ofNullable(max);
    }
}
