package com.ga.platform.core.tenant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrgPathTest {

    @Test
    void containmentIsBySegmentNotByString() {
        OrgPath hq = OrgPath.of("/HQ");
        assertThat(hq.contains(OrgPath.of("/HQ"))).isTrue();
        assertThat(hq.contains(OrgPath.of("/HQ/B1"))).isTrue();
        assertThat(hq.contains(OrgPath.of("/HQ/B1/T_2"))).isTrue();
        assertThat(hq.contains(OrgPath.of("/HQX"))).isFalse();
        assertThat(hq.contains(OrgPath.of("/HQX/B1"))).isFalse();
        assertThat(OrgPath.of("/HQ/B1").contains(hq)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "HQ", "/HQ/", "//HQ", "/HQ%", "/H Q", "/HQ/../B1"})
    void malformedPathsAreRejected(String value) {
        assertThatThrownBy(() -> OrgPath.of(value)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid org path");
    }
}
