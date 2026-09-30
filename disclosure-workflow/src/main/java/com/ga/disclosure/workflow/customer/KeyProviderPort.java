package com.ga.disclosure.workflow.customer;

import com.ga.platform.core.tenant.TenantId;

/**
 * 마스터 키(KEK) 포트 — 테넌트 데이터 키(DEK)를 감싸고(wrap) 푼다(unwrap). 운영 어댑터는 KMS(암호화 컨텍스트 =
 * {tenantId, keyId, kekId}), 개발·테스트 어댑터는 로컬 키 파일이다. 실 KMS 연동은 어댑터 교체다(Phase 2 범위 밖).
 * DEK 평문은 이 포트와 infra 암호화 어댑터 밖으로 나가지 않는다.
 */
public interface KeyProviderPort {

    /** 새 DEK를 감쌀 KEK의 ID. */
    String currentKekId();

    /** DEK를 {@code kekId}로 감싼다. 감싼 바이트는 tenant·keyId·kekId에 묶인다(다른 테넌트·키 ID로 옮기면 풀리지 않는다). */
    byte[] wrap(TenantId tenant, String keyId, String kekId, byte[] dataKey);

    byte[] unwrap(TenantId tenant, String keyId, String kekId, byte[] wrapped);
}
