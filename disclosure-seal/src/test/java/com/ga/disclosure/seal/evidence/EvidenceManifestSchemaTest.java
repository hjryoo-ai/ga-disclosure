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

    /** Phase 5 추가형: 완료 시점의 최신 앵커 참조 객체(키 5개, 닫힘). null은 여전히 유효하다 — 기존 패키지는 다시 만들지 않는다. */
    @Test
    void anAnchorReferenceIsAClosedObjectAndNullStaysValid() {
        EvidenceInput base = EvidenceFixtures.input();
        EvidenceInput anchored = new EvidenceInput(base.tenantId(), base.disclosureId(), base.disclosureNo(), base.version(), base.canonicalJson(),
                base.pdf(), base.signedPdf(), base.chainHash(), base.chainSeq(), base.pinned(), base.snapshot(), base.sealedAt(), base.completedAt(),
                base.retentionUntil(), base.signatures(), base.audit(),
                new EvidenceInput.AnchorRef(3, java.time.LocalDate.parse("2026-09-22"), "a".repeat(64), 0, 41), 2);
        byte[] zip = EvidencePackageBuilder.build(anchored).zip();
        ObjectNode m = (ObjectNode) Canonicalizer.parseStrict(new String(EvidencePackageReader.entries(zip).get("manifest.json"), StandardCharsets.UTF_8));

        assertThat(EvidenceManifestSchema.validateManifest(m)).isEmpty();
        assertThat(m.get("anchor").toString()).isEqualTo("{\"anchorDate\":\"2026-09-22\",\"anchorSeq\":3,\"auditSeq\":41,\"leafHash\":\""
                + "a".repeat(64) + "\",\"sealChainSeq\":0}");
        assertThat(manifest().get("anchor").isNull()).isTrue();
    }

    /** Phase 8: 새 패키지는 2판(rendererVersion 포함), 1판 모양(rendererVersion 없음)도 계약상 유효하다 — 저장된 1판 패키지가 계속 검증된다. */
    @Test
    void manifestTwoCarriesTheRendererVersionAndOneStaysValid() {
        ObjectNode m = manifest();
        assertThat(m.get("manifestVersion").asInt()).isEqualTo(2);
        assertThat(m.get("rendererVersion").asInt()).isEqualTo(2);
        ObjectNode v1 = m.deepCopy();
        v1.put("manifestVersion", 1);
        v1.remove("rendererVersion");
        assertThat(EvidenceManifestSchema.validateManifest(v1)).isEmpty();
        ObjectNode v2WithoutVersion = m.deepCopy();
        v2WithoutVersion.remove("rendererVersion");
        assertThat(EvidenceManifestSchema.validateManifest(v2WithoutVersion)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/extra|1",
            "/manifestVersion|3",
            "/manifestVersion|1",                                         // 1판에는 rendererVersion이 없다(Phase 8)
            "/rendererVersion|0",
            "/rendererVersion|\"2\"",
            "/anchor|{\"ref\":\"x\"}",
            "/anchor|{\"anchorSeq\":3,\"anchorDate\":\"2026-09-22\",\"leafHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"sealChainSeq\":0,\"auditSeq\":41,\"root\":\"x\"}",
            "/anchor|{\"anchorSeq\":3,\"anchorDate\":\"2026-09-22\",\"leafHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"sealChainSeq\":0}",
            "/anchor|{\"anchorSeq\":0,\"anchorDate\":\"2026-09-22\",\"leafHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"sealChainSeq\":0,\"auditSeq\":41}",
            "/anchor|{\"anchorSeq\":3,\"anchorDate\":\"2026-9-22\",\"leafHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"sealChainSeq\":0,\"auditSeq\":41}",
            "/anchor|{\"anchorSeq\":3,\"anchorDate\":\"2026-09-22\",\"leafHash\":\"ABC\",\"sealChainSeq\":0,\"auditSeq\":41}",
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
