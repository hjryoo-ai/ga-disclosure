package com.ga.platform.core.tenant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextTest {

    private static final TenantId A = TenantId.of("TA");
    private static final TenantId B = TenantId.of("TB");

    @Test
    void currentWithoutBindingThrowsInsteadOfReturningNull() {
        assertThat(TenantContext.isBound()).isFalse();
        assertThatThrownBy(TenantContext::current).isInstanceOf(TenantNotBoundException.class);
    }

    @Test
    void bindingIsScopedAndNestable() throws Exception {
        AtomicReference<TenantId> inner = new AtomicReference<>();
        TenantContext.runWith(A, () -> {
            assertThat(TenantContext.current()).isEqualTo(A);
            TenantContext.runWith(B, () -> inner.set(TenantContext.current()));
            assertThat(TenantContext.current()).isEqualTo(A);
        });
        assertThat(inner.get()).isEqualTo(B);
        assertThat(TenantContext.isBound()).isFalse();

        String result = TenantContext.runWith(A, () -> TenantContext.current().value());
        assertThat(result).isEqualTo("TA");
    }

    @Test
    void bindingIsNotInheritedByExecutorTasks() throws Exception {
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<Boolean> bound = TenantContext.runWith(A, () -> executor.submit(TenantContext::isBound));
            assertThat(bound.get()).as("비동기 작업은 명시적으로 재바인딩해야 한다").isFalse();
        }
    }

    @Test
    void rejectsNullTenant() {
        assertThatThrownBy(() -> TenantContext.runWith(null, () -> { })).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "t1", "T-1", "T 1", "_T1", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456"})
    void tenantIdRejectsMalformed(String raw) {
        assertThatThrownBy(() -> TenantId.of(raw)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"T1", "DEMO", "GA_01", "9"})
    void tenantIdAcceptsWellFormed(String raw) {
        assertThat(TenantId.of(raw).value()).isEqualTo(raw);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " a", "a/b", "a:b"})
    void agentIdRejectsMalformed(String raw) {
        assertThatThrownBy(() -> AgentId.of(raw)).isInstanceOf(IllegalArgumentException.class);
    }
}
