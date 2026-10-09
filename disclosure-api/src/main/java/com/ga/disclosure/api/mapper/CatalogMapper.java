package com.ga.disclosure.api.mapper;

import com.ga.disclosure.api.dto.CatalogProductList;
import com.ga.disclosure.api.dto.CatalogProductView;
import com.ga.disclosure.api.error.MalformedRequestException;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.workflow.catalog.CatalogQueryService;

import java.util.Optional;

/** 카탈로그 검색 매핑(6B §9.8): 질의 형식 검사(틀리면 그 매개변수 이름의 400)와 응답 모양. */
public final class CatalogMapper {

    static final int MAX_KEYWORD = 100;

    private CatalogMapper() {
    }

    public static GroupCode group(String group) {
        try {
            return GroupCode.of(group);
        } catch (RuntimeException e) {
            throw new MalformedRequestException("group");
        }
    }

    public static Optional<InsurerCode> insurer(String insurer) {
        if (insurer == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(InsurerCode.of(insurer));
        } catch (RuntimeException e) {
            throw new MalformedRequestException("insurer");
        }
    }

    public static Optional<String> keyword(String q) {
        if (q == null || q.isBlank()) {
            return Optional.empty();
        }
        if (q.length() > MAX_KEYWORD || q.codePoints().anyMatch(Character::isISOControl)) {
            throw new MalformedRequestException("q");
        }
        return Optional.of(q);
    }

    public static CatalogProductList list(CatalogQueryService.Result result) {
        return new CatalogProductList(result.asOf().toString(), result.items().stream().map(p -> new CatalogProductView(p.key().value(), p.name(), p.insurer().value(),
                p.group().value(), p.saleFrom().toString(), p.saleTo() == null ? null : p.saleTo().toString())).toList());
    }
}
