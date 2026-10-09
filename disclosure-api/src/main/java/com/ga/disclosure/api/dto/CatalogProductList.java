package com.ga.disclosure.api.dto;

import java.util.List;

/** 카탈로그 검색 결과(기준일 = 오늘 KST, 상품 키 순). */
public record CatalogProductList(String asOf, List<CatalogProductView> items) {
}
