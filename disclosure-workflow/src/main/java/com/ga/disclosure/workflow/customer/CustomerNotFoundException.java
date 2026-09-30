package com.ga.disclosure.workflow.customer;

/** 바인딩된 테넌트에 그 고객 참조가 없다(다른 테넌트의 참조도 같은 결과 — RLS). */
public class CustomerNotFoundException extends RuntimeException {

    public CustomerNotFoundException(String message) {
        super(message);
    }
}
