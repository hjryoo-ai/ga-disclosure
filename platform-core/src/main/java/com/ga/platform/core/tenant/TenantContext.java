package com.ga.platform.core.tenant;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * 현재 실행 범위의 테넌트. JDK 25 {@link ScopedValue} 기반이다.
 *
 * <p>규약
 * <ul>
 *   <li>{@link #current()}는 바인딩이 없으면 {@link TenantNotBoundException}을 던진다. {@code null}을 돌려주는 경로는 없다.</li>
 *   <li>바인딩은 {@link #runWith(TenantId, Runnable)} / {@link #runWith(TenantId, Callable)}의 동적 범위 안에서만 유효하다.
 *       범위를 벗어나면 자동으로 해제된다(ThreadLocal처럼 "지우는 것을 잊는" 경로가 없다).</li>
 *   <li><b>비동기·스케줄러 실행 시 명시적 재바인딩이 필요하다.</b> {@code ScopedValue}는 {@code StructuredTaskScope}로
 *       포크한 하위 작업에만 상속된다. {@code ExecutorService}·{@code CompletableFuture}·{@code @Async}·{@code @Scheduled}로
 *       넘긴 작업은 바인딩을 물려받지 않으므로, 작업 본문에서 {@code TenantContext.runWith(tenantId, ...)}로 다시 감싸야 한다.
 *       배치가 여러 테넌트를 돌 때도 테넌트마다 {@code runWith}를 새로 연다.</li>
 * </ul>
 */
public final class TenantContext {

    private static final ScopedValue<TenantId> CURRENT = ScopedValue.newInstance();

    private TenantContext() {
    }

    /** 현재 테넌트. 바인딩이 없으면 {@link TenantNotBoundException}. */
    public static TenantId current() {
        if (!CURRENT.isBound()) {
            throw new TenantNotBoundException();
        }
        return CURRENT.get();
    }

    public static boolean isBound() {
        return CURRENT.isBound();
    }

    public static void runWith(TenantId tenantId, Runnable action) {
        ScopedValue.where(CURRENT, Objects.requireNonNull(tenantId, "tenantId")).run(action);
    }

    public static <T> T runWith(TenantId tenantId, Callable<T> action) throws Exception {
        return ScopedValue.where(CURRENT, Objects.requireNonNull(tenantId, "tenantId")).call(action::call);
    }
}
