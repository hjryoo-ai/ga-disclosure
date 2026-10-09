package com.ga.disclosure.api.rest;

import com.ga.disclosure.api.dto.CustomerReceipt;
import com.ga.disclosure.api.dto.CustomerRegisterRequest;
import com.ga.disclosure.api.dto.IdempotencyKey;
import com.ga.disclosure.api.mapper.CustomerMapper;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.customer.CustomerRegistrationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 등록(6B §9): {@code POST /api/v1/customers}(설계사만). 고객을 읽는 경로는 없다 — 검색·상세 GET을 두지 않는다(승인 §4 조건 4, ApiRouteSetIT).
 * 응답 201 {@code {customerRef, receiptId}}, {@code Location} 없음.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomersController {

    private final CustomerRegistrationService registrations;

    public CustomersController(CustomerRegistrationService registrations) {
        this.registrations = registrations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerReceipt register(Caller caller, IdempotencyKey key, @RequestBody CustomerRegisterRequest request) {
        return CustomerMapper.receipt(registrations.register(caller, CustomerMapper.registrationKey(caller, key),
                today -> CustomerMapper.customer(request, today)));
    }
}
