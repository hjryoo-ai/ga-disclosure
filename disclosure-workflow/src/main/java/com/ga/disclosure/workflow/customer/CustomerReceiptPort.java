package com.ga.disclosure.workflow.customer;

import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.platform.core.tenant.TenantId;

import java.util.UUID;

/**
 * 고객 등록 영수증 ID(6B §9, 10단계 회신 ②): 서버 키 HMAC(테넌트 ‖ 가명 ‖ 등록 키)에서 결정론적으로 파생한 UUID. 같은 등록 키의 첫 등록·재생·멱등 만료 뒤
 * NOOP가 같은 영수증을 돌려준다 — 조회가 없고, 구현은 {@code infra.crypto}(JCA 허용 패키지)에 있다.
 */
public interface CustomerReceiptPort {

    UUID receipt(TenantId tenant, CustomerRef ref, RegistrationKey key);
}
