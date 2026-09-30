package com.ga.disclosure.rules.version;

import com.ga.disclosure.domain.vo.RuleVersionId;
import java.util.Optional;
import com.ga.disclosure.domain.enums.RuleScope;
import com.ga.platform.core.tenant.TenantId;

import java.time.LocalDate;
import java.util.List;

/**
 * 룰 버전 조회 포트(infra 어댑터가 구현). 해석기가 쓴다.
 *
 * <p>{@link #findActive}는 기준일에 <b>시행 중이었던</b> 룰, 즉 status가 ACTIVE 또는 RETIRED이고 적용 구간이 기준일을 포함하는
 * 룰을 돌려준다. RETIRED를 포함해야 경계일 배치 뒤에도 과거 상담일의 확인서를 같은 룰로 해석할 수 있다(설계서 §5).
 * APPROVED는 활성화 배치 전이므로 제외한다. 2건 이상이어도 예외를 던지지 않고 그대로 돌려준다 — 판정은 해석기의 몫이다.
 */
public interface RuleVersionPort {

    List<RuleVersion> findActive(TenantId tenant, RuleScope scope, LocalDate asOf);

    /** 확인서에 고정된 버전 ID로 조회한다(상태 무관 — 판정은 해석기, 3A {@code RuleResolver.load}). */
    Optional<RuleVersion> findById(TenantId tenant, RuleVersionId id);
}
