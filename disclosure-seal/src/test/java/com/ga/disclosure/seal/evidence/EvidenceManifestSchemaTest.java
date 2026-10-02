package com.ga.disclosure.seal.evidence;

import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** G8: 매니페스트 스키마는 닫혀 있다 — 빌더 산출물은 통과하고, 키 추가·형식 위반·앵커 값·빈 목록은 실패한다. */
class EvidenceManifestSchemaTest {

    private static ObjectNode manifest() {
        byte[] zip = EvidencePackageBuilder.build(EvidenceFixtures.input()).zip();
        return (ObjectNode) Canonicalizer.parseStrict(new String(EvidencePackageReader.entries(zip).get("manifest.json"), StandardCharsets.UTF_8));
    }

    @Test
    void builderOutputIsValid() {
        assertThat(EvidenceManifestSchema.validateManifest(manifest())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/extra|1",
            "/manifestVersion|2",
            "/anchor|{\"ref\":\"x\"}",
            "/hashes/pdf|\"ABC\"",
            "/hashes/extra|1",
            "/disclosureNo|\"demo1-2026-1\"",
            "/signatures|[]",
            "/signatures/0/evidence/0/kind|\"VIDEO\"",
            "/signatures/0/identityCheck/0/result|\"MAYBE\"",
            "/signatures/0/identityCheck/0/input|\"19800101\"",
            "/signatures/0/file|\"../x.json\"",
            "/audit/file|\"other.jsonl\"",
            "/files|[]",
            "/sealedAt|\"2026-09-23 10:00\""})
    void closedAndTyped(String spec) {
        String[] p = spec.split("\\|", 2);
        ObjectNode m = manifest();
        JsonNode value = Canonicalizer.parseStrict(p[1]);
        int slash = p[0].lastIndexOf('/');
        JsonNode parent = slash == 0 ? m : m.at(p[0].substring(0, slash));
        String key = p[0].substring(slash + 1);
        if (parent.isArray()) {
            ((tools.jackson.databind.node.ArrayNode) parent).set(Integer.parseInt(key), value);
        } else {
            ((ObjectNode) parent).set(key, value);
        }
        assertThat(EvidenceManifestSchema.validateManifest(m)).as(spec).isNotEmpty();
    }
}
