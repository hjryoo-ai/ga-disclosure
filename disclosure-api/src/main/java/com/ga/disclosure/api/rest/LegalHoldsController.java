package com.ga.disclosure.api.rest;

import com.ga.disclosure.api.dto.LegalHoldPage;
import com.ga.disclosure.api.dto.LegalHoldReceipt;
import com.ga.disclosure.api.dto.LegalHoldReleaseRequest;
import com.ga.disclosure.api.dto.LegalHoldRequest;
import com.ga.disclosure.api.mapper.LegalHoldMapper;
import com.ga.disclosure.api.mapper.PageMapper;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.retention.LegalHoldQueryService;
import com.ga.disclosure.workflow.retention.LegalHoldService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 법적 보류(6A 계획 §4.1, 준법): 설정 201·해제 200(영수증), 목록(서명된 커서). 업무 거부는 422 {@code REJECTED}. */
@RestController
@RequestMapping("/api/v1/legal-holds")
public class LegalHoldsController {

    private final LegalHoldService holds;
    private final LegalHoldQueryService queries;

    public LegalHoldsController(LegalHoldService holds, LegalHoldQueryService queries) {
        this.holds = holds;
        this.queries = queries;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LegalHoldReceipt place(Caller caller, @RequestBody LegalHoldRequest request) {
        return LegalHoldMapper.receipt(holds.place(caller, LegalHoldMapper.target(request), LegalHoldMapper.reasonCode(request.reasonCode()),
                request.reasonText()));
    }

    @PostMapping("/{holdId}/release")
    public LegalHoldReceipt release(Caller caller, @PathVariable("holdId") String holdId, @RequestBody LegalHoldReleaseRequest request) {
        return LegalHoldMapper.receipt(holds.release(caller, LegalHoldMapper.holdId(holdId), LegalHoldMapper.reasonCode(request.reasonCode())));
    }

    @GetMapping
    public LegalHoldPage list(Caller caller, @RequestParam(name = "limit", required = false) Integer limit,
                              @RequestParam(name = "after", required = false) String after) {
        return LegalHoldMapper.page(queries.list(caller, PageMapper.limit(limit), PageMapper.after(after)));
    }
}
