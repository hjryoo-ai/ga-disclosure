package com.ga.disclosure.seal.evidence;

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
 * 증거 매니페스트 스키마({@code contracts/seal/v1/evidence-manifest.schema.json}) 검증. 매니페스트와 서명 레코드 파일
 * ({@code $defs/signatureFile})을 패키지에 넣기 전에 검증하고, 위반이면 패키지를 만들지 않는다. 스키마는 클래스패스 {@code ga-contracts/seal/}.
 */
public final class EvidenceManifestSchema {

    private static final String BASE = "https://ga.example/contracts/";
    private static final String RESOURCE_ROOT = "/ga-contracts/";
    private static final String LOCATION = BASE + "seal/v1/evidence-manifest.schema.json";
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
            b -> b.schemas(iri -> iri.startsWith(BASE) ? resource(iri.substring(BASE.length())) : null));
    private static final Schema MANIFEST = REGISTRY.getSchema(SchemaLocation.of(LOCATION));
    private static final Schema SIGNATURE_FILE = REGISTRY.getSchema(SchemaLocation.of(LOCATION + "#/$defs/signatureFile"));

    private EvidenceManifestSchema() {
    }

    public static List<String> validateManifest(JsonNode manifest) {
        return MANIFEST.validate(manifest).stream().map(Object::toString).toList();
    }

    public static List<String> validateSignatureFile(JsonNode signature) {
        return SIGNATURE_FILE.validate(signature).stream().map(Object::toString).toList();
    }

    private static String resource(String relative) {
        try (InputStream in = EvidenceManifestSchema.class.getResourceAsStream(RESOURCE_ROOT + relative)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
