package com.ga.disclosure.workflow.customer;

import com.ga.disclosure.domain.pii.BirthDate;
import com.ga.disclosure.domain.pii.CustomerName;
import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.disclosure.domain.pii.Sensitive;

import java.util.Objects;
import java.util.Optional;

/** 등록할 고객 정보(최소: 이름 필수, 연락처·생년월일 선택). record가 아니다 — {@link Customer}와 같은 이유. */
public final class NewCustomer {

    private final Sensitive<CustomerName> name;
    private final Sensitive<PhoneNumber> phone;
    private final Sensitive<BirthDate> birthDate;

    public NewCustomer(Sensitive<CustomerName> name, Sensitive<PhoneNumber> phoneOrNull, Sensitive<BirthDate> birthDateOrNull) {
        this.name = Objects.requireNonNull(name, "name");
        this.phone = phoneOrNull;
        this.birthDate = birthDateOrNull;
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

    @Override
    public String toString() {
        return "NewCustomer[name=" + name + ", phone=" + (phone == null ? "-" : phone) + ", birthDate=" + (birthDate == null ? "-" : birthDate) + "]";
    }
}
