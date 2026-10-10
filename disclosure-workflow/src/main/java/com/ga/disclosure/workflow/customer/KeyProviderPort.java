package com.ga.disclosure.workflow.customer;

import com.ga.platform.core.tenant.TenantId;

/**
 * 마스터 키(KEK) 포트 — 데이터 키(DEK)를 감싸고(wrap) 푼다(unwrap). (Phase 8, 8 계획 승인 Q2) KEK는 <b>테넌트별</b>이다: 새 DEK를 감쌀 KEK는
 * 그 테넌트의 레지스트리({@code tenant_kek})가 정하고, 키 바이트는 비밀 출처에 있다. 감싼 바이트는 {tenantId, keyId, kekId}에 묶인다(운영 KMS의
 * 암호화 컨텍스트). DEK 평문은 이 포트와 infra 암호화 어댑터 밖으로 나가지 않는다 — 재래핑도 어댑터 안에서 한다({@link #rewrap}).
 */
public interface KeyProviderPort {

    /** 그 테넌트의 새 DEK를 감쌀 KEK ID(레지스트리의 CURRENT). 없으면 그 테넌트는 아직 암호화할 수 없다(예외). */
    String currentKekId(TenantId tenant);

    /** DEK를 {@code kekId}로 감싼다. 감싼 바이트는 tenant·keyId·kekId에 묶인다(다른 테넌트·키 ID로 옮기면 풀리지 않는다). */
    byte[] wrap(TenantId tenant, String keyId, String kekId, byte[] dataKey);

    byte[] unwrap(TenantId tenant, String keyId, String kekId, byte[] wrapped);

    /**
     * {@code fromKekId}로 감싼 DEK를 {@code toKekId}로 다시 감싼다. 새 바이트를 다시 풀어 같은 DEK인지 확인한 뒤 돌려준다(아니면 예외). DEK 평문은
     * 어댑터 밖으로 나가지 않는다.
     */
    byte[] rewrap(TenantId tenant, String keyId, String fromKekId, String toKekId, byte[] wrapped);

    /** 감싼 키가 그 KEK로 풀리는가(verify tenant — DEK는 어댑터 밖으로 나가지 않는다). */
    boolean unwraps(TenantId tenant, String keyId, String kekId, byte[] wrapped);
}
