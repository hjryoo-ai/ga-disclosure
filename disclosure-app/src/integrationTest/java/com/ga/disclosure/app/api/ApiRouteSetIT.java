package com.ga.disclosure.app.api;

import com.ga.disclosure.app.DisclosureApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.MethodParameter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6B §9.1, 승인 §4 조건 4: 이름·전화·생년월일로 고객을 찾는 경로가 라우트 집합에 없다. 실제 MVC 매핑 전체와 세 계약 문서를 같이 본다(경로 집합 자체의 양방향
 * 대조는 {@link OpenApiContractIT}).
 * <ul>
 *   <li>"customer"가 들어간 라우트는 {@code POST /api/v1/customers} 하나뿐 — 고객을 읽는(GET) 경로가 없다.</li>
 *   <li>어느 GET 핸들러도 개인정보 이름의 질의 매개변수를 받지 않고, 계약의 어느 GET도 그런 매개변수를 선언하지 않는다.</li>
 * </ul>
 */
@SpringBootTest(classes = DisclosureApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiRouteSetIT {

    /** 개인정보 질의로 쓰일 수 있는 매개변수 이름(소문자 비교). */
    static final Set<String> PII_PARAMS = Set.of("name", "customername", "phone", "phonenumber", "mobile", "tel", "birthdate", "birth", "dob", "rrn",
            "ssn", "residentno", "address", "account");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        ApiTestSupport.properties(registry);
    }

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void theOnlyCustomerRouteIsRegistrationAndNoGetSearchesByPersonalData() {
        Set<String> customerRoutes = new TreeSet<>();
        List<String> piiQueries = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            for (String pattern : e.getKey().getPatternValues()) {
                if (!(pattern.startsWith("/api/") || pattern.startsWith("/internal/") || pattern.startsWith("/public/"))) {
                    continue;
                }
                e.getKey().getMethodsCondition().getMethods().forEach(m -> {
                    if (pattern.toLowerCase(Locale.ROOT).contains("customer")) {
                        customerRoutes.add(m.name() + " " + pattern);
                    }
                    if (m.name().equals("GET")) {
                        for (MethodParameter p : e.getValue().getMethodParameters()) {
                            RequestParam rp = p.getParameterAnnotation(RequestParam.class);
                            if (rp != null && PII_PARAMS.contains(rp.name().toLowerCase(Locale.ROOT))) {
                                piiQueries.add(pattern + "?" + rp.name());
                            }
                        }
                    }
                });
            }
        }
        assertThat(customerRoutes).containsExactly("POST /api/v1/customers");
        assertThat(piiQueries).isEmpty();

        List<String> contractPii = new ArrayList<>();
        List<String> contractCustomerGets = new ArrayList<>();
        for (JsonNode doc : ApiContracts.get().docs.values()) {
            for (Map.Entry<String, JsonNode> path : doc.path("paths").properties()) {
                JsonNode get = path.getValue().path("get");
                if (get.isMissingNode()) {
                    continue;
                }
                if (path.getKey().toLowerCase(Locale.ROOT).contains("customer")) {
                    contractCustomerGets.add(path.getKey());
                }
                for (JsonNode parameter : get.path("parameters")) {
                    if ("query".equals(parameter.path("in").asString()) && PII_PARAMS.contains(parameter.path("name").asString().toLowerCase(Locale.ROOT))) {
                        contractPii.add(path.getKey() + "?" + parameter.path("name").asString());
                    }
                }
            }
        }
        assertThat(contractCustomerGets).isEmpty();
        assertThat(contractPii).isEmpty();
        // 대조가 실제로 계약을 읽었다
        assertThat(ApiContracts.get().docs).hasSize(3);
        assertThat(ApiContracts.get().docs.values()).anyMatch(d -> d.path("paths").has("/api/v1/customers"));
    }
}
