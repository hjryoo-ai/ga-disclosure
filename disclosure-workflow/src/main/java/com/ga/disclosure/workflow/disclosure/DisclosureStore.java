package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.DisclosureId;

import java.util.Optional;

/**
 * 확인서 저장소 포트(infra 어댑터). 상태를 바꾸는 경로는 애그리게이트 → {@link #save}뿐이다 — 상태 컬럼만 바꾸는 메서드를 두지 않는다
 * (3A 지시문 "하지 말 것"). {@code disclosure}·{@code disclosure_item}·{@code recommendation}을 쓰는 SQL은 어댑터의 insert·save에만
 * 있다(아키텍처 스캔 테스트). 바인딩된 테넌트의 트랜잭션 안에서 호출된다.
 */
public interface DisclosureStore {

    void insert(Disclosure disclosure);

    /** 행 잠금({@code FOR UPDATE})으로 읽는다. 명령은 전부 이 경로로 읽고 같은 트랜잭션에서 {@link #save}한다. */
    Optional<DisclosureRecord> loadForUpdate(DisclosureId id);

    void save(Disclosure disclosure);

    /**
     * 초안 고정(생성·정정 새 버전·재기준) 때 감사에 기록한 유효 룰 본문 해시 — 그 확인서를 대상으로 한 마지막 {@code DISCLOSURE_CREATE}·
     * {@code DISCLOSURE_REBASE} 행의 {@code ruleBodyHash}(감사 로그는 append-only, 4 계획 승인 Q1 로더 조건). 없으면 빈 값.
     */
    Optional<String> pinnedRuleBodyHash(DisclosureId id);
}
