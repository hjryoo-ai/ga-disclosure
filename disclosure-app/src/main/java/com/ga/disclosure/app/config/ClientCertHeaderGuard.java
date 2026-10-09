package com.ga.disclosure.app.config;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 운영 기동 가드(6B 계획 §6 Q11): {@code prod} 프로파일은 {@code ga.api.client-cert.subject-header} 없이 기동하지 않는다. 게이트·계약 연결 경로의 mTLS 주체
 * 대조는 인그레스가 덮어쓴 헤더에 기대므로, 설정이 빠진 운영은 대조 없이 열리는 대신 멈춘다. 값은 출력하지 않는다.
 */
@Component
@Profile("prod")
public class ClientCertHeaderGuard {

    public static final String PROPERTY = "ga.api.client-cert.subject-header";

    public ClientCertHeaderGuard(Environment environment) {
        String header = environment.getProperty(PROPERTY);
        if (header == null || header.isBlank()) {
            throw new IllegalStateException(PROPERTY + " must be set in the prod profile (the ingress writes the client certificate subject into it)");
        }
    }
}
