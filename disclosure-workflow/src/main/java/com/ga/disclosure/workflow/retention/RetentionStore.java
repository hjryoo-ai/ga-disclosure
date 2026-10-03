package com.ga.disclosure.workflow.retention;

import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.vo.CustomerRef;
import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.RuleVersionId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 파기 판정 재료 읽기(바인딩된 테넌트, RLS — infra가 구현). */
public interface RetentionStore {

    /** 봉인된 종료 상태 ∧ {@code retention_until < today} ∧ 미파기(보존기한 오름차순, 상한). */
    List<DisclosureId> candidates(LocalDate today, int limit);

    Optional<Candidate> candidate(DisclosureId id, LocalDate today);

    /** 아직 파기되지 않은 고객(등록 순, 상한). */
    List<CustomerRef> customerCandidates(int limit);

    Optional<CustomerState> customer(CustomerRef ref);

    /** 그 고객의 봉인된 확인서(보류의 저장소 대상). */
    List<DisclosureId> sealedDisclosuresOf(CustomerRef ref);

    /**
     * @param locksExpired 모든 산출물·서명 증거 행의 {@code retention_applied_until < today}(NULL이면 거짓, 5 계획 §5.2 ⓪)
     * @param keyLive      문서 데이터 키가 아직 살아 있다(① 전)
     */
    record Candidate(DisclosureId id, DisclosureStatus status, String disclosureNoOrNull, LocalDate consultDate, RuleVersionId ruleVersion,
                     RuleVersionId tenantRuleVersionOrNull, LocalDate retentionUntil, Instant sealedAt, Instant completedAtOrNull,
                     LocalDate contractDateOrNull, Instant destroyedAtOrNull, CustomerRef customer, boolean held, boolean locksExpired, boolean keyLive) {
    }

    /**
     * @param live 그 고객의 확인서 중 {@code destroyed_at IS NULL}인 것(봉인 전 초안 포함 — 초안 파기는 범위 밖, 5 계획 §5.3)
     */
    record CustomerState(CustomerRef ref, Instant createdAt, Instant destroyedAtOrNull, int disclosures, int live, Instant lastDestroyedAtOrNull,
                         boolean held) {
    }
}
