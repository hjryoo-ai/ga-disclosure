package com.ga.disclosure.workflow.metrics;

import com.ga.disclosure.workflow.job.JobKind;
import com.ga.disclosure.workflow.job.JobRecord;
import com.ga.platform.core.tenant.TenantId;

import java.time.Duration;

/**
 * 운영 미터 포트(Phase 8, 지시문 §2 관측 · G8). 인자는 닫힌 값만 — 작업 종류·결과(enum), 테넌트 ID, 정수. 확인서 번호·주체·고객 등 다른 식별자를 받을
 * 자리가 없다(라벨 키 닫힌 목록 {@code kind·outcome·reason·tenant}는 어댑터의 미터 필터가 한 번 더 강제한다). 기록 실패는 업무를 막지 않는다 — 호출하는
 * 쪽이 삼킨다({@link #safely}).
 */
public interface OperationalMetrics {

    /** 작업 한 건이 끝났다(SUCCEEDED·FAILED). */
    void jobFinished(JobKind kind, JobRecord.Status outcome, TenantId tenant, Duration took);

    /** 준법 큐 SLA 초과로 새로 표시한 플래그 수(FLAG_SLA_SWEEP). */
    void slaBreached(TenantId tenant, int count);

    /** 앵커 실행 뒤에도 TSA 고정(영수증)이 없는 가장 오래된 앵커의 경과 일수(없으면 0) — 플랫폼 단위, 테넌트 라벨 없음. */
    void unstampedAnchorDays(long days);

    OperationalMetrics NONE = new OperationalMetrics() {
        @Override
        public void jobFinished(JobKind kind, JobRecord.Status outcome, TenantId tenant, Duration took) {
        }

        @Override
        public void slaBreached(TenantId tenant, int count) {
        }

        @Override
        public void unstampedAnchorDays(long days) {
        }
    };

    /** 미터 기록은 업무 결과를 바꾸지 않는다 — 실패는 로그 코드 한 줄(예외 종류만)로 남기고 삼킨다. */
    static void safely(Runnable record) {
        try {
            record.run();
        } catch (RuntimeException e) {
            System.getLogger(OperationalMetrics.class.getName()).log(System.Logger.Level.WARNING, "METRIC_RECORD_FAILED " + e.getClass().getSimpleName());
        }
    }
}
