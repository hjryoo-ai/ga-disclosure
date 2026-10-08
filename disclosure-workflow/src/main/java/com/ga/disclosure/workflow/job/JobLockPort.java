package com.ga.disclosure.workflow.job;

import com.ga.platform.core.tenant.TenantId;

import java.util.Optional;

/**
 * 테넌트·잠금 키별 작업 잠금(6A 계획 §6.1). 운영 어댑터는 전용 롤({@code disclosure_job_lock}) 커넥션의 세션 advisory lock이고, 커넥션이 끊기면 DB가
 * 잠금을 푼다. 실패는 기다리지 않는다({@code try}).
 */
public interface JobLockPort {

    /** 잡았으면 쥔 잠금, 다른 누가 쥐고 있으면 빈 값. */
    Optional<Held> tryAcquire(TenantId tenant, JobKind lockKind);

    /** 쥔 잠금. 닫으면 풀고 커넥션을 돌려준다. */
    interface Held extends AutoCloseable {

        /** 잠금 커넥션이 살아 있고 이 잠금을 여전히 쥐고 있는가(승인 B1). 확인 자체가 실패하면 {@code false}. */
        boolean stillHeld();

        @Override
        void close();
    }
}
