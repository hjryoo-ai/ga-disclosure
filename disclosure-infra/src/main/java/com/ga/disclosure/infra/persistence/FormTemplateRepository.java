package com.ga.disclosure.infra.persistence;

import com.ga.disclosure.compliance.rules.FormTemplateStore;
import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.domain.vo.TemplateRef;
import com.ga.disclosure.rules.template.FormTemplate;
import com.ga.disclosure.rules.template.FormTemplatePort;
import com.ga.platform.core.tenant.TenantContext;
import com.ga.platform.core.tenant.TenantId;
import com.ga.platform.spring.jdbc.TenantJdbcGateway;
import com.ga.platform.spring.jdbc.TenantScopedRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 서식 템플릿 저장소. 해석용 조회 포트({@link FormTemplatePort})와 거버넌스 포트({@link FormTemplateStore})를 구현한다.
 * 번들 출처 행의 불변과 테넌트 작성본의 수정 가능 기간은 DB 트리거(V4)가 강제하고, 같은 유형의 구간 겹침은 배타 제약이 막는다.
 */
@Repository
public class FormTemplateRepository extends TenantScopedRepository implements FormTemplatePort, FormTemplateStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final RowMapper<FormTemplate> MAPPER = (rs, n) -> new FormTemplate(
            TemplateRef.of(rs.getString("template_id"), rs.getInt("version")),
            TemplateType.valueOf(rs.getString("template_type")),
            rs.getObject("apply_from", LocalDate.class),
            rs.getObject("apply_to", LocalDate.class),
            JSON.readTree(rs.getString("fields")),
            JSON.readTree(rs.getString("layout")),
            JSON.readTree(rs.getString("pending_confirmation")),
            rs.getString("source_bundle_id"),
            rs.getString("bundle_hash"));

    public FormTemplateRepository(TenantJdbcGateway gateway) {
        super(gateway);
    }

    @Override
    public List<FormTemplate> findActive(TenantId tenant, TemplateType templateType, LocalDate asOf) {
        if (!TenantContext.current().equals(tenant)) {
            throw new IllegalArgumentException("port called for " + tenant + " while bound to " + TenantContext.current());
        }
        Objects.requireNonNull(asOf, "asOf");
        return query("""
                SELECT template_id, version, template_type, apply_from, apply_to, fields::text AS fields, layout::text AS layout,
                       pending_confirmation::text AS pending_confirmation, source_bundle_id, bundle_hash
                  FROM form_template
                 WHERE tenant_id = :tenantId
                   AND template_type = :templateType
                   AND apply_from <= :asOf
                   AND (apply_to IS NULL OR apply_to > :asOf)
                 ORDER BY template_id, version
                """, Map.of("templateType", templateType.name(), "asOf", asOf), MAPPER);
    }

    @Override
    public Optional<FormTemplate> find(TemplateRef ref) {
        return queryAtMostOne("""
                SELECT template_id, version, template_type, apply_from, apply_to, fields::text AS fields, layout::text AS layout,
                       pending_confirmation::text AS pending_confirmation, source_bundle_id, bundle_hash
                  FROM form_template
                 WHERE tenant_id = :tenantId
                   AND template_id = :templateId
                   AND version = :version
                """, Map.of("templateId", ref.templateId(), "version", ref.version()), MAPPER);
    }

    @Override
    public void insert(FormTemplate t) {
        Map<String, Object> params = new HashMap<>();
        params.put("templateId", t.ref().templateId());
        params.put("version", t.ref().version());
        params.put("templateType", t.templateType().name());
        params.put("applyFrom", t.applyFrom());
        params.put("applyTo", t.applyTo());
        params.put("fields", JSON.writeValueAsString(t.fields()));
        params.put("layout", JSON.writeValueAsString(t.layout()));
        params.put("pending", JSON.writeValueAsString(t.pendingConfirmation()));
        params.put("sourceBundleId", t.sourceBundleId());
        params.put("bundleHash", t.bundleHash());
        update("""
                INSERT INTO form_template (tenant_id, template_id, version, template_type, apply_from, apply_to, fields, layout,
                                           pending_confirmation, source_bundle_id, bundle_hash)
                VALUES (:tenantId, :templateId, :version, :templateType, :applyFrom, :applyTo, CAST(:fields AS jsonb),
                        CAST(:layout AS jsonb), CAST(:pending AS jsonb), :sourceBundleId, :bundleHash)
                """, params);
    }

    @Override
    public int closeApplyTo(TemplateRef ref, LocalDate applyTo) {
        return update("""
                UPDATE form_template
                   SET apply_to = :applyTo
                 WHERE tenant_id = :tenantId
                   AND template_id = :templateId
                   AND version = :version
                   AND apply_to IS NULL
                """, Map.of("applyTo", applyTo, "templateId", ref.templateId(), "version", ref.version()));
    }

    @Override
    public List<FormTemplate> findBundled() {
        return query("""
                SELECT template_id, version, template_type, apply_from, apply_to, fields::text AS fields, layout::text AS layout,
                       pending_confirmation::text AS pending_confirmation, source_bundle_id, bundle_hash
                  FROM form_template
                 WHERE tenant_id = :tenantId
                   AND source_bundle_id IS NOT NULL
                 ORDER BY template_id, version
                """, Map.of(), MAPPER);
    }
}
