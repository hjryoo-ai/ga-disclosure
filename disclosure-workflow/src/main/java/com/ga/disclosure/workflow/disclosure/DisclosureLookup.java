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

    /** 한 건(잠그지 않는다 — 조회 API). 없으면 빈 값. */
    Optional<DisclosureRecord> load(DisclosureId id);

    /** 파기 시각(보존기간 종료 파기 뒤의 묘비). */
    Optional<java.time.Instant> destroyedAt(DisclosureId id);

    /**
     * 목록 한 쪽(6A 계획 §4.1): 범위 조건을 SQL로 건다 — 범위 밖 행은 없는 행이다. 정렬은 상담일 내림차순, 같은 날은 ID 내림차순(키셋 {@link Position}).
     * {@code limit}행까지.
     */
    List<Listed> page(com.ga.disclosure.workflow.authz.ListScope scope, Optional<com.ga.disclosure.domain.enums.DisclosureStatus> status,
                      Optional<Position> after, int limit);

    /** 목록 키셋 위치. */
    record Position(LocalDate consultDate, DisclosureId id) {
    }

    /** 목록 한 행(본문 없음 — 고객은 가명 참조만). */
    record Listed(DisclosureId id, Optional<String> disclosureNo, int version, com.ga.disclosure.domain.enums.DisclosureStatus status, String agentId,
                  CustomerRef customerRef, GroupCode group, LocalDate consultDate, Optional<java.time.Instant> sealedAt,
                  Optional<java.time.Instant> destroyedAt) {
    }

    /** 상태·버전·계보만(본문 없음). */
    record Summary(DisclosureId id, com.ga.disclosure.domain.enums.DisclosureStatus status, int version, DisclosureId supersedesIdOrNull) {
    }
}
