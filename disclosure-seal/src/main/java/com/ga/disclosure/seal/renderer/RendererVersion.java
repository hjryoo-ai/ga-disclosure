package com.ga.disclosure.seal.renderer;

/**
 * 렌더러 판(Phase 8, 8 계획 ⑥·승인 Q9). 문서는 봉인 때의 판에 <b>고정</b>된다({@code document_artifact.renderer_version}, write-once) — 서명본 덧붙임·미리보기
 * 워터마크·재렌더는 저장된 판으로 한다. 그래야 렌더러를 고쳐도 기존 문서의 "재렌더 바이트 동일"(Phase 7 G0)이 참으로 남는다.
 * <ul>
 *   <li>{@link #V1} — 3B~7. <b>동결</b>: 이 판의 출력은 골든 4건이 바이트로 지킨다(코드를 바꾸면 골든이 실패한다 — 그것이 검사다).</li>
 *   <li>{@link #V2} — V1 + 서식 {@code render.columns}(객체 배열 값을 머리행 + 서식이 정한 열의 표 하나로)와 그 CSS, 생산자 문자열.</li>
 * </ul>
 * V1은 {@code render.columns}가 있는 서식을 그리지 않는다(조용히 무시하지 않고 거부) — 허용 조합은 (서식 v1 × 판 1·2), (서식 v2 × 판 2).
 */
public enum RendererVersion {
    V1(1),
    V2(2);

    /** 새 봉인이 쓰는 판. */
    public static final RendererVersion CURRENT = V2;

    private final int number;

    RendererVersion(int number) {
        this.number = number;
    }

    public int number() {
        return number;
    }

    /** PDF 정보 {@code /Producer}·XMP {@code pdf:Producer}. */
    public String producer() {
        return "ga-disclosure-renderer/" + number;
    }

    public static RendererVersion of(int number) {
        for (RendererVersion v : values()) {
            if (v.number == number) {
                return v;
            }
        }
        throw new IllegalArgumentException("unknown renderer version " + number);
    }
}
