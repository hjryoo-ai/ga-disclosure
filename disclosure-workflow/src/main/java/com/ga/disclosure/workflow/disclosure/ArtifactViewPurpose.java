package com.ga.disclosure.workflow.disclosure;

/**
 * 산출물 열람 감사({@code ARTIFACT_VIEW})의 목적 {@code reason}(Phase 7 계획 ⑥): 고객 서명 화면의 봉인 PDF 열람과 직원 화면의 워터마크 미리보기. 직원의
 * 산출물 내려받기({@code getArtifact})는 목적 없이 기록한다(6A부터 그대로 — 감사 값 불변).
 */
public enum ArtifactViewPurpose {
    /** 고객 공개 서명 경로의 봉인 PDF 열람(워터마크 없음). */
    SIGN,
    /** 직원 화면의 미리보기(봉인 PDF에 워터마크 — 저장하지 않는다). */
    PREVIEW
}
