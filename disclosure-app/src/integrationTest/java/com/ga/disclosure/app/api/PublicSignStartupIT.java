package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 승인 Q6: 공개 응답 하한 {@code ga.public-sign.min-response-millis}는 기본값이 없다 — 웹 앱은 없거나 1 미만이면 뜨지 않는다. */
class PublicSignStartupIT {

    static String[] args(String floorOrNull) {
        List<String> out = new ArrayList<>(List.of("--server.port=0"));
        for (Map.Entry<String, Supplier<Object>> e : ApiTestSupport.propertyMap().entrySet()) {
            if (!e.getKey().equals("ga.public-sign.min-response-millis")) {
                out.add("--" + e.getKey() + "=" + e.getValue().get());
            }
        }
        if (floorOrNull != null) {
            out.add("--ga.public-sign.min-response-millis=" + floorOrNull);
        }
        return out.toArray(String[]::new);
    }

    static void start(String floorOrNull) {
        new SpringApplicationBuilder(DisclosureApplication.class).web(WebApplicationType.SERVLET).run(args(floorOrNull)).close();
    }

    @Test
    void aMissingFloorFailsStartup() {
        assertThatThrownBy(() -> start(null)).rootCause().hasMessageContaining("ga.public-sign.min-response-millis");
    }

    @Test
    void aFloorBelowOneMillisecondFailsStartup() {
        assertThatThrownBy(() -> start("0")).rootCause().hasMessageContaining("ga.public-sign.min-response-millis must be at least 1");
    }
}
