package com.ga.disclosure.workflow.sign;

import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.sign.token.SignToken;

/**
 * 원격 서명 링크 통지 포트(설계서 §6.5, 4 계획 §7.6). 번호는 언제나 {@code customer_ref.phone_enc}를 복호화한 값이다(감사 {@code CUSTOMER_PHONE_READ}) —
 * 설계사가 임의 번호로 보낼 수 없다. 링크 URL은 배포 설정이라 구현이 토큰으로 만든다. 토큰 원문은 통지 본문 한 곳에만 나가고 로그에 쓰지 않는다.
 * 실연동(푸시·알림톡)은 Phase 6.
 */
public interface NotifyPort {

    void sendSignLink(Sensitive<PhoneNumber> to, SignToken token);
}
