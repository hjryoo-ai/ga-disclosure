package com.ga.disclosure.domain.vo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueObjectTest {

    private static final String HASH = "a".repeat(64);

    @Test
    void sha256AcceptsLowercaseHexOnlyAndComparesByValue() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest("x".getBytes(StandardCharsets.UTF_8));
        Sha256 fromDigest = Sha256.ofDigest(digest);
        assertThat(Sha256.of(fromDigest.hex())).isEqualTo(fromDigest).hasSameHashCodeAs(fromDigest);
        assertThat(fromDigest.bytes()).isEqualTo(digest);
        assertThat(Sha256.of(HASH)).isNotEqualTo(Sha256.of("b".repeat(64)));
        assertThat(Sha256.of(HASH)).isNotEqualTo(HASH);
    }

    static Stream<String> malformedHashes() {
        return Stream.of("", "abc", "A".repeat(64), "g".repeat(64), "a".repeat(63), "a".repeat(65), " " + "a".repeat(63));
    }

    @ParameterizedTest
    @MethodSource("malformedHashes")
    void sha256RejectsMalformed(String raw) {
        assertThatThrownBy(() -> Sha256.of(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sha256RejectsWrongDigestLength() {
        assertThatThrownBy(() -> Sha256.ofDigest(new byte[31])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chainHashWrapsSha256() {
        assertThat(ChainHash.of(HASH).value()).isEqualTo(Sha256.of(HASH));
    }

    @Test
    void identifiersValidate() {
        UUID uuid = UUID.randomUUID();
        assertThat(DisclosureId.parse(uuid.toString()).value()).isEqualTo(uuid);
        assertThat(GroupCode.of("PG-HEALTH-SIMPLE-NR").value()).isEqualTo("PG-HEALTH-SIMPLE-NR");
        assertThat(RuleVersionId.of("DISC-2026-07").value()).isEqualTo("DISC-2026-07");
        assertThat(SnapshotId.of("GRD-20260923-000481").value()).isEqualTo("GRD-20260923-000481");
        assertThat(CustomerRef.of("C-000123").value()).isEqualTo("C-000123");
        assertThat(TemplateRef.of("STANDARD", 1).version()).isEqualTo(1);
        assertThat(ReasonCode.of("CUSTOMER_REQUEST").value()).isEqualTo("CUSTOMER_REQUEST");

        assertThatThrownBy(() -> GroupCode.of("pg-health")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerRef.of("홍길동")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TemplateRef.of("STANDARD", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReasonCode.of("coverage")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DisclosureId(null)).isInstanceOf(NullPointerException.class);
    }
}
