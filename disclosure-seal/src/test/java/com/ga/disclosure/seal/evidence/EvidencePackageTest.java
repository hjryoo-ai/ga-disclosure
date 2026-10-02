package com.ga.disclosure.seal.evidence;

import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** G8: 증거 패키지 — 스키마 통과, 엔트리 해시 전부 일치, 2회 바이트 동일, 스트로크·이미지 원본 미포함, 입력 정합 검사. */
class EvidencePackageTest {

    @Test
    void manifestFirstThenPathOrderAndEveryHashMatches() {
        EvidencePackage p = EvidencePackageBuilder.build(EvidenceFixtures.input());
        assertThat(EvidencePackageReader.verify(p.zip())).isEmpty();
        Map<String, byte[]> entries = EvidencePackageReader.entries(p.zip());
        assertThat(entries.keySet()).containsExactly("manifest.json", "audit.jsonl", "canonical.json", "disclosure-signed.pdf", "disclosure.pdf",
                "signatures/1-CUSTOMER.json", "signatures/2-MANAGER.json");
        assertThat(EvidencePackageBuilder.sha256(entries.get("manifest.json"))).isEqualTo(p.manifestSha256());
        assertThat(EvidencePackageBuilder.sha256(p.zip())).isEqualTo(p.sha256());
        JsonNode manifest = Canonicalizer.parseStrict(new String(entries.get("manifest.json"), StandardCharsets.UTF_8));
        assertThat(entries.get("manifest.json")).as("manifest bytes are JCS").isEqualTo(Canonicalizer.canonicalize(manifest));
        assertThat(manifest.at("/hashes/canonical").asString()).isEqualTo(EvidencePackageBuilder.sha256(EvidenceFixtures.CANONICAL));
        assertThat(manifest.at("/audit/fromSeq").asLong()).isEqualTo(101);
        assertThat(manifest.at("/audit/toSeq").asLong()).isEqualTo(140);
        assertThat(manifest.path("anchor").isNull()).isTrue();
        assertThat(new String(entries.get("audit.jsonl"), StandardCharsets.UTF_8).lines()).hasSize(2);
    }

    @Test
    void sameInputSameBytesStoredEntriesAtAFixedTime() throws Exception {
        byte[] a = EvidencePackageBuilder.build(EvidenceFixtures.input()).zip();
        byte[] b = EvidencePackageBuilder.build(EvidenceFixtures.input()).zip();
        assertThat(b).isEqualTo(a);
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(a))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                assertThat(e.getMethod()).as(e.getName()).isEqualTo(ZipEntry.STORED);
                assertThat(e.getTimeLocal()).as(e.getName()).isEqualTo(LocalDateTime.of(1980, 1, 1, 0, 0, 2));
                assertThat(e.getExtra()).as(e.getName()).isNull();
                assertThat(e.getComment()).as(e.getName()).isNull();
            }
        }
    }

    @Test
    void signatureImageAndStrokesAreHashesOnly() {
        byte[] zip = EvidencePackageBuilder.build(EvidenceFixtures.input()).zip();
        assertThat(contains(zip, EvidenceFixtures.IMAGE_SENTINEL)).as("raw image bytes are not packaged").isFalse();
        JsonNode customer = Canonicalizer.parseStrict(new String(EvidencePackageReader.entries(zip).get("signatures/1-CUSTOMER.json"),
                StandardCharsets.UTF_8));
        assertThat(customer.has("evidence")).isFalse();
        assertThat(customer.path("identityCheck").get(0).propertyNames()).containsExactlyInAnyOrder("type", "result", "at");
        assertThat(EvidenceManifestSchema.validateSignatureFile(customer)).isEmpty();
    }

    @Test
    void tamperingWithAnyEntryIsDetected() {
        byte[] zip = EvidencePackageBuilder.build(EvidenceFixtures.input()).zip();
        int at = indexOf(zip, EvidenceFixtures.CANONICAL);
        zip[at + 2] ^= 0x01;                                            // STORED이라 원문 바이트가 그대로 있다
        assertThat(EvidencePackageReader.verify(zip)).anyMatch(p -> p.contains("canonical.json"));
        // CRC를 우회해도(엔트리 CRC까지 맞춘 위조) 매니페스트 해시가 잡는다
        byte[] forged = EvidencePackageBuilder.build(EvidenceFixtures.input()).zip();
        Map<String, byte[]> entries = EvidencePackageReader.entries(forged);
        entries.put("canonical.json", "{\"canonicalVersion\":1,\"tenantId\":\"EVIL1\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(EvidencePackageReader.verify(rezip(entries))).anyMatch(p -> p.contains("canonical.json"));
    }

    @Test
    void inputsMustBeConsistent() {
        String doc = EvidencePackageBuilder.sha256(EvidenceFixtures.CANONICAL);
        String pdf = EvidencePackageBuilder.sha256(EvidenceFixtures.PDF);
        assertThatThrownBy(() -> EvidencePackageBuilder.build(EvidenceFixtures.input(
                List.of(EvidenceFixtures.customer(EvidenceFixtures.hash('d'), pdf)), EvidenceFixtures.audit(), EvidenceFixtures.signedPdf())))
                .hasMessageContaining("not bound");
        assertThatThrownBy(() -> EvidencePackageBuilder.build(EvidenceFixtures.input(
                List.of(EvidenceFixtures.customer(doc, EvidenceFixtures.hash('d'))), EvidenceFixtures.audit(), EvidenceFixtures.signedPdf())))
                .hasMessageContaining("not bound");
        assertThatThrownBy(() -> EvidencePackageBuilder.build(EvidenceFixtures.input(
                List.of(EvidenceFixtures.customer(doc, pdf)), EvidenceFixtures.audit(), "regenerated".getBytes(StandardCharsets.US_ASCII))))
                .hasMessageContaining("prefix");
        List<EvidenceInput.AuditRow> reversed = new ArrayList<>(EvidenceFixtures.audit()).reversed();
        assertThatThrownBy(() -> EvidencePackageBuilder.build(EvidenceFixtures.input(
                List.of(EvidenceFixtures.customer(doc, pdf)), reversed, EvidenceFixtures.signedPdf())))
                .hasMessageContaining("increasing");
        assertThatThrownBy(() -> EvidencePackageBuilder.build(EvidenceFixtures.input(List.of(), EvidenceFixtures.audit(), EvidenceFixtures.signedPdf())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] rezip(Map<String, byte[]> entries) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        return indexOf(haystack, needle) >= 0;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
