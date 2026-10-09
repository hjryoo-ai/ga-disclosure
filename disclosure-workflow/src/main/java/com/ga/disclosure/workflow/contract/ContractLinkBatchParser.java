package com.ga.disclosure.workflow.contract;

import com.ga.platform.canonical.CanonicalizationException;
import com.ga.platform.canonical.Canonicalizer;
import com.ga.platform.canonical.Sha256;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 계약 연결 배치 파서: 엄격 UTF-8·엄격 JSON(중복 키 거부) → 계약 스키마({@code contracts/contract-link/v1}) → 날짜 실재. 문제를 모아 한 번에 실패하고,
 * 문제에는 위치(JSON 포인터)와 규칙 이름만 싣는다 — 스키마 검증기의 문장은 값을 담을 수 있어 쓰지 않는다.
 */
public final class ContractLinkBatchParser {

    private static final String BASE = "https://ga.example/contracts/";
    private static final String RESOURCE_ROOT = "/ga-contracts/";
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            b -> b.schemas(iri -> iri.startsWith(BASE) ? resource(iri.substring(BASE.length())) : null));
    private static final Schema SCHEMA = REGISTRY.getSchema(SchemaLocation.of(BASE + "contract-link/v1/contract-link-batch.schema.json"));

    private ContractLinkBatchParser() {
    }

    public static ContractLinkBatch parse(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            throw new InvalidContractLinkBatchException(List.of("not valid UTF-8"));
        }
        JsonNode root;
        try {
            root = Canonicalizer.parseStrict(text);
        } catch (CanonicalizationException e) {
            throw new InvalidContractLinkBatchException(List.of("not strict JSON"));
        }
        List<String> problems = new ArrayList<>(SCHEMA.validate(root).stream()
                .map(e -> e.getInstanceLocation() + " " + e.getKeyword()).distinct().sorted().toList());
        if (!problems.isEmpty()) {
            throw new InvalidContractLinkBatchException(problems);
        }
        List<ContractLinkBatch.Item> items = new ArrayList<>();
        int index = 0;
        for (JsonNode n : root.get("items")) {
            index++;
            LocalDate date;
            try {
                date = LocalDate.parse(n.get("contractDate").asString());
            } catch (DateTimeException e) {
                problems.add("/items/" + (index - 1) + "/contractDate date");
                continue;
            }
            items.add(new ContractLinkBatch.Item(index, n.get("policyNo").asString(), text(n, "applicationNo"), date, n.get("insurerCode").asString(),
                    text(n, "customerRef"), text(n, "productKey")));
        }
        if (!problems.isEmpty()) {
            throw new InvalidContractLinkBatchException(problems);
        }
        return new ContractLinkBatch(root.get("source").asString(), root.get("batchId").asString(), Sha256.of(Canonicalizer.canonicalize(root)), items);
    }

    private static Optional<String> text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null ? Optional.empty() : Optional.of(v.asString());
    }

    private static String resource(String relative) {
        try (InputStream in = ContractLinkBatchParser.class.getResourceAsStream(RESOURCE_ROOT + relative)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
