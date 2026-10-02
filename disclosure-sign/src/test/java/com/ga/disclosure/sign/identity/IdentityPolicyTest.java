package com.ga.disclosure.sign.identity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.ga.disclosure.domain.enums.IdentityMethod.AGENT_FACE_TO_FACE;
import static com.ga.disclosure.domain.enums.IdentityMethod.BIRTH_DATE;
import static com.ga.disclosure.domain.enums.IdentityMethod.LINK_POSSESSION;
import static com.ga.disclosure.domain.enums.IdentityMethod.PROVIDER;
import static com.ga.disclosure.domain.enums.IdentityMethod.SCROLL_COMPLETE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityPolicyTest {

    @Test
    void everyRequiredMethodMustPassInRuleOrder() {
        List<com.ga.disclosure.domain.enums.IdentityMethod> touch = List.of(AGENT_FACE_TO_FACE, SCROLL_COMPLETE);
        assertThat(IdentityPolicy.missing(touch, Set.of())).containsExactly(AGENT_FACE_TO_FACE, SCROLL_COMPLETE);
        assertThat(IdentityPolicy.missing(touch, Set.of(SCROLL_COMPLETE))).containsExactly(AGENT_FACE_TO_FACE);
        assertThat(IdentityPolicy.complete(touch, Set.of(SCROLL_COMPLETE, AGENT_FACE_TO_FACE, BIRTH_DATE))).isTrue();
        // 룰 데이터를 바꾸면 요구가 바뀐다 — 코드 변경 없음
        assertThat(IdentityPolicy.complete(List.of(LINK_POSSESSION), Set.of(LINK_POSSESSION))).isTrue();
        assertThat(IdentityPolicy.complete(List.of(), Set.of())).isTrue();
    }

    @Test
    void providerIsReservedAndRepeatsAreConfigurationErrors() {
        assertThatThrownBy(() -> IdentityPolicy.missing(List.of(PROVIDER), Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityPolicy.missing(List.of(BIRTH_DATE, BIRTH_DATE), Set.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exhaustedAtTheMaximum() {
        assertThat(IdentityPolicy.exhausted(4, 5)).isFalse();
        assertThat(IdentityPolicy.exhausted(5, 5)).isTrue();
        assertThatThrownBy(() -> IdentityPolicy.exhausted(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
