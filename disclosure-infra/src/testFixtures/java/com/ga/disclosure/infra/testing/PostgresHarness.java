package com.ga.disclosure.infra.testing;

import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

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

    public static final String IMAGE = "postgres:18.6";
    public static final String DATABASE = "disclosure";
    public static final String MIGRATOR = "disclosure_migrator";
    public static final String APP = "disclosure_app";
    public static final String OPERATOR = "disclosure_operator";
    // init-roles.sql과 짝을 이루는 로컬 전용 자격 증명
    static final String MIGRATOR_PASSWORD = "migrator_local_only";
    static final String APP_PASSWORD = "app_local_only";
    public static final String OPERATOR_PASSWORD = "operator_local_only";

    private static PostgresHarness instance;

    private final PostgreSQLContainer container;
    private final DataSource app;
    private final DataSource migrator;
    private final DataSource operator;
    private final DataSource superuser;

    private PostgresHarness() {
        Path initRoles = repoRoot().resolve("docker/postgres/init-roles.sql");
        if (!Files.isRegularFile(initRoles)) {
            throw new IllegalStateException("init-roles.sql not found: " + initRoles);
        }
        container = new PostgreSQLContainer(DockerImageName.parse(IMAGE))
                .withDatabaseName(DATABASE)
                .withUsername("postgres")
                .withPassword("postgres")
                .withCopyFileToContainer(MountableFile.forHostPath(initRoles), "/docker-entrypoint-initdb.d/00-init-roles.sql");
        container.start();

        superuser = dataSource("postgres", "postgres");
        migrator = dataSource(MIGRATOR, MIGRATOR_PASSWORD);
        app = dataSource(APP, APP_PASSWORD);
        operator = dataSource(OPERATOR, OPERATOR_PASSWORD);

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

    public DataSource superuserDataSource() {
        return superuser;
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
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(container.getJdbcUrl());
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
