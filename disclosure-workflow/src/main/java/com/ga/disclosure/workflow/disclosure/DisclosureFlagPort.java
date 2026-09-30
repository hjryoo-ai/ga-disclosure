package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.DisclosureId;

import java.time.Instant;
import java.util.UUID;

/**
 * 확인서 준법 플래그 포트(설계서 §5 {@code compliance_flag}). 같은 유형·대상의 <b>열린</b> 플래그가 있으면 새 행 없이 그 플래그를
 * 돌려준다(V5 부분 유일 인덱스). 워크플로가 올리는 유형은 {@link Type}뿐이다.
 */
public interface DisclosureFlagPort {

    enum Type {
        /** 엔진 응답이 계약 스키마·정합성 검증을 통과하지 못해 스냅샷을 만들지 않았다(대상 = 확인서). */
        GRADE_INCONSISTENT,
        /** 오버라이드 가능한 검증 실패가 있다 — 봉인 전에 관리자 승인이 필요하다(대상 = 확인서/규칙 ID, 3A 계획 Q2). */
        VALIDATION_OVERRIDE
    }

    RaisedFlag raise(Type type, String severity, DisclosureId disclosureId, String targetKind, String targetId, Instant raisedAt);

    record RaisedFlag(UUID flagId, boolean created) {
    }
}
