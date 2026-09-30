package com.ga.disclosure.domain.vo;

import java.util.Objects;

/**
 * 상품키 {@code {insurerCode}:{code}}(예: {@code INS-A:PRD-1001}). 콜론은 정확히 하나, 전체 40자 이하(엔진 계약 1.2.0).
 * 임시등록 상품은 상품키가 없다(엔진 도메인 밖, 설계서 §4.2).
 *
 * @param insurer 보험사(8자 이하)
 * @param code    보험사 내 상품 코드(31자 이하, 영숫자로 시작)
 */
public record ProductKey(InsurerCode insurer, String code) {

    public ProductKey {
        Objects.requireNonNull(insurer, "insurer");
        Patterns.require(Patterns.PRODUCT_CODE, code, "product code");
    }

    public static ProductKey parse(String raw) {
        int colon = raw == null ? -1 : raw.indexOf(':');
        if (colon < 0 || raw.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException("invalid product key: " + raw);
        }
        return new ProductKey(InsurerCode.of(raw.substring(0, colon)), raw.substring(colon + 1));
    }

    public String value() {
        return insurer.value() + ":" + code;
    }

    @Override
    public String toString() {
        return value();
    }
}
