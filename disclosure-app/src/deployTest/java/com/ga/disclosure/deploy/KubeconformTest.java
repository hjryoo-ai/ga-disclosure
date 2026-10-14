package com.ga.disclosure.deploy;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G4(Phase 8 ③): 렌더 결과 전부가 스키마를 지난다 — 쿠버네티스 기본 리소스는 커밋 고정 스키마(tools.lock {@code k8s-schemas}, strict — 모르는 필드 거부),
 * CRD 리소스(Traefik·ESO)는 저장소의 CRD 사본에서 만든 스키마. 건너뛰는 것은 CRD 정의 자체뿐이고(고정 스키마 저장소에 그 스키마가 없다), 그 정의는 해시로
 * 고정한 상류 사본과 같아야 한다 — 스키마 없는 종류가 조용히 통과하지 않게.
 */
class KubeconformTest {

    static final List<String> OVERLAYS = Manifests.OVERLAYS;
    static final List<String> CRD_FILES = List.of("deploy/components/ingress/traefik/kubernetes-crd-definition-v1.yml",
            "deploy/components/secrets-eso/crds/external-secrets.io_externalsecrets.yaml", "deploy/components/secrets-eso/crds/external-secrets.io_secretstores.yaml");
    static final JsonMapper JSON = JsonMapper.builder().build();

    /** CRD 사본 → {@code <dir>/<group>/<kind 소문자>_<version>.json}(kubeconform의 {@code {{ .Group }}/{{ .ResourceKind }}_{{ .ResourceAPIVersion }}}). */
    static int crdSchemas(Path dir) throws IOException {
        int n = 0;
        for (String f : CRD_FILES) {
            for (JsonNode crd : Manifests.parse(Manifests.read(Manifests.repoRoot().resolve(f)))) {
                if (!crd.path("kind").asString("").equals("CustomResourceDefinition")) {
                    continue;
                }
                String group = crd.path("spec").path("group").asString();
                String kind = crd.path("spec").path("names").path("kind").asString().toLowerCase(Locale.ROOT);
                for (JsonNode v : crd.path("spec").path("versions")) {
                    Path out = dir.resolve(group).resolve(kind + "_" + v.path("name").asString() + ".json");
                    Files.createDirectories(out.getParent());
                    Files.writeString(out, JSON.writeValueAsString(v.path("schema").path("openAPIV3Schema")));
                    n++;
                }
            }
        }
        return n;
    }

    @ParameterizedTest
    @FieldSource("OVERLAYS")
    void everyRenderedResourceIsValidAgainstAPinnedSchema(String overlay, @TempDir Path dir) throws Exception {
        assertThat(crdSchemas(dir.resolve("crd"))).isGreaterThanOrEqualTo(12);
        Path rendered = dir.resolve(overlay + ".yaml");
        Files.writeString(rendered, Manifests.render(overlay));
        ToolsLock k8s = ToolsLock.named("k8s-schemas").orElseThrow();
        Path cache = Manifests.repoRoot().resolve("build/kubeconform-cache");
        Files.createDirectories(cache);
        String out = Manifests.run(Manifests.tool("kubeconform").toString(), "-strict", "-summary", "-output", "json",
                "-kubernetes-version", k8s.version().substring(1), "-cache", cache.toString(),
                "-schema-location", k8s.ref() + "/{{ .NormalizedKubernetesVersion }}-standalone{{ .StrictSuffix }}/{{ .ResourceKind }}{{ .KindSuffix }}.json",
                "-schema-location", dir.resolve("crd") + "/{{ .Group }}/{{ .ResourceKind }}_{{ .ResourceAPIVersion }}.json",
                "-skip", "CustomResourceDefinition", rendered.toString());
        JsonNode summary = JSON.readTree(out).path("summary");
        List<JsonNode> docs = Manifests.docs(overlay);
        List<String> crds = Manifests.ofKind(docs, "CustomResourceDefinition").map(Manifests::name).sorted().toList();
        assertThat(summary.path("invalid").asInt(-1)).as(out).isZero();
        assertThat(summary.path("errors").asInt(-1)).as(out).isZero();
        assertThat(summary.path("skipped").asInt(-1)).as(out).isEqualTo(crds.size());
        assertThat(summary.path("valid").asLong()).as(out).isEqualTo(docs.size() - crds.size());
        // 건너뛴 것은 CRD 정의 자체뿐이고(고정 스키마 저장소에 CustomResourceDefinition 스키마가 없다 — 2026-10-10 404 확인), 그것은 tools.lock 해시로 고정한
        // 상류 사본 그대로다(vendoredFilesMatchTheirLockedHashes). 클러스터의 API 서버가 적용 때 다시 검증한다(kind — 9c).
        assertThat(crds).isEqualTo(vendoredCrdNames().stream().filter(crds::contains).toList()).isNotEmpty();
        assertThat(Manifests.ofKind(docs, "CustomResourceDefinition")).allSatisfy(crd ->
                assertThat(vendoredCrd(Manifests.name(crd))).as(Manifests.name(crd)).isEqualTo(crd));
    }

    static List<String> vendoredCrdNames() {
        return CRD_FILES.stream().flatMap(f -> Manifests.parse(Manifests.read(Manifests.repoRoot().resolve(f))).stream())
                .filter(d -> d.path("kind").asString("").equals("CustomResourceDefinition")).map(Manifests::name).sorted().toList();
    }

    static JsonNode vendoredCrd(String name) {
        return CRD_FILES.stream().flatMap(f -> Manifests.parse(Manifests.read(Manifests.repoRoot().resolve(f))).stream())
                .filter(d -> Manifests.name(d).equals(name)).findFirst().orElse(null);
    }
}
