package com.ga.disclosure.seal.evidence;

import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * 증거 패키지 검증기: 첫 엔트리가 {@code manifest.json}이고 스키마를 통과하며, {@code files}가 나머지 엔트리 전부를 경로 순으로 정확히 덮고
 * 해시·길이가 일치하는지 본다. 패키지를 받은 쪽(감독 제출·분쟁)이 저장소 없이 다시 확인하는 경로다.
 */
public final class EvidencePackageReader {

    private EvidencePackageReader() {
    }

    /** 엔트리 이름 → 바이트(ZIP 순서 유지). 손상된 엔트리(CRC 불일치 등)는 그 이름과 함께 {@link CorruptEntry}. */
    public static Map<String, byte[]> entries(byte[] zip) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                byte[] bytes;
                try {
                    bytes = in.readAllBytes();
                } catch (ZipException corrupt) {
                    throw new CorruptEntry(e.getName(), corrupt);
                }
                if (out.put(e.getName(), bytes) != null) {
                    throw new IllegalArgumentException("duplicate entry " + e.getName());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** 읽을 수 없는 엔트리. */
    public static final class CorruptEntry extends RuntimeException {
        private final String entry;

        CorruptEntry(String entry, ZipException cause) {
            super("corrupt entry " + entry + ": " + cause.getMessage(), cause);
            this.entry = entry;
        }

        public String entry() {
            return entry;
        }
    }

    /** 문제 목록(비어 있으면 통과). */
    public static List<String> verify(byte[] zip) {
        List<String> problems = new ArrayList<>();
        Map<String, byte[]> entries;
        try {
            entries = entries(zip);
        } catch (CorruptEntry e) {
            problems.add(e.getMessage());
            return problems;
        }
        List<String> names = new ArrayList<>(entries.keySet());
        if (names.isEmpty() || !names.getFirst().equals(EvidencePackageBuilder.MANIFEST)) {
            problems.add("first entry is not " + EvidencePackageBuilder.MANIFEST);
            return problems;
        }
        JsonNode manifest = EvidencePackageBuilder.parse(entries.get(EvidencePackageBuilder.MANIFEST));
        problems.addAll(EvidenceManifestSchema.validateManifest(manifest));
        List<String> listed = new ArrayList<>();
        for (JsonNode f : manifest.path("files")) {
            String path = f.path("path").asString();
            listed.add(path);
            byte[] bytes = entries.get(path);
            if (bytes == null) {
                problems.add("listed file missing: " + path);
            } else if (!EvidencePackageBuilder.sha256(bytes).equals(f.path("sha256").asString()) || bytes.length != f.path("bytes").asLong()) {
                problems.add("hash or length mismatch: " + path);
            }
        }
        List<String> rest = names.subList(1, names.size());
        if (!rest.equals(listed)) {
            problems.add("entries after the manifest must be exactly the listed files in path order: " + rest + " vs " + listed);
        }
        String signedPdf = manifest.at("/hashes/signedPdf").asString();
        byte[] signed = entries.get(EvidencePackageBuilder.SIGNED_PDF);
        if (signed == null || !EvidencePackageBuilder.sha256(signed).equals(signedPdf)) {
            problems.add("signed PDF hash mismatch");
        }
        return problems;
    }
}
