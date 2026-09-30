package com.ga.disclosure.workflow.customer;

import com.ga.platform.core.tenant.TenantId;

import java.util.List;
import java.util.Optional;

/** 키 순환 결과: 은퇴시킨 키, 새 활성 키, 재암호화한 행 수, 파기한 키. */
public record RekeyReport(TenantId tenant, Optional<String> retiredKeyId, String activeKeyId, int reencrypted, List<String> destroyedKeyIds) {

    public RekeyReport {
        destroyedKeyIds = List.copyOf(destroyedKeyIds);
    }
}
