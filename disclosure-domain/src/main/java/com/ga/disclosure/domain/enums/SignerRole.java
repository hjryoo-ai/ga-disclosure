package com.ga.disclosure.domain.enums;

/** 서명자 역할. 어떤 역할이 필요한지(서명자 집합·순서)는 룰 데이터({@code signerSet}, {@code signOrder})다. */
public enum SignerRole {
    CUSTOMER,
    AGENT,
    MANAGER
}
