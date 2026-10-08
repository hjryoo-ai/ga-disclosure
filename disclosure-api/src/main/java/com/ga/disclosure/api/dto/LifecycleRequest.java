package com.ga.disclosure.api.dto;

/** 무효·정정 사유: 고정 룰의 코드({@code voidReasons}·{@code supersedeReasons})와 선택 텍스트. */
public record LifecycleRequest(String reasonCode, String reasonText) {
}
