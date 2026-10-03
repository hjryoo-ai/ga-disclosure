package com.ga.disclosure.app.demo;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * 데모가 아닌 프로파일의 기동 검사(5 계획 §8.9 (a), 승인 Q6·B2): {@code ga.demo.*} 키(환경변수 {@code GA_DEMO_*} 포함)가 하나라도 설정돼 있으면
 * 기동을 멈춘다 — 운영에서 시계를 옮기는 설정이 조용히 무시되거나 쓰이는 일이 없게. 값은 읽지도 출력하지도 않고 키 이름만 본다.
 */
@Component
@Profile("!demo")
public class DemoKeysGuard {

    public DemoKeysGuard(ConfigurableEnvironment environment) {
        Set<String> found = new TreeSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> e) {
                for (String name : e.getPropertyNames()) {
                    String normalized = name.toLowerCase(Locale.ROOT).replace('_', '.').replace('-', '.');
                    if (normalized.equals("ga.demo") || normalized.startsWith("ga.demo.")) {
                        found.add(name);
                    }
                }
            }
        }
        if (!found.isEmpty()) {
            throw new IllegalStateException("demo-only keys are set outside the demo profile (activate 'demo' or remove them): " + found);
        }
    }
}
