package com.ga.disclosure.workflow.contract;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.RuleVersionId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 계약 연결 저장소(V14 {@code contract_link}·{@code contract_link_unmatched}, 확인서 현재값·보존기한). 바인딩된 테넌트 트랜잭션 안에서 부른다.
 * 정정 순서는 이전 행 대체 → 새 활성 행 → 확인서 현재값이다(활성 1건 부분 유일 — 대체 대상은 지연 외래키, 정합은 커밋 때 V15 GD136).
 */
public interface ContractLinkStore {

    /** 배치 원장(V18 — 6B 중간 회신 ①): 그 출처·배치 ID로 받은 내용 해시와 완료 여부. */
    Optional<LedgerEntry> batch(String source, String batchId);

    /** 수신 기록(요약 없음). */
    void recordBatch(String source, String batchId, String sha256, int items, Instant receivedAt, String receivedBy);

    /** 요약을 한 번 쓴다(이미 있으면 그대로 — 재생). 썼으면 true. */
    boolean completeBatch(String source, String batchId, String summaryJson, Instant at);

    record LedgerEntry(String sha256, Instant receivedAt, boolean completed) {
    }

    /** 같은 출처 참조로 이미 만든 연결(재수입 멱등). */
    Optional<UUID> linkBySource(String source, String sourceRef);

    /** 같은 출처 참조로 이미 남긴 보고 행의 사유. */
    Optional<String> unmatchedBySource(String source, String sourceRef);

    /** 청약번호가 정확히 같은 확인서 — 무효·정정됨·폐기·파기 제외(봉인 전은 포함 — NOT_SEALED 판정). */
    List<Candidate> byApplicationNo(String applicationNo);

    /** 그 증권번호의 활성 연결을 가진 확인서 — 같은 제외. */
    List<Candidate> byActivePolicy(String policyNo);

    /**
     * 그 증권번호의 활성 연결을 가진 확인서 전부 — 상태 제외 없음. 무효·정정된 확인서의 활성 연결도 증권을 붙들고 있다(활성 증권 부분 유일). "다른 확인서에
     * 활성" 판정은 이것으로 한다.
     */
    List<DisclosureId> activePolicyHolders(String policyNo);

    Optional<ActiveLink> activeLink(DisclosureId disclosure);

    void supersede(UUID linkId, UUID supersededBy, Instant at);

    void insertLink(NewLink link);

    /** 확인서 현재값을 활성 연결로 맞춘다(V14 GD136 — 같지 않으면 거부). */
    void mirror(DisclosureId disclosure, String policyNo, LocalDate contractDate);

    /** 보존기한 연장(더 늦을 때만 — GD094가 단축을 막는다). 바뀌었으면 true. */
    boolean extendRetention(DisclosureId disclosure, LocalDate until);

    void insertUnmatched(UUID id, ContractLinkBatch.Item item, String reason, String source, String sourceRef, Instant receivedAt);

    /** {@code receivedBefore} 이전에 받은 보고 행을 지운다(오래된 순, 최대 {@code limit}). 지운 수. */
    int purgeUnmatched(Instant receivedBefore, int limit);

    /** 매칭 후보 확인서의 사실(개인정보 없음 — 고객은 가명). */
    record Candidate(DisclosureId id, String status, Optional<String> disclosureNo, String customerRef, LocalDate consultDate,
                     RuleVersionId globalRule, Optional<RuleVersionId> tenantRule, Optional<LocalDate> retentionUntil) {
        public Candidate {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(disclosureNo, "disclosureNo");
            Objects.requireNonNull(customerRef, "customerRef");
            Objects.requireNonNull(consultDate, "consultDate");
            Objects.requireNonNull(globalRule, "globalRule");
            Objects.requireNonNull(tenantRule, "tenantRule");
            Objects.requireNonNull(retentionUntil, "retentionUntil");
        }
    }

    record ActiveLink(UUID linkId, String policyNo, Optional<String> applicationNo, LocalDate contractDate, String insurerCode,
                      Optional<String> productKey) {
        @Override
        public String toString() {
            return "ActiveLink[" + linkId + "]";
        }
    }

    record NewLink(UUID linkId, DisclosureId disclosure, String policyNo, Optional<String> applicationNo, LocalDate contractDate,
                   String insurerCode, Optional<String> productKey, String source, String sourceRef, Instant receivedAt, String linkedBy) {
        @Override
        public String toString() {
            return "NewLink[" + linkId + "]";
        }
    }
}
