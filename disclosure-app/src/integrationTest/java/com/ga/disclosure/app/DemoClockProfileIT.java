package com.ga.disclosure.app;

import com.ga.disclosure.infra.testing.PostgresHarness;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 5 계획 §8.9 (a)·승인 Q6·B2: 시계 오프셋 키 {@code ga.demo.clock-offset}은 데모 프로파일에서만 존재한다. 운영(데모가 아닌) 프로파일에 그 키가 있으면
 * 기동이 실패하고, 데모 프로파일에서는 애플리케이션의 시계가 그만큼 옮겨진다. 키가 없으면 데모가 아닌 프로파일은 그대로 뜬다(대조).
 */
class DemoClockProfileIT {

    private static final PostgresHarness DB = PostgresHarness.get();

    private static ConfigurableApplicationContext start(String profiles, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.profiles.active=" + profiles,
                "--spring.main.web-application-type=none",
                "--spring.datasource.url=" + DB.jdbcUrl(),
                "--ga.health.url=" + DB.jdbcUrl(),
                "--ga.tenant-directory.url=" + DB.jdbcUrl(),
                "--ga.job-lock.url=" + DB.jdbcUrl()));
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(DisclosureApplication.class).run(args.toArray(String[]::new));
    }

    @Test
    void theOffsetKeyOutsideTheDemoProfileStopsTheBoot() {
        assertThatThrownBy(() -> start("default", "--ga.demo.clock-offset=-P5D").close())
                .hasStackTraceContaining("demo-only keys are set outside the demo profile").hasStackTraceContaining("ga.demo.clock-offset");
        try (ConfigurableApplicationContext plain = start("default")) {
            assertThat(Duration.between(plain.getBean(Clock.class).instant(), Instant.now()).abs()).isLessThan(Duration.ofMinutes(1));
        }
    }

    @Test
    void theDemoProfileShiftsTheApplicationClock() {
        try (ConfigurableApplicationContext demo = start("demo", "--ga.demo.clock-offset=-P5D")) {
            Duration behind = Duration.between(demo.getBean(Clock.class).instant(), Instant.now());
            assertThat(behind).isBetween(Duration.ofDays(5).minusMinutes(1), Duration.ofDays(5).plusMinutes(1));
        }
    }
}
