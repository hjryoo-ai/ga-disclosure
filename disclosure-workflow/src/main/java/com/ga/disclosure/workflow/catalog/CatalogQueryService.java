package com.ga.disclosure.workflow.catalog;

import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.workflow.WorkflowTransactions;
import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.Target;
import com.ga.disclosure.workflow.authz.UseCaseEntry;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 카탈로그 상품 검색(6B 계획 §9.8): 행위 {@code CATALOG_READ}(설계사·관리자·준법 TENANT, 대상 없음). 기준일은 오늘(KST), 상품군 필수 — 포트가 상품군을
 * 요구한다. 결과에 개인정보가 없다(상품 키·이름·보험사·상품군·판매기간).
 */
public final class CatalogQueryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ProductCatalogPort catalog;
    private final WorkflowTransactions transactions;
    private final AuthorizationPort authz;
    private final Clock clock;

    public CatalogQueryService(ProductCatalogPort catalog, WorkflowTransactions transactions, AuthorizationPort authz, Clock clock) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.authz = Objects.requireNonNull(authz, "authz");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 검색 결과와 그 기준일. */
    public record Result(LocalDate asOf, List<CatalogProduct> items) {
        public Result {
            Objects.requireNonNull(asOf, "asOf");
            items = List.copyOf(items);
        }
    }

    @UseCaseEntry(Action.CATALOG_READ)
    public Result search(Caller caller, GroupCode group, Optional<InsurerCode> insurer, Optional<String> keyword) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(group, "group");
        return transactions.inTenant(caller.tenant(), () -> {
            authz.require(caller, Action.CATALOG_READ, Target.none());
            LocalDate asOf = LocalDate.ofInstant(clock.instant(), SEOUL);
            return new Result(asOf, catalog.searchProducts(caller.tenant(), group, insurer, keyword.orElse(null), asOf));
        });
    }
}
