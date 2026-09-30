package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.GroupCode;

import java.time.LocalDate;
import java.util.List;

/** 확인서 조회(읽기 전용, 상태를 바꾸지 않는다). 바인딩된 테넌트의 트랜잭션 안에서 호출된다. */
public interface DisclosureLookup {

    /** 같은 고객·상담일·상품군의 확인서 ID(생성 순). 데모 시드의 2회 실행 NOOP 판정에 쓴다 — 운영 동작의 규칙이 아니다. */
    List<DisclosureId> findFor(CustomerRef customer, LocalDate consultDate, GroupCode group);
}
