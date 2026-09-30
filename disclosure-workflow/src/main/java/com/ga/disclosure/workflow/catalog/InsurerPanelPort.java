package com.ga.disclosure.workflow.catalog;

import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.platform.core.tenant.TenantId;

import java.time.LocalDate;
import java.util.List;
import java.util.function.BiPredicate;

/** 보험사 패널("추천가능 보험사") 조회 포트. 기준일 필수. */
public interface InsurerPanelPort {

    boolean isOnPanel(TenantId tenant, InsurerCode insurer, LocalDate date);

    /** 기준일에 위탁 중인 보험사(코드 순). */
    List<PanelEntry> panel(TenantId tenant, LocalDate asOf);

    /**
     * 검증 규칙 R-PANEL이 쓰는 판정 함수({@code ValidationSubject.isInsurerOnPanel})를 테넌트에 묶어 만든다.
     * 확인서 애그리게이트(Phase 3)가 이 함수를 받는다.
     */
    default BiPredicate<InsurerCode, LocalDate> lookupFor(TenantId tenant) {
        return (insurer, date) -> isOnPanel(tenant, insurer, date);
    }
}
