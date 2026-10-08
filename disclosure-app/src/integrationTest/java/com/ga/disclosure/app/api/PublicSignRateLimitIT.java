package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import com.ga.disclosure.infra.testing.SeedData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G7(6A 계획 §11): 공개 서명 경로의 테넌트 분당 한도는 룰 데이터({@code publicSign.tenantRatePerMinute})다 — 한도 2와 4인 두 테넌트에서 각각 N번째까지
 * 200, N+1번째는 다른 거부와 같은 바이트, 한도에 걸린 테넌트가 있어도 다른 테넌트는 그대로, 다음 1분 창에 회복. 창 경계는 시험 시계로 맞춘다(앞으로만
 * 움직인다 — 분의 시작 + 1초로 맞추고, 회복은 60초 앞으로).
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PublicSignRateLimitIT.Hooks.class)
class PublicSignRateLimitIT {

    static final String TWO = SeedData.uniqueTenant("RL2");
    static final String FOUR = SeedData.uniqueTenant("RL4");
    static final String OTHER = SeedData.uniqueTenant("RLO");
    static final ShiftedClock CLOCK = new ShiftedClock();
    static String twoCustomer;
    static String fourCustomer;
    static String otherCustomer;

    /** 시스템 시계 + 앞으로만 늘어나는 오프셋. */
    static final class ShiftedClock extends Clock {

        private volatile Duration offset = Duration.ZERO;
        private final Clock base = Clock.system(ZoneId.of("Asia/Seoul"));

        void forward(Duration by) {
            assertThat(by.isNegative()).isFalse();
            offset = offset.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return base.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.offset(base.withZone(zone), offset);
        }

        @Override
        public Instant instant() {
            return base.instant().plus(offset);
        }
    }

    @TestConfiguration
    static class Hooks {
        @Bean
        @Primary
        Clock shiftedClock() {
            return CLOCK;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @BeforeAll
    static void prepare() {
        FlowSupport.prepare(TWO, FlowSupport.variantBundle("RL-IT-RATE2", body -> ((ObjectNode) body.get("publicSign")).put("tenantRatePerMinute", 2)));
        twoCustomer = FlowSupport.customerRef(TWO, "C03");
        FlowSupport.prepare(FOUR, FlowSupport.variantBundle("RL-IT-RATE4", body -> ((ObjectNode) body.get("publicSign")).put("tenantRatePerMinute", 4)));
        fourCustomer = FlowSupport.customerRef(FOUR, "C03");
        otherCustomer = FlowSupport.prepare(OTHER);
    }

    @Value("${local.server.port}")
    int port;

    ApiTestSupport.Response status(String token) {
        return FlowSupport.publicPost(port, "/public/v1/sign/status", token, null);
    }

    /** 다음 분의 시작 + 1초로(시험 시계만). */
    static void alignToTheNextMinute() {
        long second = CLOCK.instant().getEpochSecond();
        CLOCK.forward(Duration.ofSeconds(60 - second % 60 + 1));
    }

    @Test
    void theLimitComesFromTheRuleAndRecoversInTheNextWindow() {
        String two = FlowSupport.deviceToken(port, TWO, FlowSupport.sealed(port, TWO, twoCustomer), "TOUCH_PAD");
        String four = FlowSupport.deviceToken(port, FOUR, FlowSupport.sealed(port, FOUR, fourCustomer), "TOUCH_PAD");
        String other = FlowSupport.deviceToken(port, OTHER, FlowSupport.sealed(port, OTHER, otherCustomer), "TOUCH_PAD");
        ApiTestSupport.Response reference = status("not-a-token");
        assertThat(reference.status()).isEqualTo(404);

        alignToTheNextMinute();
        List<Integer> twoStatuses = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            twoStatuses.add(status(two).status());
        }
        assertThat(twoStatuses).as("N = 2 from the rule").containsOnly(200);
        ApiTestSupport.Response limited = status(two);
        assertThat(limited.fingerprint()).as("N+1 is the one rejection").isEqualTo(reference.fingerprint());
        assertThat(status(other).status()).as("another tenant is not affected").isEqualTo(200);

        List<Integer> fourStatuses = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            fourStatuses.add(status(four).status());
        }
        assertThat(fourStatuses).as("N = 4 from the other tenant's rule").containsOnly(200);
        assertThat(status(four).fingerprint()).isEqualTo(reference.fingerprint());

        CLOCK.forward(Duration.ofSeconds(60));
        assertThat(status(two).status()).as("the next one-minute window admits again").isEqualTo(200);
        assertThat(status(four).status()).isEqualTo(200);
    }
}
