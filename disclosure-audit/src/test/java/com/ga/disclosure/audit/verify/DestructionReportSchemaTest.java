package com.ga.disclosure.audit.verify;

import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/** 파기 실행 보고서 스키마는 닫혀 있다 — 표본은 통과하고, 키 추가·사유 어휘 밖·단계 밖·형식 위반은 실패한다. 실제 실행의 보고서는 통합 테스트가 매번 대조한다. */
class DestructionReportSchemaTest {

    static final String SAMPLE = """
            {"reportVersion":1,"tenantId":"DEMO1","asOf":"2026-09-26T01:00:00Z","dryRun":false,"ruleVersionId":"DISC-2026-07","candidates":3,
             "destroyed":[{"disclosureId":"0b7d8c1e-3c1a-4c55-9a51-2f0f5d6a7b11","disclosureNo":"DEMO1-2026-000001","anchorsWaived":["CONTRACT_DATE"]}],
             "wouldDestroy":[],
             "skipped":[{"disclosureId":"5a0e4f8b-62a4-4b0c-8a77-0e9d8c7b6a51","reason":"HOLD","reasons":["HOLD","PENDING_ANCHOR"]}],
             "skippedByReason":{"HOLD":1},
             "failed":[{"disclosureId":"9c3f2e1d-7b6a-4c5d-8e9f-0a1b2c3d4e5f","stage":"DELETE_OBJECTS","code":"OBJECTS_REMAIN"}],
             "anchorsWaived":1,"holdAfterShred":0,
             "customers":{"candidates":2,"destroyed":["CR-00000000000000000000000000000001"],"skippedByReason":{"LIVE_DISCLOSURES":1,"REFUSED_GD114":1}}}
            """;

    static ObjectNode sample() {
        return (ObjectNode) Canonicalizer.parseStrict(SAMPLE);
    }

    @Test
    void theSampleIsValid() {
        assertThat(VerifySchemas.destructionReport(sample())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/extra|1",
            "/reportVersion|2",
            "/tenantId|\"demo1\"",
            "/asOf|\"2026-09-26\"",
            "/destroyed/0/disclosureId|\"not-a-uuid\"",
            "/skipped/0/reason|\"MAYBE\"",
            "/skipped/0/reasons|[]",
            "/skippedByReason/SOMETHING|1",
            "/skippedByReason/HOLD|0",
            "/failed/0/stage|\"UPLOAD\"",
            "/failed/0/code|\"has space\"",
            "/destroyed/0/disclosureNo|\"demo1-2026-1\"",
            "/destroyed/0/anchorsWaived/0|\"SIGNATURE\"",
            "/destroyed/0/name|\"홍길동\"",
            "/customers/extra|1",
            "/customers/skippedByReason/UNKNOWN|1",
            "/customers/destroyed/0|\"has space\""})
    void closedAndTyped(String spec) {
        String[] p = spec.split("\\|", 2);
        ObjectNode m = sample();
        JsonNode value = Canonicalizer.parseStrict(p[1]);
        int slash = p[0].lastIndexOf('/');
        JsonNode parent = slash == 0 ? m : m.at(p[0].substring(0, slash));
        String key = p[0].substring(slash + 1);
        if (parent.isArray()) {
            ((ArrayNode) parent).set(Integer.parseInt(key), value);
        } else {
            ((ObjectNode) parent).set(key, value);
        }
        assertThat(VerifySchemas.destructionReport(m)).as(spec).isNotEmpty();
    }
}
