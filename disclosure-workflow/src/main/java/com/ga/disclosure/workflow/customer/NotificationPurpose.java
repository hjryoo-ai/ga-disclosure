package com.ga.disclosure.workflow.customer;

/** 연락처 복호화가 허용되는 목적(설계서 §6.5: 발송 번호는 {@code customer_ref.phone_enc}에서만, 원격 서명 링크 발송 전용). */
public enum NotificationPurpose {
    REMOTE_LINK
}
