package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.enums.IssuerMode;
import com.ga.platform.core.tenant.TenantId;

/** 확인서가 쓰는 테넌트 속성(대형 GA 여부·정본 모드). 바인딩된 테넌트만 읽는다. */
public interface TenantProfilePort {

    TenantProfile profile(TenantId tenant);

    record TenantProfile(boolean largeGa, IssuerMode issuerMode) {
    }
}
