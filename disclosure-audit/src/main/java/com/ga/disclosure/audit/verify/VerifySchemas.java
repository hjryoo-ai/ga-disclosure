package com.ga.disclosure.audit.verify;

import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 계약 스키마 검증(클래스패스 {@code ga-contracts/} — 빌드가 {@code contracts/seal}·{@code contracts/verify}를 싣는다). 증거 매니페스트는 생산자(seal)의
 * 검증기를 거치지 않고 같은 계약 파일로 직접 검증한다.
 */
public final class VerifySchemas {

    private static final String BASE = "https://ga.example/contracts/";
    private static final String RESOURCE_ROOT = "/ga-contracts/";
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            b -> b.schemas(iri -> iri.startsWith(BASE) ? resource(iri.substring(BASE.length())) : null));
    private static final Schema MANIFEST = REGISTRY.getSchema(SchemaLocation.of(BASE + "seal/v1/evidence-manifest.schema.json"));
    private static final Schema SIGNATURE_FILE = REGISTRY.getSchema(SchemaLocation.of(BASE + "seal/v1/evidence-manifest.schema.json#/$defs/signatureFile"));
    private static final Schema RECEIPT_EXPORT = REGISTRY.getSchema(SchemaLocation.of(BASE + "verify/v1/anchor-receipt-export.schema.json"));
    private static final Schema REPORT = REGISTRY.getSchema(SchemaLocation.of(BASE + "verify/v1/verify-report.schema.json"));
    private static final Schema DESTRUCTION_REPORT = REGISTRY.getSchema(SchemaLocation.of(BASE + "verify/v1/destruction-report.schema.json"));
    private static final Schema RECOMPUTE_REPORT = REGISTRY.getSchema(SchemaLocation.of(BASE + "verify/v1/retention-recompute-report.schema.json"));

    private VerifySchemas() {
    }

    public static List<String> manifest(JsonNode node) {
        return errors(MANIFEST, node);
    }

    public static List<String> signatureFile(JsonNode node) {
        return errors(SIGNATURE_FILE, node);
    }

    public static List<String> receiptExport(JsonNode node) {
        return errors(RECEIPT_EXPORT, node);
    }

    public static List<String> report(JsonNode node) {
        return errors(REPORT, node);
    }

    /** 파기 실행 보고서(5 계획 §8.4). */
    public static List<String> destructionReport(JsonNode node) {
        return errors(DESTRUCTION_REPORT, node);
    }

    /** 보존 재계산 보고서(6B 계획 §8). */
    public static List<String> retentionRecomputeReport(JsonNode node) {
        return errors(RECOMPUTE_REPORT, node);
    }

    private static List<String> errors(Schema schema, JsonNode node) {
        return schema.validate(node).stream().map(Object::toString).toList();
    }

    private static String resource(String relative) {
        try (InputStream in = VerifySchemas.class.getResourceAsStream(RESOURCE_ROOT + relative)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
