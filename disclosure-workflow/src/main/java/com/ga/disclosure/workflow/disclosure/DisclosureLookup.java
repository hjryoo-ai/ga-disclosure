package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.GroupCode;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 확인서 조회(읽기 전용, 상태를 바꾸지 않는다). 바인딩된 테넌트의 트랜잭션 안에서 호출된다. */
public interface DisclosureLookup {

    /** 같은 고객·상담일·상품군의 확인서 ID(생성 순). 데모 시드의 2회 실행 NOOP 판정에 쓴다 — 운영 동작의 규칙이 아니다. */
    List<DisclosureId> findFor(CustomerRef customer, LocalDate consultDate, GroupCode group);

    /** 같은 고객·상담일·상품군의 확인서 요약(버전 순). 데모 시드의 봉인·정정 NOOP 판정용(3B). */
    List<Summary> summariesFor(CustomerRef customer, LocalDate consultDate, GroupCode group);

    /**
     * 봉인 각주(확인서 번호·canonical 해시) — 봉인 PDF 각주와 같은 값. 데모 종이 스캔이 각주를 "읽는" 대신 쓴다(운영 경로는 설계사가 스캔본에서 읽어
     * 입력한다, 4 계획 §7.2). 봉인 전이면 빈 값.
     */
    Optional<Footnote> footnote(DisclosureId id);

    record Footnote(String disclosureNo, String canonicalHash) {
    }

    /** 상태·버전·계보만(본문 없음). */
    record Summary(DisclosureId id, com.ga.disclosure.domain.enums.DisclosureStatus status, int version, DisclosureId supersedesIdOrNull) {
    }
}
