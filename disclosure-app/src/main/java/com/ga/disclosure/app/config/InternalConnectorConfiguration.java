package com.ga.disclosure.app.config;

import com.ga.disclosure.api.security.InternalPort;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code /internal/**} 전용 커넥터(Phase 8, 8 계획 승인 Q5): 앱 포트(8080 — {@code /api}·{@code /public})와 별도인 내부 포트 {@code ga.internal.port}
 * (기본 8081, 시험은 0 = 임의). 인그레스는 이 포트를 노출하지 않고(클러스터 안 진입점만), NetworkPolicy는 포트 단위로 막는다. 경로 대조는
 * {@code PortChannelFilter}·{@code PublicSignGate}. 서버가 뜨면 실제 포트를 {@link InternalPort}와 환경 {@code local.internal.port}에 넣는다.
 */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class InternalConnectorConfiguration {

    /** 이 조립이 만든 커넥터(실제 포트를 찾을 때 동일성으로 대조 — 관리 포트 문맥의 커넥터와 섞이지 않는다). */
    private final java.util.concurrent.atomic.AtomicReference<Connector> created = new java.util.concurrent.atomic.AtomicReference<>();

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> internalConnector(@Value("${ga.internal.port:8081}") int port,
                                                                                    @Value("${server.address:}") String address) {
        return factory -> {
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setPort(port);
            if (!address.isBlank()) {
                connector.setProperty("address", address);
            }
            factory.addAdditionalConnectors(connector);
            created.set(connector);
        };
    }

    @Bean
    public ApplicationListener<ServletWebServerInitializedEvent> internalPortPublisher(InternalPort internalPort, ConfigurableEnvironment environment) {
        return event -> {
            if (event.getApplicationContext().getServerNamespace() != null || !(event.getWebServer() instanceof TomcatWebServer tomcat)) {
                return;    // 관리 포트(자식 문맥) 등
            }
            Connector internal = Arrays.stream(tomcat.getTomcat().getService().findConnectors())
                    .filter(c -> c == created.get())
                    .findFirst().orElseThrow(() -> new IllegalStateException("internal connector missing"));
            int port = internal.getLocalPort();
            internalPort.set(port);
            Map<String, Object> ports = new HashMap<>();
            ports.put("local.internal.port", port);
            environment.getPropertySources().addFirst(new MapPropertySource("ga.internal.port", ports));
        };
    }
}
