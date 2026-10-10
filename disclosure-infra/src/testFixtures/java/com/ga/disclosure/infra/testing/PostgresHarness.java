package com.ga.disclosure.infra.testing;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * 통합 테스트용 PostgreSQL 18 하네스. JVM당 1회 기동·재사용(싱글턴).
 *
 * <ol>
 *   <li>{@value #IMAGE} 컨테이너를 {@code POSTGRES_DB=disclosure}로 띄우고 {@code docker/postgres/init-roles.sql}을
 *       초기화 스크립트로 실행한다(docker-compose와 같은 파일).</li>
 *   <li>{@code disclosure_migrator}로 Flyway 마이그레이션(V1~).</li>
 *   <li>테스트 데이터소스는 {@code disclosure_app}(RLS 대상). 시드는 {@code disclosure_migrator}(FORCE RLS라 테넌트 설정 필요).</li>
 * </ol>
 * Docker가 없으면 {@link #get()}이 예외를 던져 테스트가 <b>실패</b>한다(스킵하지 않는다).
 */
public final class PostgresHarness {

    /** postgres:18.6 — digest는 2026-10-10 실측(다중 아키텍처 인덱스, Phase 8 — 태그만 쓰던 것을 SeaweedFS처럼 고정). */
    public static final String IMAGE = "postgres@sha256:74935e72241653ca55e0414067e6d8763aceb8a810eb51b452253ec3dcfc4336";
    public static final String DATABASE = "disclosure";
    public static final String MIGRATOR = "disclosure_migrator";
    public static final String APP = "disclosure_app";
    public static final String OPERATOR = "disclosure_operator";
    public static final String JOB_LOCK = "disclosure_job_lock";
    public static final String HEALTH = "disclosure_health";
    public static final String BACKUP = "disclosure_backup";
    // init-roles.sql과 짝을 이루는 로컬 전용 자격 증명
    static final String MIGRATOR_PASSWORD = "migrator_local_only";
    static final String APP_PASSWORD = "app_local_only";
    public static final String OPERATOR_PASSWORD = "operator_local_only";
    public static final String JOB_LOCK_PASSWORD = "job_lock_local_only";
    public static final String HEALTH_PASSWORD = "health_local_only";
    public static final String BACKUP_PASSWORD = "backup_local_only";

    private static PostgresHarness instance;

    private final PostgreSQLContainer container;
    private final DataSource app;
    private final DataSource migrator;
    private final DataSource operator;
    private final DataSource jobLock;
    private final DataSource health;
    private final DataSource superuser;

    private PostgresHarness() {
        Path initRoles = repoRoot().resolve("docker/postgres/init-roles.sql");
        if (!Files.isRegularFile(initRoles)) {
            throw new IllegalStateException("init-roles.sql not found: " + initRoles);
        }
        container = new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(DATABASE)
                .withUsername("postgres")
                .withPassword("postgres")
                .withCopyFileToContainer(MountableFile.forHostPath(initRoles), "/docker-entrypoint-initdb.d/00-init-roles.sql");
        container.start();

        superuser = dataSource("postgres", "postgres");
        migrator = dataSource(MIGRATOR, MIGRATOR_PASSWORD);
        app = dataSource(APP, APP_PASSWORD);
        operator = dataSource(OPERATOR, OPERATOR_PASSWORD);
        jobLock = dataSource(JOB_LOCK, JOB_LOCK_PASSWORD);
        health = dataSource(HEALTH, HEALTH_PASSWORD);

        Flyway.configure()
                .dataSource(migrator)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    public static synchronized PostgresHarness get() {
        if (instance == null) {
            instance = new PostgresHarness();
        }
        return instance;
    }

    /** 이 JVM에서 이미 띄운 하네스(없으면 띄우지 않는다 — 실행 끝 스캔용). */
    public static synchronized java.util.Optional<PostgresHarness> ifStarted() {
        return java.util.Optional.ofNullable(instance);
    }

    public DataSource appDataSource() {
        return app;
    }

    public DataSource migratorDataSource() {
        return migrator;
    }

    /** 테넌트 디렉터리 전용 롤({@code tenant.tenant_id}만 읽는다, V4). */
    public DataSource operatorDataSource() {
        return operator;
    }

    /** 작업 잠금 전용 롤(테이블 권한 0, V12 — advisory lock만). */
    public DataSource jobLockDataSource() {
        return jobLock;
    }

    /** 헬스·스키마 버전 가드 전용 롤(이력 표 두 컬럼만, V22). */
    public DataSource healthDataSource() {
        return health;
    }

    /** 데이터베이스의 일반 세션 데이터 소스(롤·비밀번호 임의 — 접속 거부 시험용). */
    public DataSource dataSourceAs(String role, String password) {
        return dataSource(role, password);
    }

    /** 같은 컨테이너의 다른 데이터베이스({@link #emptyDatabase})의 앱 롤 데이터 소스(Phase 8 — 질의 계획 실측). */
    public DataSource appDataSource(String database) {
        return dataSource(APP, APP_PASSWORD, database);
    }

    /** 같은 컨테이너의 다른 데이터베이스({@link #emptyDatabase})의 superuser 데이터 소스. */
    public DataSource superuserDataSource(String database) {
        return dataSource("postgres", "postgres", database);
    }

    public DataSource superuserDataSource() {
        return superuser;
    }

    /**
     * 같은 컨테이너에 빈 데이터베이스를 하나 더 만들어 {@code disclosure_migrator} 데이터 소스를 돌려준다(소유·권한은 init-roles.sql과 같다).
     * 단계별 마이그레이션(예: V7까지 → 옛 형식 행 → V8) 검증용 — 공유 DB는 이미 최신이라 이관 경로를 지나지 않는다.
     */
    public DataSource emptyDatabase(String name) {
        if (!name.matches("[a-z][a-z0-9_]{0,40}")) {
            throw new IllegalArgumentException("database name must be lower-case identifier: " + name);
        }
        try (Connection c = superuser.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name + " OWNER " + MIGRATOR);
            s.execute("REVOKE ALL ON DATABASE " + name + " FROM PUBLIC");
            s.execute("GRANT CONNECT ON DATABASE " + name + " TO " + APP);
            s.execute("GRANT CONNECT ON DATABASE " + name + " TO " + OPERATOR);
            s.execute("GRANT CONNECT ON DATABASE " + name + " TO " + JOB_LOCK);
            s.execute("GRANT CONNECT ON DATABASE " + name + " TO " + HEALTH);
        } catch (SQLException e) {
            throw new UncheckedSqlException(e);
        }
        try (Connection c = dataSource("postgres", "postgres", name).getConnection(); Statement s = c.createStatement()) {
            s.execute("ALTER SCHEMA public OWNER TO " + MIGRATOR);
            s.execute("REVOKE ALL ON SCHEMA public FROM PUBLIC");
        } catch (SQLException e) {
            throw new UncheckedSqlException(e);
        }
        return dataSource(MIGRATOR, MIGRATOR_PASSWORD, name);
    }

    /** 운영과 같은 위치의 마이그레이션을 {@code target} 버전까지만 적용한다. */
    public static void migrate(DataSource migratorOfDatabase, String target) {
        Flyway.configure()
                .dataSource(migratorOfDatabase)
                .locations("classpath:db/migration")
                .target(target)
                .load()
                .migrate();
    }

    public String jdbcUrl() {
        return container.getJdbcUrl();
    }

    /** 컨테이너 안에서 전체 데이터 덤프({@code pg_dump --data-only}, 컨테이너 superuser — RLS와 무관하게 전 테넌트). */
    public String dumpAllData() {
        try {
            var result = container.execInContainer("pg_dump", "--data-only", "--username", container.getUsername(), container.getDatabaseName());
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("pg_dump failed: " + result.getStderr());
            }
            return result.getStdout();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** PostgreSQL 서버 로그(컨테이너 표준 출력·오류). */
    public String serverLogs() {
        return container.getLogs();
    }

    /** {@code disclosure_migrator}로 한 트랜잭션을 열고 {@code app.tenant_id}를 설정한 뒤 실행·커밋한다(시드용). */
    public void seed(String tenantId, SqlWork work) {
        runInTransaction(migrator, tenantId, work, true);
    }

    /** {@code disclosure_app}으로 한 트랜잭션을 열고(테넌트가 {@code null}이면 설정하지 않음) 실행 후 롤백한다. */
    public <T> T asApp(String tenantIdOrNull, SqlFunction<T> fn) {
        return inTransaction(app, tenantIdOrNull, fn, false);
    }

    /** {@code disclosure_app}으로 실행 후 커밋한다. */
    public <T> T asAppCommitting(String tenantIdOrNull, SqlFunction<T> fn) {
        return inTransaction(app, tenantIdOrNull, fn, true);
    }

    private static void runInTransaction(DataSource ds, String tenantId, SqlWork work, boolean commit) {
        inTransaction(ds, tenantId, c -> {
            work.run(c);
            return null;
        }, commit);
    }

    private static <T> T inTransaction(DataSource ds, String tenantId, SqlFunction<T> fn, boolean commit) {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (tenantId != null) {
                    setTenant(c, tenantId);
                }
                T result = fn.apply(c);
                if (commit) {
                    c.commit();
                } else {
                    c.rollback();
                }
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new UncheckedSqlException(e);
        }
    }

    public static void setTenant(Connection c, String tenantId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            ps.setString(1, tenantId);
            ps.execute();
        }
    }

    private DataSource dataSource(String user, String password) {
        return dataSource(user, password, DATABASE);
    }

    private DataSource dataSource(String user, String password, String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(container.getJdbcUrl());
        ds.setDatabaseName(database);
        ds.setUser(user);
        ds.setPassword(password);
        return ds;
    }

    private static Path repoRoot() {
        String root = System.getProperty("ga.repoRoot");
        if (root == null) {
            throw new IllegalStateException("system property ga.repoRoot is not set (configured by the Gradle build)");
        }
        return Path.of(root);
    }

    @FunctionalInterface
    public interface SqlWork {
        void run(Connection c) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection c) throws SQLException;
    }

    /** SQL 예외를 SQLSTATE와 함께 전달한다. */
    public static final class UncheckedSqlException extends RuntimeException {
        public UncheckedSqlException(SQLException cause) {
            super(cause.getSQLState() + ": " + cause.getMessage(), cause);
        }

        public String sqlState() {
            return ((SQLException) getCause()).getSQLState();
        }
    }
}
