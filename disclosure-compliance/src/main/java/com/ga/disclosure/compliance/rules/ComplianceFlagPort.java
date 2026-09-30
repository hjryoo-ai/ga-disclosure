package com.ga.disclosure.compliance.rules;

import java.time.Instant;
import java.util.UUID;

/** 준법 플래그 기록 포트(설계서 §5 {@code compliance_flag}). */
public interface ComplianceFlagPort {

    /**
     * 대상(예: {@code RULE_VERSION}/룰 ID)에 플래그를 올린다. 같은 유형·대상의 <b>열린</b> 플래그가 이미 있으면 새 행 없이 그
     * 플래그를 돌려준다({@code created=false}) — 일 배치 재실행이 플래그를 복제하지 않는다(DB 부분 유일 인덱스가 이중으로 막는다).
     */
    RaisedFlag raiseOpen(String type, String severity, String targetKind, String targetId, Instant raisedAt);

    record RaisedFlag(UUID flagId, boolean created) {
    }
}
