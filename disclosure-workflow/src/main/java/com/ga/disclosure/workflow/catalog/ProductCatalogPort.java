package com.ga.disclosure.workflow.catalog;

import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.platform.core.tenant.TenantId;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 상품 카탈로그 조회 포트(설계서 §4.2). v1 어댑터는 파일 수입 캐시(DB)이고 실제 연동은 어댑터 교체다.
 * 기준일은 전부 필수다 — 오늘로 채우지 않는다(호출자가 상담일을 넘긴다). 테넌트는 바인딩된 테넌트와 같아야 한다.
 */
public interface ProductCatalogPort {

    /** 기준일에 유효한 유사상품군(코드 순). */
    List<ProductGroup> listGroups(TenantId tenant, LocalDate asOf);

    /**
     * 기준일에 판매 중인 상품군 소속 상품(상품키 순). {@code insurer}가 있으면 그 보험사만, {@code keyword}가 비어 있지 않으면
     * 상품명 부분 일치(대소문자 무시).
     */
    List<CatalogProduct> searchProducts(TenantId tenant, GroupCode group, Optional<InsurerCode> insurer, String keyword, LocalDate asOf);

    /** 기준일에 판매 중인 상품. 판매기간 밖이면 비어 있다. */
    Optional<CatalogProduct> getProduct(TenantId tenant, ProductKey key, LocalDate asOf);
}
