package com.ga.disclosure.domain.vo;

import java.util.Objects;
import java.util.UUID;

/** 확인서 내부 식별자(버전마다 다르다). */
public record DisclosureId(UUID value) {

    public DisclosureId {
        Objects.requireNonNull(value, "value");
    }

    public static DisclosureId of(UUID value) {
        return new DisclosureId(value);
    }

    public static DisclosureId parse(String raw) {
        return new DisclosureId(UUID.fromString(raw));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
