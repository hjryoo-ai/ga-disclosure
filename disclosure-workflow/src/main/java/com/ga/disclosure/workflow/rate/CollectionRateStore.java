package com.ga.disclosure.workflow.rate;

import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.metric.CollectionRates;
import com.ga.disclosure.workflow.authz.ListScope;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** 징구율 입력·스냅샷 저장(V14 {@code collection_rate_snapshot} — append-only GD135, 유일 (달, 조직, 룰 버전)). */
public interface CollectionRateStore {

    /**
     * 계약일이 [{@code from}, {@code toExclusive})인 <b>활성</b> 계약 연결(정정·이전으로 닫히지 않은 행 — {@code superseded_by}·{@code carried_to} 모두 NULL)과
     * 그 확인서의 사실. 파기·폐기된 확인서도 돌려준다 — 빼는 것은 산식이 한다(한 곳).
     */
    List<CollectionRates.Linked> linked(LocalDate from, LocalDate toExclusive);

    /**
     * 계약일이 [{@code from}, {@code toExclusive})인 미매칭(사유 UNMATCHED) 증권의 키(증권번호의 SHA-256 — 번호 자체는 싣지 않는다), 지금 활성 연결이 있는
     * 증권은 뺀다(같은 증권은 한 번). 안 B만 쓴다.
     */
    List<String> unmatchedPolicyKeys(LocalDate from, LocalDate toExclusive);

    /** 그 달·룰 버전의 스냅샷 행이 하나라도 있는가. */
    boolean exists(LocalDate periodMonth, RuleVersionId ruleVersion);

    void insert(Row row);

    /** 기간 [{@code fromMonth}, {@code toMonth}]의 행 — 범위({@code ORG}면 조직 아래 행만, 테넌트 전체 행 없음), 조직 경로가 주어지면 그 행만. */
    List<Row> list(ListScope scope, LocalDate fromMonth, LocalDate toMonth, Optional<String> orgPath);

    record Row(UUID snapshotId, LocalDate periodMonth, String orgPath, String formula, int denominator, int numerator, OptionalInt rateBp,
               Instant computedAt, RuleVersionId ruleVersionId, String inputsHash, UUID jobId) {
        public Row {
            Objects.requireNonNull(snapshotId, "snapshotId");
            Objects.requireNonNull(periodMonth, "periodMonth");
            Objects.requireNonNull(orgPath, "orgPath");
            Objects.requireNonNull(formula, "formula");
            Objects.requireNonNull(rateBp, "rateBp");
            Objects.requireNonNull(computedAt, "computedAt");
            Objects.requireNonNull(ruleVersionId, "ruleVersionId");
            Objects.requireNonNull(inputsHash, "inputsHash");
            Objects.requireNonNull(jobId, "jobId");
        }
    }
}
