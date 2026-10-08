package com.ga.disclosure.workflow.job;

import com.ga.disclosure.workflow.authz.Caller;
import com.ga.platform.core.tenant.TenantId;

import java.util.List;

/**
 * 작업 본체: 잠금을 잡은 테넌트들의 호출자로 유스케이스를 한 번 부르고({@link #run}), 테넌트마다 보고서 바이트(JCS)를 낸다. 단일 테넌트 작업은 호출자가
 * 하나다. 앵커만 여러 테넌트를 한 번에 묶는다(한 트리, 6A 계획 §6.2). 보고서에는 그 테넌트의 것만 싣는다.
 */
public interface JobWork<R> {

    R run(List<Caller> acquired);

    byte[] report(R result, TenantId tenant);
}
