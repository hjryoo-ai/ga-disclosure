package com.ga.platform.spring.jdbc;

import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 모든 저장소의 기반 클래스. DB에 닿는 유일한 경로다(CLAUDE.md 절대 규칙 5, 아키텍처 테스트로 강제).
 *
 * <p>모든 조회·변경은 다음 순서로 검사한 뒤에만 실행된다. 어느 하나라도 실패하면 DB에 도달하지 않는다.
 * <ol>
 *   <li>{@link TenantContext#current()} — 바인딩이 없으면 {@code TenantNotBoundException}.</li>
 *   <li>SQL에 테넌트 바인드 변수 {@code :tenantId}가 있어야 한다 — 없으면 {@link MissingTenantPredicateException}.
 *       조회·수정·삭제는 {@code WHERE tenant_id = :tenantId}, 삽입은 {@code tenant_id} 컬럼에 {@code :tenantId}.</li>
 *   <li>호출자는 {@code tenantId} 파라미터를 직접 넘길 수 없다 — 값은 항상 컨텍스트에서 자동 주입된다.</li>
 *   <li>{@link TenantSessionBinder}가 같은 테넌트로 연 트랜잭션 안이어야 한다 —
 *       밖이면 {@link OutsideTenantTransactionException}, 테넌트가 다르면 {@link TenantMismatchException}.</li>
 * </ol>
 * 1차 방어가 이 클래스, 2차 방어가 PostgreSQL RLS({@code app.tenant_id} 세션 설정)다.
 */
public abstract class TenantScopedRepository {

    /** 자동 주입되는 테넌트 바인드 변수 이름. SQL에는 {@code :tenantId}로 쓴다. */
    public static final String TENANT_PARAM = "tenantId";

    private static final Pattern TENANT_PLACEHOLDER = Pattern.compile(":" + TENANT_PARAM + "(?![A-Za-z0-9_])");

    private final JdbcClient jdbc;

    protected TenantScopedRepository(Gateway gateway) {
        this.jdbc = Objects.requireNonNull(gateway, "gateway").client;
    }

    protected final <T> List<T> query(String sql, Map<String, ?> params, RowMapper<T> mapper) {
        return statement(sql, params).query(mapper).list();
    }

    /** 0건이면 빈 값, 2건 이상이면 {@link AmbiguousResultException}(엔진과 같은 fail-fast 규약). */
    protected final <T> Optional<T> queryAtMostOne(String sql, Map<String, ?> params, RowMapper<T> mapper) {
        List<T> rows = query(sql, params, mapper);
        if (rows.size() > 1) {
            throw new AmbiguousResultException(rows.size());
        }
        return rows.stream().findFirst();
    }

    protected final int update(String sql, Map<String, ?> params) {
        return statement(sql, params).update();
    }

    private JdbcClient.StatementSpec statement(String sql, Map<String, ?> params) {
        TenantId tenant = TenantContext.current();
        if (!TENANT_PLACEHOLDER.matcher(sql).find()) {
            throw new MissingTenantPredicateException(sql);
        }
        if (params.containsKey(TENANT_PARAM)) {
            throw new IllegalArgumentException("'" + TENANT_PARAM + "' is injected from TenantContext and must not be passed by callers");
        }
        TenantSessionBinder.requireBoundTransaction(tenant);
        return jdbc.sql(sql).params(params).param(TENANT_PARAM, tenant.value());
    }

    /**
     * 저장소가 쓰는 JDBC 클라이언트의 운반체. 원시 {@code DataSource}/{@code JdbcClient}를 저장소 하위 클래스에 노출하지
     * 않기 위한 봉투다 — 하위 클래스는 이 객체를 생성자로 받아 {@code super(gateway)}에 넘기기만 한다.
     */
    public static final class Gateway {

        private final JdbcClient client;

        public Gateway(DataSource dataSource) {
            this.client = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
        }
    }
}
