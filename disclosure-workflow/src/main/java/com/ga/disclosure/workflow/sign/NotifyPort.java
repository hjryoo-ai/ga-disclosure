package com.ga.disclosure.workflow.sign;

import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.domain.pii.PhoneNumber;

/**
 * 원격 서명 링크 통지 포트(설계서 §6.5, 6A 계획 §7). 번호는 언제나 {@code customer_ref.phone_enc}를 복호화한 값이다(감사 {@code CUSTOMER_PHONE_READ}) —
 * 설계사가 임의 번호로 보낼 수 없다. 부르는 곳은 통지 디스패처 하나다(아웃박스 행마다 한 트랜잭션). 실패는 {@link NotifyFailure}(닫힌 코드)로 알린다 —
 * 디스패처가 그 트랜잭션을 롤백하고 재시도를 예약한다. 링크 원문은 통지 본문 한 곳에만 나가고 로그에 쓰지 않는다. 실 사업자(푸시·알림톡) 어댑터는 운영 결정이다.
 */
public interface NotifyPort {

    void sendSignLink(Sensitive<PhoneNumber> to, SignLink link);
}
