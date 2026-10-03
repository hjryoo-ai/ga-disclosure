package com.ga.disclosure.workflow;

import com.ga.platform.core.tenant.TenantId;

import java.util.function.Supplier;

/**
 * 테넌트에 바인딩된 트랜잭션 하나(포트, infra가 구현). 안에서 호출하는 저장소·감사 포트는 전부 같은 트랜잭션이다(설계서 §6.7).
 */
public interface WorkflowTransactions {

    <T> T inTenant(TenantId tenant, Supplier<T> work);

    /**
     * 제한 시간이 있는 트랜잭션(3B 봉인): 제한을 넘기면 그 뒤의 DB 문장이 실패해 롤백된다. 봉인이 올린 객체는 커밋 시점에 이 제한보다 젊다 — 잔여물
     * 정리의 유예가 이 제한보다 크면 진행 중인 봉인의 객체를 건드리지 않는다.
     */
    <T> T inTenant(TenantId tenant, java.time.Duration timeout, Supplier<T> work);

    /**
     * REPEATABLE READ 트랜잭션(5 계획 승인 Q12 — 일일 앵커가 두 체인 머리를 한 스냅샷에서 읽는다). 스냅샷 뒤 다른 트랜잭션의 커밋과 부딪히면
     * (직렬화 실패 40001, 감사 seq 유일성 23505) 롤백하고 {@link ConcurrentWriteConflict}를 던진다 — 호출자가 처음부터 다시 한다.
     */
    <T> T inTenantRepeatableRead(TenantId tenant, Supplier<T> work);
}
