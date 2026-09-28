package com.ga.platform.spring.security;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

/** OIDC 설정은 골격이며 프로파일 "oidc"에서만 활성이어야 한다(Phase 0 비활성). */
class OidcResourceServerConfigurationTest {

    @Test
    void isGatedByOidcProfile() {
        Profile profile = OidcResourceServerConfiguration.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("oidc");
    }
}
