package com.ga.disclosure.infra.jobs;

import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.JobLockPort;
import com.ga.platform.core.tenant.TenantId;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/**
 * 작업 잠금(6A 계획 §6.1): 키 = {@code hashtextextended('ga.job|' || tenant || '|' || 잠금 키, 0)}의 <b>세션</b> advisory lock. 잠금마다 전용 롤
 * {@code disclosure_job_lock}(테이블·스키마 권한 0, V12가 단언)로 <b>풀 없이</b> 커넥션을 새로 연다 — 닫으면 커넥션이 끝나고 DB가 잠금을 푼다(풀에 잠금을 쥔
 * 커넥션이 돌아가지 않는다). 테넌트 데이터를 읽지 않으며 아키텍처 규칙의 DB 직접 접근 허용 목록에 FQN으로 등재된다.
 * <p>보유 확인({@link Held#stillHeld()}, 승인 B1)은 같은 커넥션에서 {@code pg_locks}를 본다: 이 백엔드({@code pg_backend_pid()})가 같은 키의 advisory
 * lock을 {@code granted}로 쥐고 있어야 한다. 커넥션이 죽었으면 조회가 실패하고 잃은 것으로 본다.
 */
public final class JobLockGateway implements JobLockPort {

    static final String NAMESPACE = "ga.job|";
    private static final String KEY = "hashtextextended(?, 0)";

    private final String url;
    private final String username;
    private final String password;

    public JobLockGateway(String url, String username, String password) {
        this.url = Objects.requireNonNull(url, "url");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
    }

    /** 잠금 키 문자열(테스트가 {@code pg_locks}에서 같은 잠금을 찾을 때도 쓴다). */
    public static String keyText(TenantId tenant, JobKind lockKind) {
        return NAMESPACE + tenant.value() + "|" + lockKind.lockKind().name();
    }

    @Override
    public Optional<Held> tryAcquire(TenantId tenant, JobKind lockKind) {
        String key = keyText(tenant, lockKind);
        Connection c;
        try {
            c = DriverManager.getConnection(url, username, password);
        } catch (SQLException e) {
            throw new IllegalStateException("job lock connection failed (" + e.getSQLState() + ")", e);
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(" + KEY + ")")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getBoolean(1)) {
                    return Optional.of(new Session(c, key));
                }
            }
        } catch (SQLException e) {
            closeQuietly(c);
            throw new IllegalStateException("job lock failed (" + e.getSQLState() + ")", e);
        }
        closeQuietly(c);
        return Optional.empty();
    }

    private static void closeQuietly(Connection c) {
        try {
            c.close();
        } catch (SQLException ignored) {
            // 커넥션이 끝나면 세션 잠금도 끝난다
        }
    }

    private static final class Session implements Held {

        private final Connection connection;
        private final String key;
        private boolean closed;

        Session(Connection connection, String key) {
            this.connection = connection;
            this.key = key;
        }

        @Override
        public synchronized boolean stillHeld() {
            if (closed) {
                return false;
            }
            // bigint 키의 advisory lock: classid = 상위 32비트, objid = 하위 32비트, objsubid = 1
            try (PreparedStatement ps = connection.prepareStatement("""
                    SELECT EXISTS (
                        SELECT 1
                          FROM pg_catalog.pg_locks l, (SELECT %s AS k) key
                         WHERE l.locktype = 'advisory'
                           AND l.pid = pg_catalog.pg_backend_pid()
                           AND l.granted
                           AND l.objsubid = 1
                           AND l.classid::bigint = ((key.k >> 32) & 4294967295)
                           AND l.objid::bigint = (key.k & 4294967295))
                    """.formatted(KEY))) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBoolean(1);
                }
            } catch (SQLException e) {
                return false;
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try (PreparedStatement ps = connection.prepareStatement("SELECT pg_advisory_unlock(" + KEY + ")")) {
                ps.setString(1, key);
                ps.execute();
            } catch (SQLException ignored) {
                // 커넥션이 이미 죽었으면 잠금도 이미 풀렸다
            } finally {
                closeQuietly(connection);
            }
        }
    }
}
