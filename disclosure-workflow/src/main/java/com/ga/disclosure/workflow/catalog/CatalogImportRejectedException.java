package com.ga.disclosure.workflow.catalog;

/** 유효한 파일이지만 현재 캐시에 적용할 수 없다(기준일 역행, 상품의 보험사 변경, 없는 상품군 참조). 트랜잭션은 롤백된다. */
public class CatalogImportRejectedException extends RuntimeException {

    public CatalogImportRejectedException(String message) {
        super(message);
    }
}
