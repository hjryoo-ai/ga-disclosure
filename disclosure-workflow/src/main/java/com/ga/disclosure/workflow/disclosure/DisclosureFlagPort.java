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
        RULE_SUPERSEDED_DRAFT,
        /** 본인확인 실패가 룰 {@code identityCheck.maxFailures}에 닿아 세션을 취소했다(대상 = 세션, Phase 4). */
        IDENTITY_FAILED,
        /** 대리 서명 의심 — 기기·IP 재사용 또는 발송 직후 서명(대상 = 서명, 4 계획 §5). 서명을 막지 않는다. */
        SIGNATURE_DEVICE_REUSE,
        /** 종이 스캔 서명의 관리자 검토 대기(대상 = 서명, 룰 {@code channels.PAPER_SCAN.requiresManagerReview}). 열려 있으면 완료되지 않는다. */
        PAPER_SCAN_REVIEW,
        /** 서명 기한이 지나 만료됐다(대상 = 확인서, 4 계획 §7.4) — 준법 점검 대상(재작성·사후 확인). */
        SIGN_EXPIRED,
        /**
         * {@code verify tenant}가 무결성 불일치를 찾았다(대상 = 끊긴 지점의 확인서, 정할 수 없으면 테넌트 — 5 계획 §8.4). 문서 상태로 닫지 않는다 — 준법이
         * 조사해 해소한다.
         */
        CHAIN_BROKEN
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
        REBASED,
        /** 종이 스캔을 관리자 확인 또는 예외 승인 역할이 검토했다(4 계획 승인 Q10). */
        PAPER_SCAN_REVIEWED
    }

    RaisedFlag raise(Type type, String severity, DisclosureId disclosureId, String targetKind, String targetId, Instant raisedAt);

    /** 확인서가 없는 플래그({@code disclosure_id NULL} — 테넌트 수준 {@code CHAIN_BROKEN} 등). 같은 대상의 열린 플래그가 있으면 그것. */
    RaisedFlag raiseUnattached(Type type, String severity, String targetKind, String targetId, Instant raisedAt);

    /** 확인서의 열린 플래그(이 포트의 유형만). */
    List<OpenFlag> openFor(DisclosureId disclosureId);

    /** 확인서에 걸린 플래그 전부(유형 무관, 열림·닫힘) — 관리자 확인의 사유 확인 대상(4 계획 승인 Q9). */
    List<FlagSummary> allFor(DisclosureId disclosureId);

    /** 열린 플래그 하나를 해소한다(이미 닫혔으면 false — 해소는 한 번). */
    boolean resolve(UUID flagId, Resolution resolution, String resolvedBy, Instant at);

    record RaisedFlag(UUID flagId, boolean created) {
    }

    /** 플래그 1건 요약: ID·유형(데이터 문자열 — 준법 배치 유형 포함)·열림 여부. */
    record FlagSummary(UUID flagId, String type, boolean open) {
        public FlagSummary {
            Objects.requireNonNull(flagId, "flagId");
            Objects.requireNonNull(type, "type");
        }
    }

    record OpenFlag(UUID flagId, Type type, String targetKind, String targetId) {
        public OpenFlag {
            Objects.requireNonNull(flagId, "flagId");
            Objects.requireNonNull(type, "type");
        }
    }
}
