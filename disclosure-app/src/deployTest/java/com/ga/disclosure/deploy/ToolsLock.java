package com.ga.disclosure.deploy;

import java.util.List;
import java.util.Optional;

/** {@code deploy/tools.lock}의 줄(이름 버전 플랫폼 참조 SHA-256). */
public record ToolsLock(String name, String version, String platform, String ref, String sha256) {

    public static List<ToolsLock> rows() {
        return Manifests.read(Manifests.repoRoot().resolve("deploy/tools.lock")).lines().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .map(l -> l.split("\\s+")).map(f -> new ToolsLock(f[0], f[1], f[2], f[3], f[4])).toList();
    }

    public static Optional<ToolsLock> named(String name) {
        return rows().stream().filter(r -> r.name().equals(name)).findFirst();
    }

    /** 저장소 안 파일을 가리키는 줄(사본 — 해시 대조 대상). */
    public boolean isRepoFile() {
        return platform.equals("any") && !ref.contains(":") && !sha256.equals("-");
    }

    /** 이미지 줄(digest 참조). */
    public boolean isImage() {
        return ref.contains("@sha256:");
    }
}
