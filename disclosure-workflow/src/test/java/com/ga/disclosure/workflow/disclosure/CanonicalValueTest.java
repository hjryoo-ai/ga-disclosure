package com.ga.disclosure.workflow.disclosure;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 스칼라·컨테이너 값의 RFC 8785 정규형(항목값 저장 형식). */
class CanonicalValueTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void scalarsAndContainersCanonicalize() {
        assertThat(CanonicalValue.of(JSON.readTree("\"값\\u00e9\""))).isEqualTo("\"값é\"");
        assertThat(CanonicalValue.of(JSON.readTree("32100"))).isEqualTo("32100");
        assertThat(CanonicalValue.of(JSON.readTree("true"))).isEqualTo("true");
        assertThat(CanonicalValue.of(JSON.readTree("{\"b\":1,\"a\":[2, {\"d\":1,\"c\":0}]}"))).isEqualTo("{\"a\":[2,{\"c\":0,\"d\":1}],\"b\":1}");
    }
}
