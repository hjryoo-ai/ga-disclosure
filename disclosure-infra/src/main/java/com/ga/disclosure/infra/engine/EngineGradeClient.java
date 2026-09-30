package com.ga.disclosure.infra.engine;

import com.ga.disclosure.domain.grade.EngineSnapshot;
import com.ga.disclosure.domain.vo.SnapshotId;
import com.ga.disclosure.infra.json.EngineJson;
import com.ga.disclosure.rules.grade.GradeConsistencyCheck;
import com.ga.disclosure.workflow.disclosure.EngineRequest;
import com.ga.disclosure.workflow.disclosure.EngineUnavailableException;
import com.ga.disclosure.workflow.disclosure.GradeSnapshotPort;
import com.ga.platform.canonical.CanonicalizationException;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.core.tenant.TenantId;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * {@link GradeSnapshotPort} 어댑터(설계서 §4.1·§6.3). 파이프라인: 요청을 계약 요청 스키마로 자기 검증 → 전송 → 상태 코드(200이 아니면
 * {@link EngineUnavailableException}, 재시도 없음) → 엄격 UTF-8·엄격 JSON → <b>계약 응답 스키마</b>(oneOf 분기 포함) → 도메인 매핑 →
 * {@link GradeConsistencyCheck}(요청 집합·순위·단조·허용 정책·UNAVAILABLE 형태). 스키마·매핑·정합성 중 하나라도 실패하면 스냅샷을 만들지 않고
 * {@link Fetch.Rejected}다 — 워크플로가 {@code GRADE_INCONSISTENT} 플래그를 올린다.
 */
public final class EngineGradeClient implements GradeSnapshotPort {

    private static final Pattern PROBLEM_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final EngineTransport transport;
    private final EngineContract contract;

    public EngineGradeClient(EngineTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.contract = EngineContract.get();
    }

    @Override
    public Fetch request(TenantId tenant, EngineRequest request, Allowance allowance) {
        ObjectNode body = EngineJson.request(tenant, request);
        List<String> own = contract.requestErrors(body);
        if (!own.isEmpty()) {
            throw new IllegalArgumentException("engine request violates contract " + contract.version() + ": " + own);
        }
        return evaluate(transport.post(tenant, EngineTransport.GRADES_PATH, EngineJson.bytes(body)), request, allowance, null);
    }

    @Override
    public Fetch refetch(TenantId tenant, SnapshotId snapshotId, EngineRequest request, Allowance allowance) {
        return evaluate(transport.get(tenant, EngineTransport.GRADES_PATH + "/" + snapshotId.value()), request, allowance, snapshotId);
    }

    private Fetch evaluate(EngineTransport.Response response, EngineRequest request, Allowance allowance, SnapshotId expectedOrNull) {
        if (response.status() != 200) {
            throw new EngineUnavailableException(errorCode(response), null);
        }
        JsonNode node;
        try {
            node = Canonicalizer.parseStrict(utf8(response.body()));
        } catch (CanonicalizationException | CharacterCodingException e) {
            return new Fetch.Rejected(null, List.of("schema: response is not strict UTF-8 JSON"));
        }
        String snapshotId = node.path("snapshotId").isString() ? node.path("snapshotId").asString() : null;
        List<String> errors = contract.responseErrors(node);
        if (!errors.isEmpty()) {
            return new Fetch.Rejected(snapshotId, errors.stream().map(e -> "schema: " + e).toList());
        }
        EngineSnapshot snapshot;
        try {
            snapshot = EngineJson.snapshot(node);
        } catch (RuntimeException e) {
            return new Fetch.Rejected(snapshotId, List.of("mapping: " + e.getClass().getSimpleName()));
        }
        if (expectedOrNull != null && !snapshot.snapshot().snapshotId().equals(expectedOrNull)) {
            return new Fetch.Rejected(snapshotId, List.of("refetch returned snapshot " + snapshotId + " for " + expectedOrNull));
        }
        List<String> violations = GradeConsistencyCheck.violations(request.products(), snapshot.snapshot(), allowance.gradingPolicies(),
                allowance.rankingPolicies(), allowance.tieBreaks());
        return violations.isEmpty() ? new Fetch.Accepted(snapshot) : new Fetch.Rejected(snapshotId, violations);
    }

    private static String utf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    /** {@code ENGINE_<상태>[_<Problem.code>]}. 본문이 계약의 Problem이 아니면 코드 없이. */
    private String errorCode(EngineTransport.Response response) {
        String base = "ENGINE_" + response.status();
        try {
            JsonNode problem = Canonicalizer.parseStrict(utf8(response.body()));
            if (contract.problemErrors(problem).isEmpty() && PROBLEM_CODE.matcher(problem.get("code").asString()).matches()) {
                return base + "_" + problem.get("code").asString();
            }
        } catch (CharacterCodingException | RuntimeException e) {
            // 본문을 해석할 수 없는 오류 응답 — 상태 코드만 남긴다
        }
        return base;
    }
}
