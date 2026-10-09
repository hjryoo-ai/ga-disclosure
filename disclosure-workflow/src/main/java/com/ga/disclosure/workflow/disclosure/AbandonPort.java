package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.DisclosureId;

import java.time.Instant;

/**
 * 초안 폐기 함수 호출(V14 {@code ga_draft_abandon} — 실행은 전용 롤 {@code disclosure_abandoner}만, 6B 계획 Q10). 호출자의 테넌트 트랜잭션 안에서
 * 부른다. 함수가 상태를 {@code ABANDONED}로 옮기며 자유 텍스트·청약번호를 지운다 — 그 밖의 경로로는 이 상태가 될 수 없다(GD133). 거부는
 * {@link AbandonRefusedException}(SQLSTATE만).
 */
public interface AbandonPort {

    void abandon(DisclosureId disclosure, Instant at, String by);
}
