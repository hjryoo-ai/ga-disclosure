package com.ga.disclosure.workflow.job;

import com.ga.disclosure.workflow.authz.Caller;
import com.ga.platform.core.tenant.TenantId;

import java.util.List;
import java.util.UUID;

/**
 * 작업 본체: 잠금을 잡은 테넌트들의 호출자로 유스케이스를 한 번 부르고({@link #run}), 테넌트마다 보고서 바이트(JCS)를 낸다. 단일 테넌트 작업은 호출자가
 * 하나다. 앵커만 여러 테넌트를 한 번에 묶는다(한 트리, 6A 계획 §6.2). 보고서에는 그 테넌트의 것만 싣는다.
 */
public interface JobWork<R> {

    R run(List<Caller> acquired);

    /**
     * 작업 ID가 필요한 본체(6B 징구율 스냅샷 — 행에 작업 ID를 남긴다)는 이것을 덮는다. {@code jobIds}는 {@code acquired}와 같은 순서다. 기본은 ID를 쓰지 않는다.
     */
    default R run(List<Caller> acquired, List<UUID> jobIds) {
        return run(acquired);
    }

    /** HTTP 제출 전 업무 검사(작업 행·잠금 전 — 거부는 그 응답 코드로). 기본은 없음. CLI 실행은 본체가 같은 검사를 잠금 아래에서 한다. */
    default void admit(Caller caller) {
    }

    byte[] report(R result, TenantId tenant);
}
