package com.ga.disclosure.app.api;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * 시험 전용(Phase 8 Q5): 웹 앱이 뜨면 앱 포트 → 내부 포트를 {@link ApiTestSupport#INTERNAL_PORTS}에 적는다 — 시험의 {@code /internal} 호출이 내부 포트로
 * 간다. {@code META-INF/spring.factories}(integrationTest 자원)로 등록한다.
 */
public final class InternalPortRecorder implements ApplicationListener<ApplicationReadyEvent> {

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        var env = event.getApplicationContext().getEnvironment();
        String app = env.getProperty("local.server.port");
        String internal = env.getProperty("local.internal.port");
        if (app != null && internal != null) {
            ApiTestSupport.INTERNAL_PORTS.put(Integer.parseInt(app), Integer.parseInt(internal));
        }
    }
}
