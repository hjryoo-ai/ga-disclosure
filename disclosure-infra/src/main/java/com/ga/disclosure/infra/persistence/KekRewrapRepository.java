package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.workflow.kek.KekRewrapStore;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link KekRewrapStore} 어댑터. 대상 = 감싼 바이트가 있는(파기되지 않은) 행: 문서 키({@code wrapped_dek} 있음), 고객 데이터 키(DESTROYED 아님),
 * 끝난 작업의 보고서 키. 쓰기는 DB 함수 {@code ga_kek_rewrap}(V21 — 정의자 롤, 전제 판정 GD141) 하나다.
 */
@Repository
public class KekRewrapRepository extends TenantScopedRepository implements KekRewrapStore {

    public KekRewrapRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public List<Wrapped> notUnder(Target target, String kekId, Optional<String> after, int limit) {
        Map<String, Object> params = new HashMap<>();
        params.put("kek", kekId);
        params.put("after", after.orElse(""));
        params.put("limit", limit);
        return switch (target) {
            case DOCUMENT_KEY -> query("""
                    SELECT key_id, kek_key_id, wrapped_dek FROM document_key
                     WHERE tenant_id = :tenantId AND wrapped_dek IS NOT NULL AND kek_key_id <> :kek AND key_id > :after
                     ORDER BY key_id LIMIT :limit
                    """, params, (rs, n) -> new Wrapped(target, rs.getString("key_id"), rs.getString("key_id"), rs.getString("kek_key_id"),
                    rs.getBytes("wrapped_dek")));
            case CUSTOMER_DATA_KEY -> query("""
                    SELECT key_id, kek_id, wrapped_key FROM customer_data_key
                     WHERE tenant_id = :tenantId AND status <> 'DESTROYED' AND wrapped_key IS NOT NULL AND kek_id <> :kek AND key_id > :after
                     ORDER BY key_id LIMIT :limit
                    """, params, (rs, n) -> new Wrapped(target, rs.getString("key_id"), rs.getString("key_id"), rs.getString("kek_id"),
                    rs.getBytes("wrapped_key")));
            case JOB_REPORT -> query("""
                    SELECT job_id::text AS job_id, report_kek_id, report_key_wrapped FROM async_job
                     WHERE tenant_id = :tenantId AND report_key_wrapped IS NOT NULL AND report_kek_id <> :kek AND job_id::text > :after
                     ORDER BY job_id::text LIMIT :limit
                    """, params, (rs, n) -> new Wrapped(target, rs.getString("job_id"), "RPT-" + rs.getString("job_id"), rs.getString("report_kek_id"),
                    rs.getBytes("report_key_wrapped")));
        };
    }

    @Override
    public boolean rewrap(Target target, String rowKey, String fromKekId, String toKekId, byte[] wrapped) {
        return Boolean.TRUE.equals(queryAtMostOne("""
                SELECT ga_kek_rewrap(:tenantId, :target, :rowKey, :from, :to, :wrapped) AS moved
                """, Map.of("target", target.dbName(), "rowKey", rowKey, "from", fromKekId, "to", toKekId, "wrapped", wrapped),
                (rs, n) -> rs.getBoolean("moved")).orElse(false));
    }
}
