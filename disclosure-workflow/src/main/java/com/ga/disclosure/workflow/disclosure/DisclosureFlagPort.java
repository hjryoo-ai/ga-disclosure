package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.vo.DisclosureId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 확인서 준법 플래그 포트(설계서 §5 {@code compliance_flag}). 같은 유형·대상의 <b>열린</b> 플래그가 있으면 새 행 없이 그 플래그를
 * 돌려준다(V5 부분 유일 인덱스). 워크플로가 올리는 유형은 {@link Type}뿐이고, 해소 사유는 닫힌 어휘 {@link Resolution}이다(3A 수용심사 §3-7,
 * 3B 계획 승인 Q9).
 */
public interface DisclosureFlagPort {

    enum Type {
        /** 엔진 응답이 계약 스키마·정합성 검증을 통과하지 못해 스냅샷을 만들지 않았다(대상 = 확인서). 엔진 이상 신호라 문서 상태 변경으로 닫지 않는다. */
        GRADE_INCONSISTENT,
        /** 오버라이드 가능한 검증 실패가 있다 — 봉인 전에 관리자 승인이 필요하다(대상 = 확인서/규칙 ID, 3A 계획 Q2). */
        VALIDATION_OVERRIDE,
        /** 소급 배포로 고정 룰·서식이 상담일 재해석과 다르다 — 봉인 거부, 재기준 필요(대상 = 확인서, 3B 봉인 조건 ①·②). */
        RULE_SUPERSEDED_DRAFT
    }

    /** 해소 사유. */
    enum Resolution {
        /** 봉인 성공 — 그 실패를 덮는 승인이 있었다(해소자 = 승인자). */
        APPROVED,
        /** 봉인 성공 — 봉인 시점에는 그 규칙이 더는 실패하지 않았다(해소자 SYSTEM). */
        RESOLVED_AT_SEAL,
        /** 무효·정정으로 문서가 닫혔다. */
        SUPERSEDED_BY_DOCUMENT_STATE,
        /** 재기준으로 새 룰·서식에 고정됐다. */
        REBASED
    }

    RaisedFlag raise(Type type, String severity, DisclosureId disclosureId, String targetKind, String targetId, Instant raisedAt);

    /** 확인서의 열린 플래그. */
    List<OpenFlag> openFor(DisclosureId disclosureId);

    /** 열린 플래그 하나를 해소한다(이미 닫혔으면 false — 해소는 한 번). */
    boolean resolve(UUID flagId, Resolution resolution, String resolvedBy, Instant at);

    record RaisedFlag(UUID flagId, boolean created) {
    }

    record OpenFlag(UUID flagId, Type type, String targetKind, String targetId) {
        public OpenFlag {
            Objects.requireNonNull(flagId, "flagId");
            Objects.requireNonNull(type, "type");
        }
    }
}
