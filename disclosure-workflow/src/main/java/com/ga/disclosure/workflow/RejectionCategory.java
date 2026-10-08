package com.ga.disclosure.workflow;

/**
 * 업무 거부의 범주(6A 계획 §4.2) — HTTP 상태는 이 값을 기계적으로 바꿀 뿐이다({@code CONFLICT → 409}, {@code INVALID → 422}). 판단은 workflow가 한다:
 * <ul>
 *   <li>{@link #CONFLICT}: 요청은 맞지만 자원의 지금 상태가 허락하지 않는다(봉인됨·이미 서명·기한 경과·순서·룰 교체 등) — 상태가 바뀌어야 성립한다.</li>
 *   <li>{@link #INVALID}: 요청 자체가 업무 규칙에 맞지 않는다(모르는 코드·검증 차단·역할·입력 불일치) — 요청을 고쳐야 한다.</li>
 * </ul>
 * 한 응답에 둘이 섞이면 CONFLICT가 이긴다(상태가 먼저 바뀌어야 다른 거부도 의미가 있다).
 */
public enum RejectionCategory {
    CONFLICT,
    INVALID;

    /** 여러 거부의 범주: 하나라도 CONFLICT면 CONFLICT. */
    public static RejectionCategory of(java.util.Collection<? extends Categorized> rejections) {
        return rejections.stream().anyMatch(r -> r.category() == CONFLICT) ? CONFLICT : INVALID;
    }

    /** 범주가 붙은 거부 코드. */
    public interface Categorized {
        String name();

        RejectionCategory category();
    }
}
