package com.ga.disclosure.api.dto;

import java.util.List;

/** 확인서 하나의 플래그 전부(쪽 나눔 없음). */
public record FlagList(List<FlagView> items) {
}
