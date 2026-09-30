package com.ga.disclosure.infra.testing;

import com.ga.disclosure.infra.engine.EngineContract;
import com.ga.disclosure.infra.engine.EngineTransport;
import com.ga.disclosure.infra.engine.stub.TableEngineStub;
import com.ga.platform.canonical.Canonicalizer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 엔진 테스트 대역(3A 계획 §5): JDK {@link HttpServer} 위에서 실제 HTTP 어댑터까지 통과시킨다.
 * <ul>
 *   <li><b>받은 요청을 계약 요청 스키마로 검증</b>한다 — 임시등록 상품·41자 키 같은 계약 밖 요청이 오면 {@link #requestViolations()}에 남고
 *       400을 돌려준다(테스트가 비어 있음을 단언한다).</li>
 *   <li>정상 응답은 {@link TableEngineStub}(데모 스텁과 같은 표)로 만들고 <b>계약 응답 스키마로 자기 검증한 뒤</b> 보낸다 — 페이크가 의도치 않게
 *       틀린 응답을 내면 {@link #selfCheckFailures()}에 남는다.</li>
 *   <li>결함은 {@link Fault} 모드로만 주입한다(자기 검증 뒤에 응답을 고친다).</li>
 * </ul>
 */
public final class FakeEngine implements AutoCloseable {

    /** 주입할 결함. */
    public enum Fault {
        NONE,
        /** OK 결과 하나를 UNAVAILABLE로 바꾸되 rankInSet을 남긴다(계약 oneOf 위반). */
        UNAVAILABLE_WITH_RANK,
        /** 1순위와 꼴찌의 gradeOrdinal을 바꾼다(순위-등급 단조 위반 §6.3 (iii)). */
        NON_MONOTONIC,
        /** 마지막 결과를 뺀다(응답 집합 ≠ 요청 집합 §6.3 (i)). */
        MISSING_PRODUCT,
        /** 요청하지 않은 상품을 더한다(§6.3 (i)). */
        EXTRA_PRODUCT,
        /** 룰이 허용하지 않는 등급 정책 버전(§6.3 (iv)). */
        DISALLOWED_POLICY,
        /** 동점 처리를 STRICT로 바꾼다(룰이 STRICT를 허용하지 않으면 §6.3 (iv)). */
        TIE_BREAK_STRICT,
        /** 422 NO_POLICY(엔진 명시 오류 — 스냅샷 없음). */
        STATUS_422,
        /** 응답 전에 {@link #delay(Duration)}만큼 기다린다(요청 타임아웃 → 재시도 금지 확인). */
        DELAY
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final TableEngineStub table;
    private final String expectedToken;
    private final Clock clock;
    private final HttpServer server;
    private final EngineContract contract = EngineContract.get();
    private final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
    private final AtomicReference<Duration> delay = new AtomicReference<>(Duration.ZERO);
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicLong sequence = new AtomicLong();
    private final List<String> requestViolations = new CopyOnWriteArrayList<>();
    private final List<String> selfCheckFailures = new CopyOnWriteArrayList<>();
    private final Map<String, byte[]> issued = new ConcurrentHashMap<>();

    public FakeEngine(TableEngineStub table, String expectedToken, Clock clock) {
        this.table = table;
        this.expectedToken = expectedToken;
        this.clock = clock;
        try {
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext(EngineTransport.GRADES_PATH, this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    public URI baseUrl() {
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/");
    }

    public FakeEngine fault(Fault f) {
        fault.set(f);
        return this;
    }

    public FakeEngine delay(Duration d) {
        delay.set(d);
        return this;
    }

    public int requests() {
        return requests.get();
    }

    public List<String> requestViolations() {
        return List.copyOf(requestViolations);
    }

    public List<String> selfCheckFailures() {
        return List.copyOf(selfCheckFailures);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        requests.incrementAndGet();
        try (ex) {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals("Bearer " + expectedToken)) {
                send(ex, 401, problem("UNAUTHENTICATED"));
                return;
            }
            if (ex.getRequestMethod().equals("GET")) {
                String id = ex.getRequestURI().getPath().substring(EngineTransport.GRADES_PATH.length() + 1);
                byte[] body = issued.get(id);
                send(ex, body == null ? 404 : 200, body == null ? problem("SNAPSHOT_NOT_FOUND") : body);
                return;
            }
            JsonNode request;
            try {
                request = Canonicalizer.parseStrict(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                requestViolations.add("not strict JSON");
                send(ex, 400, problem("BAD_REQUEST"));
                return;
            }
            List<String> violations = contract.requestErrors(request);
            if (!violations.isEmpty()) {
                requestViolations.addAll(violations);
                send(ex, 400, problem("BAD_REQUEST"));
                return;
            }
            if (fault.get() == Fault.STATUS_422) {
                send(ex, 422, problem("NO_POLICY"));
                return;
            }
            String id = TableEngineStub.snapshotId(clock.instant(), sequence.getAndIncrement());
            ObjectNode response = table.respond(request, id, clock.instant());
            List<String> self = contract.responseErrors(response);
            if (!self.isEmpty()) {
                selfCheckFailures.addAll(self);
            }
            inject(response, fault.get());
            if (fault.get() == Fault.DELAY) {
                Thread.sleep(delay.get());
            }
            byte[] body = JSON.writeValueAsBytes(response);
            issued.put(id, body);
            send(ex, 200, body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void inject(ObjectNode response, Fault fault) {
        ArrayNode results = (ArrayNode) response.get("results");
        switch (fault) {
            case NONE, DELAY, STATUS_422 -> {
            }
            case UNAVAILABLE_WITH_RANK -> {
                ObjectNode ok = firstOk(results);
                ok.put("status", "UNAVAILABLE").put("reason", "NO_RATE_DATA");
                ok.remove(List.of("ratioToAvg", "grade", "gradeLabel", "gradeOrdinal", "tie"));   // rankInSet은 남긴다
            }
            case NON_MONOTONIC -> {
                ObjectNode first = null;
                ObjectNode last = null;
                for (JsonNode r : results) {
                    if (r.path("status").asString().equals("OK")) {
                        if (first == null || r.get("rankInSet").asInt() < first.get("rankInSet").asInt()) {
                            first = (ObjectNode) r;
                        }
                        if (last == null || r.get("rankInSet").asInt() > last.get("rankInSet").asInt()) {
                            last = (ObjectNode) r;
                        }
                    }
                }
                int a = first.get("gradeOrdinal").asInt();
                first.put("gradeOrdinal", last.get("gradeOrdinal").asInt() + 1);
                last.put("gradeOrdinal", a);
            }
            case MISSING_PRODUCT -> results.remove(results.size() - 1);
            case EXTRA_PRODUCT -> results.addObject().put("productKey", "INS-Z:EXTRA-1").put("status", "UNAVAILABLE").put("reason", "NOT_IN_GROUP");
            case DISALLOWED_POLICY -> response.put("gradingPolicyVersionId", "GRADING-2099-01");
            case TIE_BREAK_STRICT -> response.put("tieBreak", "STRICT");
        }
    }

    private static ObjectNode firstOk(ArrayNode results) {
        for (JsonNode r : results) {
            if (r.path("status").asString().equals("OK")) {
                return (ObjectNode) r;
            }
        }
        throw new IllegalStateException("fault needs an OK result");
    }

    private static byte[] problem(String code) {
        return JSON.writeValueAsBytes(JSON.createObjectNode().put("code", code).put("message", code));
    }

    private static void send(HttpExchange ex, int status, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
    }
}
