package com.ga.disclosure.compliance.rules;

import com.ga.platform.core.tenant.TenantId;

import java.util.List;

/** 모든 테넌트 ID(운영자 CLI의 {@code --tenants all}). 구현은 테넌트 디렉터리 전용 롤을 쓴다(설계서 §9). */
public interface TenantDirectory {

    List<TenantId> allTenants();
}
