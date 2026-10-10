package com.ga.disclosure.app.metrics;

import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.JobRecord;
import com.ga.disclosure.workflow.metrics.OperationalMetrics;
import com.ga.platform.core.tenant.TenantId;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link OperationalMetrics}의 Micrometer 어댑터. 미터: {@code ga.jobs.runs{kind,outcome,tenant}}·{@code ga.jobs.duration{kind,tenant}}·
 * {@code ga.flags.sla.breached{tenant}}·{@code ga.anchor.unstamped.days}(게이지, 라벨 없음) — 공개 경로 거부는 {@code ga.public.rejections{reason}}
 * ({@link MetricsConfiguration}).
 */
final class MicrometerOperationalMetrics implements OperationalMetrics {

    private final MeterRegistry registry;
    private final AtomicLong unstampedDays = new AtomicLong();

    MicrometerOperationalMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        registry.gauge("ga.anchor.unstamped.days", unstampedDays);
    }

    @Override
    public void jobFinished(JobKind kind, JobRecord.Status outcome, TenantId tenant, Duration took) {
        registry.counter("ga.jobs.runs", "kind", kind.name(), "outcome", outcome.name(), "tenant", tenant.value()).increment();
        registry.timer("ga.jobs.duration", "kind", kind.name(), "tenant", tenant.value()).record(took);
    }

    @Override
    public void slaBreached(TenantId tenant, int count) {
        registry.counter("ga.flags.sla.breached", "tenant", tenant.value()).increment(count);
    }

    @Override
    public void unstampedAnchorDays(long days) {
        unstampedDays.set(days);
    }
}
