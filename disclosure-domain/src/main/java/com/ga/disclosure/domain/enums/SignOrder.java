package com.ga.disclosure.domain.enums;

/**
 * 서명 순서 정책(부록 D {@code signOrder}). SEQUENTIAL은 {@code signerSet} 순서대로, PARALLEL은 순서 무관·전원 필요.
 * 서명자 집합과 순서는 룰 데이터이고, 이 열거형은 그 데이터를 해석하는 방식(닫힌 어휘)이다.
 */
public enum SignOrder {
    SEQUENTIAL,
    PARALLEL
}
