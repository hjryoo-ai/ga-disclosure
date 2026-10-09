package com.ga.disclosure.infra.engine.stub;

import com.ga.platform.canonical.Canonicalizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 데모 모드 엔진 스텁(설계서 부록 B, 3A 계획 §5): 데이터 파일의 고정표 <b>상품키 → {ratioToAvg 문자열, grade, gradeLabel, gradeOrdinal,
 * rankKey 정수}</b>로 계약 응답을 만든다. 비율 문자열에서 등급·순위를 계산하지 않는다(절대 규칙 1) — 순위는 표의 정수 {@code rankKey}로
 * 매기고(SHARED_RANK면 같은 rankKey가 동순위, STRICT면 rankKey가 서로 달라야 한다) 등급은 표에 적힌 값 그대로다. 표에 없는 상품은
 * 표의 {@code unknownReason}(엔진 데이터)으로 UNAVAILABLE. 운영 코드가 아니다 — 데모 프로파일과 테스트 대역({@code FakeEngine})만 쓴다.
 */
public final class TableEngineStub {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private record Row(String ratioToAvg, String grade, String gradeLabel, int gradeOrdinal, int rankKey) {
    }

    private final String gradingPolicy;
    private final String rankingPolicy;
    private final String tieBreak;
    private final JsonNode basis;
    private final String unknownReason;
    private final Set<String> groups;
    private final Map<String, Row> rows;

    private TableEngineStub(JsonNode table) {
        this.gradingPolicy = table.required("gradingPolicyVersionId").asString();
        this.rankingPolicy = table.required("rankingPolicyVersionId").asString();
        this.tieBreak = table.required("tieBreak").asString();
        this.basis = table.required("basis").deepCopy();
        this.unknownReason = table.required("unknownReason").asString();
        this.groups = new HashSet<>();
        table.required("productGroups").forEach(g -> groups.add(g.asString()));
        this.rows = new LinkedHashMap<>();
        for (JsonNode r : table.required("products")) {
            Row row = new Row(r.required("ratioToAvg").asString(), r.required("grade").asString(), r.required("gradeLabel").asString(),
                    r.required("gradeOrdinal").asInt(), r.required("rankKey").asInt());
            if (rows.put(r.required("productKey").asString(), row) != null) {
                throw new IllegalArgumentException("engine table lists " + r.get("productKey") + " twice");
            }
        }
    }

    /** 표 파일(JSON, 엄격 파싱). */
    public static TableEngineStub load(String json) {
        return new TableEngineStub(Canonicalizer.parseStrict(json));
    }

    public boolean servesGroup(String group) {
        return groups.contains(group);
    }

    /** 계약 요청 → 계약 응답(스냅샷 ID·발급 시각은 호출자가 준다). */
    public ObjectNode respond(JsonNode request, String snapshotId, Instant generatedAt) {
        List<String> requested = new ArrayList<>();
        request.get("products").forEach(p -> requested.add(p.get("productKey").asString()));
        List<String> ok = requested.stream().filter(rows::containsKey).toList();
        List<String> byRank = new ArrayList<>(ok);
        byRank.sort(Comparator.comparingInt((String k) -> rows.get(k).rankKey()).thenComparing(Comparator.naturalOrder()));
        Map<String, Integer> rank = new HashMap<>();
        Map<Integer, Integer> sharing = new HashMap<>();
        for (int i = 0; i < byRank.size(); i++) {
            String k = byRank.get(i);
            boolean sameAsPrevious = i > 0 && rows.get(byRank.get(i - 1)).rankKey() == rows.get(k).rankKey();
            if (sameAsPrevious && tieBreak.equals("STRICT")) {
                throw new IllegalStateException("engine table: STRICT needs distinct rankKeys (" + byRank.get(i - 1) + ", " + k + ")");
            }
            rank.put(k, sameAsPrevious ? rank.get(byRank.get(i - 1)) : i + 1);
            sharing.merge(rank.get(k), 1, Integer::sum);
        }
        ObjectNode out = JSON.createObjectNode();
        out.put("snapshotId", snapshotId);
        out.put("gradingPolicyVersionId", gradingPolicy);
        out.put("rankingPolicyVersionId", rankingPolicy);
        out.put("tieBreak", tieBreak);
        out.set("basis", basis.deepCopy());
        ArrayNode results = out.putArray("results");
        for (String k : requested) {
            Row r = rows.get(k);
            if (r == null) {
                results.addObject().put("productKey", k).put("status", "UNAVAILABLE").put("reason", unknownReason);
            } else {
                results.addObject().put("productKey", k).put("status", "OK").put("ratioToAvg", r.ratioToAvg()).put("grade", r.grade())
                        .put("gradeLabel", r.gradeLabel()).put("gradeOrdinal", r.gradeOrdinal()).put("rankInSet", rank.get(k))
                        .put("tie", sharing.get(rank.get(k)) > 1);
            }
        }
        // RFC 3339 — 초를 늘 쓴다(OffsetDateTime.toString()은 0초를 생략해 계약 1.2.1 pattern에 어긋났다). 엔진과 같은 형식기
        out.put("generatedAt", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(generatedAt.atOffset(ZoneOffset.ofHours(9))));
        return out;
    }

    /** 엔진 E3.1 형식의 스냅샷 ID({@code GRD-yyyyMMdd-} + 7자리 일련, 일자별로 다시 세지 않는다). */
    public static String snapshotId(Instant at, long sequence) {
        Objects.checkIndex(sequence, 9_000_000L);
        return "GRD-" + DAY.format(at.atOffset(ZoneOffset.ofHours(9)).toLocalDate()) + "-" + (1_000_000L + sequence);
    }
}
