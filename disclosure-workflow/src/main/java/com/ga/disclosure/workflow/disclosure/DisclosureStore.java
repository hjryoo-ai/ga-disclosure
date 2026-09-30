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
}
