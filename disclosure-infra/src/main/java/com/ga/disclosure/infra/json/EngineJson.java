package com.ga.disclosure.infra.json;

import com.ga.disclosure.domain.enums.GradeStatus;
import com.ga.disclosure.domain.enums.TieBreak;
import com.ga.disclosure.domain.grade.EngineSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshotItem;
import com.ga.disclosure.domain.grade.RatioLabel;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.domain.vo.SnapshotId;
import com.ga.disclosure.workflow.disclosure.EngineRequest;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 엔진 요청·응답 JSON 매핑(계약 스키마 검증을 통과한 본문만 받는다). {@code ratioToAvg}는 문자열 원문 그대로 {@link RatioLabel}로 싣고
 * 숫자로 해석하지 않는다(절대 규칙 1). {@code basis}는 해석하지 않고 정규형 텍스트로 보존한다(V6 {@code grade_basis}).
 */
public final class EngineJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private EngineJson() {
    }

    public static ObjectNode request(TenantId tenant, EngineRequest request) {
        ObjectNode n = JSON.createObjectNode();
        n.put("tenantId", tenant.value());
        n.put("asOfDate", request.asOf().toString());
        n.put("productGroupCode", request.group().value());
        ArrayNode products = n.putArray("products");
        for (ProductKey k : request.products()) {
            products.addObject().put("productKey", k.value()).put("insurerCode", k.insurer().value());
        }
        return n;
    }

    public static byte[] bytes(JsonNode node) {
        return JSON.writeValueAsBytes(node);
    }

    /** 스키마를 통과한 응답을 도메인으로 옮긴다. 결과 순서는 엔진이 준 그대로다. */
    public static EngineSnapshot snapshot(JsonNode response) {
        List<GradeSnapshotItem> items = new ArrayList<>();
        for (JsonNode r : response.get("results")) {
            ProductKey key = ProductKey.parse(r.get("productKey").asString());
            if (GradeStatus.valueOf(r.get("status").asString()) == GradeStatus.OK) {
                items.add(GradeSnapshotItem.ok(key, r.get("grade").asString(), r.get("gradeLabel").asString(), r.get("gradeOrdinal").asInt(),
                        r.get("rankInSet").asInt(), r.get("tie").asBoolean(), new RatioLabel(r.get("ratioToAvg").asString())));
            } else {
                items.add(GradeSnapshotItem.unavailable(key, r.get("reason").asString()));
            }
        }
        GradeSnapshot snapshot = new GradeSnapshot(SnapshotId.of(response.get("snapshotId").asString()),
                response.get("gradingPolicyVersionId").asString(), response.get("rankingPolicyVersionId").asString(),
                TieBreak.valueOf(response.get("tieBreak").asString()), items);
        String basis = new String(Canonicalizer.canonicalize(response.get("basis")), StandardCharsets.UTF_8);
        return new EngineSnapshot(snapshot, basis, OffsetDateTime.parse(response.get("generatedAt").asString()).toInstant());
    }
}
