package com.ga.disclosure.api.security;

import com.ga.platform.core.tenant.TenantId;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 공개 서명 경로의 테넌트 분당 한도(6A 계획 §5.4): 고정 1분 창(주입 시계의 분), 인스턴스 메모리, <b>알려진 테넌트만 키</b>(없는 테넌트 접두로 맵을 키울 수
 * 없다 — 게이트가 존재 확인 뒤에만 부른다). 성공 포함 모든 요청을 센다. 다중 인스턴스면 한도는 인스턴스별이다(인그레스 문서의 요구사항, Phase 8).
 */
public final class RateWindow {

    private record Window(long minute, int count) {
    }

    private final ConcurrentMap<TenantId, Window> windows = new ConcurrentHashMap<>();

    /** 이 테넌트의 창이 있는가(관찰용 — 없는 테넌트는 키가 되지 않는다). */
    public boolean tracks(TenantId tenant) {
        return windows.containsKey(tenant);
    }

    /** 이번 요청을 센다. 이 분의 수가 한도 이하면 {@code true}. */
    public boolean admit(TenantId tenant, int perMinute, Instant now) {
        long minute = now.getEpochSecond() / 60;
        Window w = windows.compute(tenant, (t, old) -> old == null || old.minute() != minute ? new Window(minute, 1) : new Window(minute, old.count() + 1));
        return w.count() <= perMinute;
    }
}
