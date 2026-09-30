package com.ga.disclosure.workflow.catalog;

import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 카탈로그 상품(설계서 §4.2). 판매기간 [saleFrom, saleTo). {@code defaults}는 서식 항목 기본값 {코드: 값}(보험료 예시 등, 금액은
 * 정수 원)이며 복사본으로만 드나든다.
 */
public record CatalogProduct(ProductKey key, GroupCode group, String name, LocalDate saleFrom, LocalDate saleTo, JsonNode defaults) {

    public CatalogProduct {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(saleFrom, "saleFrom");
        Validity.check(saleFrom, saleTo);
        defaults = Objects.requireNonNull(defaults, "defaults").deepCopy();
        if (!defaults.isObject()) {
            throw new IllegalArgumentException("defaults must be a JSON object");
        }
    }

    public InsurerCode insurer() {
        return key.insurer();
    }

    @Override
    public JsonNode defaults() {
        return defaults.deepCopy();
    }

    public boolean onSaleOn(LocalDate date) {
        return Validity.contains(saleFrom, saleTo, date);
    }
}
