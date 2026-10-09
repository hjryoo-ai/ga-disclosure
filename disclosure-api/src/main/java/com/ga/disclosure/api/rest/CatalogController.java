package com.ga.disclosure.api.rest;

import com.ga.disclosure.api.dto.CatalogProductList;
import com.ga.disclosure.api.mapper.CatalogMapper;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.catalog.CatalogQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 카탈로그 상품 검색(6B §9.8): {@code GET /api/v1/catalog/products?group&q&insurer} — 설계사·관리자·준법, 기준일 오늘(KST). */
@RestController
@RequestMapping("/api/v1/catalog/products")
public class CatalogController {

    private final CatalogQueryService catalog;

    public CatalogController(CatalogQueryService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public CatalogProductList search(Caller caller, @RequestParam(name = "group") String group, @RequestParam(name = "q", required = false) String q,
                                     @RequestParam(name = "insurer", required = false) String insurer) {
        return CatalogMapper.list(catalog.search(caller, CatalogMapper.group(group), CatalogMapper.insurer(insurer), CatalogMapper.keyword(q)));
    }
}
