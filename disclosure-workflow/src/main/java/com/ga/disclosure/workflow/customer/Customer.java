package com.ga.disclosure.workflow.customer;

import com.ga.disclosure.domain.pii.BirthDate;
import com.ga.disclosure.domain.pii.CustomerName;
import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.domain.vo.CustomerRef;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 복호화된 고객 참조. 개인정보는 {@link Sensitive}로만 들고 있다 — 이 클래스는 {@code record}가 아니다(아키텍처 규칙:
 * {@code Sensitive}를 컴포넌트로 가진 record 금지). {@code toString}은 참조 ID와 항목 존재 여부만.
 */
public final class Customer {

    private final CustomerRef ref;
    private final Sensitive<CustomerName> name;
    private final Sensitive<PhoneNumber> phone;
    private final Sensitive<BirthDate> birthDate;
    private final String keyId;
    private final Instant createdAt;

    public Customer(CustomerRef ref, Sensitive<CustomerName> name, Sensitive<PhoneNumber> phoneOrNull, Sensitive<BirthDate> birthDateOrNull,
                    String keyId, Instant createdAt) {
        this.ref = Objects.requireNonNull(ref, "ref");
        this.name = Objects.requireNonNull(name, "name");
        this.phone = phoneOrNull;
        this.birthDate = birthDateOrNull;
        this.keyId = Objects.requireNonNull(keyId, "keyId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public CustomerRef ref() {
        return ref;
    }

    public Sensitive<CustomerName> name() {
        return name;
    }

    public Optional<Sensitive<PhoneNumber>> phone() {
        return Optional.ofNullable(phone);
    }

    public Optional<Sensitive<BirthDate>> birthDate() {
        return Optional.ofNullable(birthDate);
    }

    /** 이 행의 암호문을 만든 데이터 키 ID. */
    public String keyId() {
        return keyId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "Customer[" + ref + ", name=" + name + ", phone=" + (phone == null ? "-" : phone) + ", birthDate="
                + (birthDate == null ? "-" : birthDate) + ", key=" + keyId + "]";
    }
}
