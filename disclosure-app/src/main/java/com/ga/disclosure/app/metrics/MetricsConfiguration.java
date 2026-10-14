package com.ga.disclosure.app.metrics;

import com.ga.disclosure.api.security.PublicRejection;
import com.ga.disclosure.workflow.metrics.OperationalMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/**
 * 운영 미터(Phase 8, 지시문 §2 관측 · G8). 이름이 {@code ga.}로 시작하는 미터의 라벨 키는 닫힌 목록 {@link #LABEL_KEYS}뿐이다 — 목록 밖 키(확인서 번호·주체·
 * 고객 등)를 단 미터는 <b>등록이 거부된다</b>(필터가 예외). 라벨 값은 enum 이름·테넌트 ID뿐이다(포트가 다른 값을 받을 자리가 없다). 관리 포트의
 * {@code /actuator/prometheus}로만 나간다.
 */
@Configuration
public class MetricsConfiguration {

    public static final Set<String> LABEL_KEYS = Set.of("kind", "outcome", "reason", "tenant");
    static final String PREFIX = "ga.";

    @Bean
    public MeterFilter closedLabelKeys() {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                if (id.getName().startsWith(PREFIX)) {
                    List<String> outside = id.getTags().stream().map(Tag::getKey).filter(k -> !LABEL_KEYS.contains(k)).sorted().toList();
                    if (!outside.isEmpty()) {
                        throw new IllegalArgumentException("meter " + id.getName() + " has label keys outside the closed list " + LABEL_KEYS + ": " + outside);
                    }
                }
                return id;
            }
        };
    }

    @Bean
    public OperationalMetrics operationalMetrics(MeterRegistry registry) {
        return new MicrometerOperationalMetrics(registry);
    }

    @Bean
    public PublicRejection.Counter publicRejectionCounter(MeterRegistry registry) {
        return reason -> registry.counter("ga.public.rejections", "reason", reason.name()).increment();
    }
}
