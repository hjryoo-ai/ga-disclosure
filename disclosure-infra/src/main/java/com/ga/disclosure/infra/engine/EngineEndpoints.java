package com.ga.disclosure.infra.engine;

import com.ga.platform.core.tenant.TenantId;

import java.net.URI;

/** 테넌트별 엔진 인스턴스 주소({@code tenant.engine_base_url}, 엔진은 테넌트별 인스턴스). */
@FunctionalInterface
public interface EngineEndpoints {

    URI baseUrl(TenantId tenant);
}
