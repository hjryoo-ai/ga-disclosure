package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.DisclosureNo;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.workflow.Actor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 정정 새 버전의 봉인 트랜잭션에서 선행 버전의 활성 계약 연결을 이월한다(6B 중간 회신 ③, 2026-10-09 답변 — 봉인 때 이월, 초안에는 연결이 없다는 V15
 * GD130은 그대로). 구현은 {@code workflow.contract}(의존 방향: contract → disclosure).
 */
public interface LinkCarry {

    /**
     * 선행 버전에 활성 연결이 있으면 새 버전으로 옮긴다(옛 행 {@code carried_to}, 새 행, 현재값 투영, 보존기한은 앵커 {@code CONTRACT_DATE}로 연장만,
     * 감사 {@code CONTRACT_LINK_CARRIED}, 아웃박스 {@code PolicyLinked}). 돌려주는 값은 새 버전의 보존기한(연장됐으면 늦은 값, 아니면 그대로).
     */
    LocalDate carryOnSeal(Actor actor, DisclosureId sealed, DisclosureNo number, DisclosureId predecessor, EffectiveRule pinned, LocalDate retentionUntil,
                          Instant at);
}
