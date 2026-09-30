package com.ga.disclosure.workflow.catalog;

import com.ga.disclosure.domain.enums.InsuranceLine;
import com.ga.disclosure.domain.vo.GroupCode;
import com.ga.disclosure.domain.vo.InsurerCode;
import com.ga.disclosure.domain.vo.ProductKey;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 카탈로그 수입 파일 파서: 엄격 UTF-8·엄격 JSON(중복 키 거부) → 계약 스키마({@code contracts/catalog/v1}) → 의미 규칙.
 * 의미 규칙: 날짜 실재, 구간 [from, to)에서 to ≥ from, 키 중복 금지(상품군 코드·상품키·패널 (보험사, activeFrom)),
 * 상품키 접두 = insurerCode, {@code defaults}에 정수가 아닌 숫자 금지(금액은 정수 원). 문제를 전부 모아 한 번에 실패한다.
 */
public final class CatalogFileParser {

    private static final String BASE = "https://ga.example/contracts/";
    private static final String RESOURCE_ROOT = "/ga-contracts/";
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            b -> b.schemas(iri -> iri.startsWith(BASE) ? resource(iri.substring(BASE.length())) : null));
    private static final Schema SCHEMA = REGISTRY.getSchema(SchemaLocation.of(BASE + "catalog/v1/catalog-file.schema.json"));

    private CatalogFileParser() {
    }

    public static CatalogFile parse(byte[] content) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            throw new InvalidCatalogFileException(List.of("file is not valid UTF-8"));
        }
        JsonNode root;
        try {
            root = Canonicalizer.parseStrict(text);
        } catch (CanonicalizationException e) {
            throw new InvalidCatalogFileException(List.of("not strict JSON: " + e.getMessage()));
        }
        List<String> problems = new ArrayList<>(SCHEMA.validate(root).stream().map(Object::toString).toList());
        if (!problems.isEmpty()) {
            throw new InvalidCatalogFileException(problems);
        }
        CatalogKind kind = CatalogKind.valueOf(root.get("kind").asString());
        LocalDate asOf = date(root, "asOf", "asOf", problems);
        List<ProductGroup> groups = new ArrayList<>();
        List<CatalogProduct> products = new ArrayList<>();
        List<PanelEntry> insurers = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        switch (kind) {
            case PRODUCT_GROUPS -> each(root, "groups", (path, n) -> {
                String code = n.get("groupCode").asString();
                unique(keys, code, path, problems);
                LocalDate from = date(n, "applyFrom", path, problems);
                LocalDate to = openDate(n, "applyTo", path, problems);
                if (from != null && range(from, to, path, problems)) {
                    groups.add(new ProductGroup(GroupCode.of(code), n.get("name").asString(),
                            InsuranceLine.valueOf(n.get("line").asString()), from, to));
                }
            });
            case PRODUCTS -> each(root, "products", (path, n) -> {
                ProductKey key = ProductKey.parse(n.get("productKey").asString());
                unique(keys, key.value(), path, problems);
                if (!key.insurer().equals(InsurerCode.of(n.get("insurerCode").asString()))) {
                    problems.add(path + ": productKey prefix " + key.insurer() + " differs from insurerCode " + n.get("insurerCode").asString());
                }
                nonIntegralNumbers(n.get("defaults"), path + ".defaults", problems);
                LocalDate from = date(n, "saleFrom", path, problems);
                LocalDate to = openDate(n, "saleTo", path, problems);
                if (from != null && range(from, to, path, problems)) {
                    products.add(new CatalogProduct(key, GroupCode.of(n.get("groupCode").asString()), n.get("productName").asString(),
                            from, to, n.get("defaults")));
                }
            });
            case INSURER_PANEL -> each(root, "insurers", (path, n) -> {
                LocalDate from = date(n, "activeFrom", path, problems);
                LocalDate to = openDate(n, "activeTo", path, problems);
                unique(keys, n.get("insurerCode").asString() + "@" + from, path, problems);
                if (from != null && range(from, to, path, problems)) {
                    insurers.add(new PanelEntry(InsurerCode.of(n.get("insurerCode").asString()), n.get("insurerName").asString(),
                            InsuranceLine.valueOf(n.get("line").asString()), from, to));
                }
            });
        }
        if (!problems.isEmpty()) {
            throw new InvalidCatalogFileException(problems);
        }
        return new CatalogFile(kind, root.get("source").asString(), asOf, Sha256.of(content), groups, products, insurers);
    }

    private interface Row {
        void accept(String path, JsonNode node);
    }

    private static void each(JsonNode root, String array, Row row) {
        JsonNode items = root.get(array);
        for (int i = 0; i < items.size(); i++) {
            row.accept(array + "[" + i + "]", items.get(i));
        }
    }

    private static void unique(Set<String> seen, String key, String path, List<String> problems) {
        if (!seen.add(key)) {
            problems.add(path + ": duplicate key " + key);
        }
    }

    private static boolean range(LocalDate from, LocalDate to, String path, List<String> problems) {
        if (to != null && to.isBefore(from)) {
            problems.add(path + ": end " + to + " is before start " + from);
            return false;
        }
        return true;
    }

    private static LocalDate date(JsonNode parent, String field, String path, List<String> problems) {
        try {
            return LocalDate.parse(parent.get(field).asString());
        } catch (DateTimeException e) {
            problems.add(path + "." + field + ": not a calendar date");
            return null;
        }
    }

    private static LocalDate openDate(JsonNode parent, String field, String path, List<String> problems) {
        JsonNode n = parent.get(field);
        return n == null || n.isNull() ? null : date(parent, field, path, problems);
    }

    private static void nonIntegralNumbers(JsonNode node, String path, List<String> problems) {
        if (node.isNumber() && !node.isIntegralNumber()) {
            problems.add(path + ": non-integer number (amounts are integer won)");
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                nonIntegralNumbers(node.get(i), path + "[" + i + "]", problems);
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                nonIntegralNumbers(e.getValue(), path + "." + e.getKey(), problems);
            }
        }
    }

    private static String resource(String relative) {
        try (InputStream in = CatalogFileParser.class.getResourceAsStream(RESOURCE_ROOT + relative)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
