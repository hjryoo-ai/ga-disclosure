package com.ga.disclosure.api.dto;

/** 카탈로그 상품 한 건(6B §9.8): 상품 키·이름·보험사·상품군·판매기간({@code saleTo} 없으면 null). 개인정보 없음. */
public record CatalogProductView(String productKey, String name, String insurerCode, String groupCode, String saleFrom, String saleTo) {
}
