package com.ga.disclosure.domain.pii;

import com.ga.disclosure.domain.enums.PiiField;

/** {@link Sensitive}에 담기는 개인정보 값. 구현은 이 패키지의 세 종류뿐이다. */
public sealed interface SensitiveValue permits CustomerName, PhoneNumber, BirthDate {

    PiiField field();

    /** 정규화된 원문(암호화·상수 시간 비교의 입력). 호출할 수 있는 곳은 {@code reveal} 안뿐이다. */
    String canonical();
}
