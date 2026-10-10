package com.ga.disclosure.deploy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 오버레이 렌더(Phase 8 ③): {@code build/tools/kubectl kustomize deploy/overlays/<이름>}(tools.lock 고정판 — 내장 kustomize)의 출력을 문서 목록으로.
 * 린트는 저장소의 원본이 아니라 클러스터에 갈 렌더 결과를 본다(패치·컴포넌트가 규칙을 깨는 것도 잡힌다).
 */
public final class Manifests {

    /** 린트 대상 오버레이. */
    public static final List<String> OVERLAYS = List.of("prod", "kind-demo", "kind-restore");

    static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final Map<String, String> RENDERED = new ConcurrentHashMap<>();

    private Manifests() {
    }

    public static Path repoRoot() {
        return Path.of(System.getProperty("ga.repoRoot"));
    }

    public static Path tool(String name) {
        return Path.of(System.getProperty("ga.tools"), name);
    }

    public static String render(String overlay) {
        // kind 데모는 저장소의 원본 파일(롤 SQL·엔진 스텁 표)을 사본 없이 쓴다 — 오버레이 밖 파일 읽기 허용(스크립트와 같은 플래그)
        return RENDERED.computeIfAbsent(overlay, o -> run(tool("kubectl").toString(), "kustomize", "--load-restrictor", "LoadRestrictionsNone",
                repoRoot().resolve("deploy/overlays/" + o).toString()));
    }

    public static List<JsonNode> docs(String overlay) {
        return parse(render(overlay));
    }

    /** 여러 문서 YAML(빈 문서 제외). */
    public static List<JsonNode> parse(String yaml) {
        List<JsonNode> out = new ArrayList<>();
        try (MappingIterator<JsonNode> it = YAML.readerFor(JsonNode.class).readValues(yaml)) {
            while (it.hasNext()) {
                JsonNode n = it.next();
                if (n != null && !n.isMissingNode() && !n.isNull()) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    public static Stream<JsonNode> ofKind(List<JsonNode> docs, String kind) {
        return docs.stream().filter(d -> d.path("kind").asString("").equals(kind));
    }

    public static String name(JsonNode doc) {
        return doc.path("metadata").path("name").asString("");
    }

    /** 문서 하나의 파드 명세(Deployment·Job·CronJob)와 그 문서. */
    public record Pod(JsonNode owner, JsonNode spec) {
        public String label() {
            return owner.path("kind").asString() + "/" + name(owner);
        }

        public Stream<JsonNode> containers() {
            return Stream.concat(stream(spec.path("initContainers")), stream(spec.path("containers")));
        }
    }

    public static List<Pod> pods(List<JsonNode> docs) {
        List<Pod> out = new ArrayList<>();
        for (JsonNode d : docs) {
            switch (d.path("kind").asString("")) {
                case "Deployment", "StatefulSet", "Job" -> out.add(new Pod(d, d.path("spec").path("template").path("spec")));
                case "CronJob" -> out.add(new Pod(d, d.path("spec").path("jobTemplate").path("spec").path("template").path("spec")));
                default -> {
                }
            }
        }
        return out;
    }

    public static Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> out = new ArrayList<>();
        array.forEach(out::add);
        return out.stream();
    }

    public static List<String> strings(JsonNode array) {
        return stream(array).map(n -> n.asString()).toList();
    }

    static String run(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                throw new IllegalStateException(String.join(" ", command) + " failed:\n" + out);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Predicate<JsonNode> named(String name) {
        return d -> name(d).equals(name);
    }
}
