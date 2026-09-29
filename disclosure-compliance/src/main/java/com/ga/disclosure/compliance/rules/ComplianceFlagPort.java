package com.ga.disclosure.compliance.rules;

import java.time.Instant;
import java.util.UUID;

/** 준법 플래그 기록 포트(설계서 §5 {@code compliance_flag}). */
public interface ComplianceFlagPort {

    /** 확인서·계약에 묶이지 않은 플래그(예: RULE_DRIFT)를 올리고 ID를 돌려준다. */
    UUID raise(String type, String severity, Instant raisedAt);
}
