package com.ga.disclosure.infra.engine.stub;

import com.ga.disclosure.infra.engine.EngineTransport;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 데모 프로파일의 프로세스 안 엔진 전송: {@link TableEngineStub}로 응답 바이트를 만들고, 발급한 응답을 스냅샷 ID로 보관해 재조회에 같은 바이트를
 * 돌려준다(§4.1 바이트 동일 약속의 흉내). 응답은 운영 경로와 똑같이 클라이언트의 계약 스키마 검증·정합성 검증을 거친다.
 */
public final class StubEngineTransport implements EngineTransport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TableEngineStub table;
    private final Clock clock;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, byte[]> issued = new ConcurrentHashMap<>();

    public StubEngineTransport(TableEngineStub table, Clock clock) {
        this.table = Objects.requireNonNull(table, "table");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Response post(TenantId tenant, String path, byte[] body) {
        if (!GRADES_PATH.equals(path)) {
            return problem(404, "NOT_FOUND");
        }
        JsonNode request = Canonicalizer.parseStrict(new String(body, StandardCharsets.UTF_8));
        if (!table.servesGroup(request.path("productGroupCode").asString())) {
            return problem(400, "UNKNOWN_PRODUCT_GROUP");
        }
        Instant now = clock.instant();
        String id = TableEngineStub.snapshotId(now, sequence.getAndIncrement());
        byte[] response = JSON.writeValueAsBytes(table.respond(request, id, now));
        issued.put(id, response);
        return new Response(200, response);
    }

    @Override
    public Response get(TenantId tenant, String path) {
        String prefix = GRADES_PATH + "/";
        byte[] body = path.startsWith(prefix) ? issued.get(path.substring(prefix.length())) : null;
        return body == null ? problem(404, "SNAPSHOT_NOT_FOUND") : new Response(200, body);
    }

    private static Response problem(int status, String code) {
        ObjectNode p = JSON.createObjectNode().put("code", code).put("message", code);
        return new Response(status, JSON.writeValueAsBytes(p));
    }
}
