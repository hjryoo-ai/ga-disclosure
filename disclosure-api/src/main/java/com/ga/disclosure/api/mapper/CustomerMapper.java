package com.ga.disclosure.api.mapper;

import com.ga.disclosure.api.dto.CustomerReceipt;
import com.ga.disclosure.api.dto.CustomerRegisterRequest;
import com.ga.disclosure.api.dto.IdempotencyKey;
import com.ga.disclosure.api.error.MalformedRequestException;
import com.ga.disclosure.domain.pii.BirthDate;
import com.ga.disclosure.domain.pii.CustomerName;
import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.customer.CustomerRegistrationService;
import com.ga.disclosure.workflow.customer.NewCustomer;
import com.ga.disclosure.workflow.customer.RegistrationKey;
import com.ga.platform.canonical.Sha256;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * 고객 등록 매핑(6B §9.2·§9.3, 승인 §4 조건 6): DTO → Phase 2 값객체 변환(검증은 값객체가 한다 — 실패는 그 필드 이름만, 값 없음)과 등록 키 도출뿐이다.
 * 판단(중복·한도·생성 여부)은 없다.
 */
public final class CustomerMapper {

    private CustomerMapper() {
    }

    /** 생년월일 상한 {@code today}는 유스케이스의 기준일(KST)이다. */
    public static NewCustomer customer(CustomerRegisterRequest r, LocalDate today) {
        if (r == null) {
            throw new MalformedRequestException(null);
        }
        Sensitive<CustomerName> name = field("name", () -> CustomerName.of(r.name()));
        Sensitive<PhoneNumber> phone = r.phone() == null ? null : field("phone", () -> PhoneNumber.of(r.phone()));
        Sensitive<BirthDate> birthDate = r.birthDate() == null ? null : field("birthDate", () -> BirthDate.parse(r.birthDate(), today));
        return new NewCustomer(name, phone, birthDate);
    }

    /** 등록 키 = {@code api:} + hex(SHA-256(주체 ‖ 0x00 ‖ Idempotency-Key))[0..40] — 같은 주체의 같은 키는 같은 고객, 다른 주체는 다른 고객. */
    public static RegistrationKey registrationKey(Caller caller, IdempotencyKey key) {
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        input.writeBytes(caller.subject().getBytes(StandardCharsets.UTF_8));
        input.write(0);
        input.writeBytes(key.value().getBytes(StandardCharsets.UTF_8));
        return new RegistrationKey("api:" + Sha256.of(input.toByteArray()).substring(0, 40));
    }

    public static CustomerReceipt receipt(CustomerRegistrationService.Receipt r) {
        return new CustomerReceipt(r.ref().value(), r.receiptId().toString());
    }

    private static <T> T field(String name, java.util.function.Supplier<T> parse) {
        try {
            return parse.get();
        } catch (RuntimeException e) {
            throw new MalformedRequestException(name);
        }
    }
}
