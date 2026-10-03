package com.ga.disclosure.app.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

/**
 * 데모 프로파일 전용 시계(5 계획 §8.9 (a), 승인 Q6): {@code ga.demo.clock-offset}(ISO 기간, 예 {@code -P5D})만큼 옮긴 시스템 시계를 우선 시계로 둔다.
 * 짧은 보존(0년 1일) 데모 테넌트를 과거 시각으로 봉인해 같은 날 파기까지 보이려는 것뿐이다. 이 키를 읽는 코드는 이 클래스뿐이고, 데모가 아닌
 * 프로파일에서 {@code ga.demo.*} 키가 있으면 {@link DemoKeysGuard}가 기동을 멈춘다.
 */
@Configuration
@Profile("demo")
@EnableConfigurationProperties(DemoClockConfiguration.DemoProperties.class)
public class DemoClockConfiguration {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @ConfigurationProperties("ga.demo")
    public record DemoProperties(Duration clockOffset) {
        public DemoProperties {
            clockOffset = clockOffset == null ? Duration.ZERO : clockOffset;
        }
    }

    @Bean
    @Primary
    public Clock demoClock(DemoProperties properties) {
        return Clock.offset(Clock.system(SEOUL), properties.clockOffset());
    }
}
