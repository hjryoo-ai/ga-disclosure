package com.ga.disclosure.workflow.flag;

import com.ga.disclosure.workflow.RejectionCategory;

/**
 * 준법 큐 명령의 업무 거부(6B 계획 §7). 거부 감사는 업무 트랜잭션에서 커밋된 뒤 던진다. 메시지에 근거 값을 싣지 않는다.
 */
public final class FlagRejectedException extends RuntimeException {

    /** 거부 코드와 범주(설계서 §7 {@code rejection-categories} 블록 — {@code RejectionCategoryTableTest}). */
    public enum Rejection implements RejectionCategory.Categorized {
        /** 룰 {@code resolutionCodes}가 비어 있다 — 문서 상태·전용 유스케이스로만 닫힌다. */
        NOT_MANUALLY_RESOLVABLE(RejectionCategory.INVALID),
        /** 그 유형의 해소 코드 목록 밖. */
        RESOLUTION_CODE_UNKNOWN(RejectionCategory.INVALID),
        /** 근거가 필요한데 없거나 모양이 틀렸다(유형별 닫힌 모양). */
        EVIDENCE_REQUIRED(RejectionCategory.INVALID),
        /** 근거를 받지 않는 유형에 근거가 실렸다(자유 형식 근거를 저장하지 않는다). */
        EVIDENCE_NOT_ACCEPTED(RejectionCategory.INVALID),
        /** {@code CHAIN_BROKEN}의 근거 작업이 조건(같은 테넌트·VERIFY_TENANT·SUCCEEDED·플래그 뒤 시작·감사 MATCH)을 채우지 못했다 — 세부는 감사에만. */
        CHAIN_EVIDENCE_REJECTED(RejectionCategory.INVALID),
        /** 관리자는 담당 역할이 MANAGER인 플래그만 배정한다(조직 범위는 인가가 먼저 본다). */
        ROLE_NOT_ASSIGNED(RejectionCategory.INVALID),
        /** 담당자가 그 플래그의 담당 역할로 연결된 주체가 아니다. */
        ASSIGNEE_INVALID(RejectionCategory.INVALID),
        /** 이미 닫혔다(재개 없음 — 다시 생기면 새 플래그). */
        ALREADY_RESOLVED(RejectionCategory.CONFLICT);

        private final RejectionCategory category;

        Rejection(RejectionCategory category) {
            this.category = category;
        }

        @Override
        public RejectionCategory category() {
            return category;
        }
    }

    private final Rejection rejection;

    public FlagRejectedException(Rejection rejection) {
        super("flag command rejected: " + rejection.name());
        this.rejection = rejection;
    }

    public Rejection rejection() {
        return rejection;
    }
}
