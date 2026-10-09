package com.ga.disclosure.infra.engine;

import com.ga.disclosure.infra.engine.stub.TableEngineStub;
import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 엔진 계약 1.2.1(E3.2): 데모·테스트 스텁의 응답이 계약 응답 스키마를 통과한다 — 특히 {@code generatedAt}이 0초·소수 초에서도 RFC 3339
 * (초 필수)다. 1.2.0의 {@code format: date-time}은 검증기가 단언하지 않아 스텁의 {@code OffsetDateTime.toString()}(0초 생략)이 드러나지 않았다.
 */
class TableEngineStubContractIT {

    static String resource(String path) {
        try (InputStream in = TableEngineStubContractIT.class.getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"2026-09-23T01:15:00Z", "2026-09-23T01:15:30Z", "2026-09-23T01:15:30.120Z", "2026-09-23T15:00:00.000000001Z"})
    void stubResponsesPassTheContractAtAnyInstant(String at) {
        TableEngineStub stub = TableEngineStub.load(resource("/workflow/engine-table.json"));
        ObjectNode request = (ObjectNode) Canonicalizer.parseStrict("""
                {"tenantId":"T1","asOfDate":"2026-09-23","productGroupCode":"PG-HEALTH-SIMPLE-NR",
                 "products":[{"productKey":"INS-A:PRD-1001","insurerCode":"INS-A"},{"productKey":"INS-Z:PRD-0000","insurerCode":"INS-Z"}]}""");
        assertThat(EngineContract.get().requestErrors(request)).isEmpty();
        ObjectNode response = stub.respond(request, TableEngineStub.snapshotId(Instant.parse(at), 1_000_000L), Instant.parse(at));
        assertThat(EngineContract.get().responseErrors(response)).isEmpty();
        assertThat(response.get("generatedAt").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\+09:00");
    }
}
