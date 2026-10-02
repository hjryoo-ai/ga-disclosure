package com.ga.disclosure.domain.enums;

/**
 * 보존기한 앵커(3B 수용심사 §3-3, 룰 {@code retentionAnchors}): {@code retention_until = max(앵커 날짜 + retentionYears)}를 앵커가
 * 생길 때마다 다시 계산해 연장만 한다. SEAL = 봉인일, COMPLETION = 완료일(Asia/Seoul), CONTRACT_DATE = 계약일(Phase 6 계약 연결).
 */
public enum RetentionAnchor {
    SEAL,
    COMPLETION,
    CONTRACT_DATE
}
