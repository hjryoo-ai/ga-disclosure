package com.ga.disclosure.rules.resolve;

/**
 * 징구율 산식 ID(룰 {@code collectionRate.formula}, 6B 계획 §5·승인 §3). <b>내부 지표 — 규제 정의 없음</b>(설계서 §14 #17): 규제 정의가
 * 발견되면 값을 더하고, 스냅샷은 산식 ID와 룰 버전으로 구분한다. TODO(confirm#17)
 */
public enum CollectionRateFormula {
    /** 기본(안 A): 분모 = 기준월(계약일 KST 달) 활성 연결, 분자 = 그중 연결 확인서가 COMPLETED이고 완료일(KST) ≤ 계약일. */
    LINKED_COMPLETED_BY_CONTRACT_DATE,
    /** 대안(안 B): 분모에 같은 달 미매칭(UNMATCHED, 같은 증권은 한 번)을 더한다 — 테넌트 전체 행에만. 분자는 안 A와 같다. */
    TARGET_INCLUDING_UNMATCHED
}
