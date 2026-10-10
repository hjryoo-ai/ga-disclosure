package com.ga.disclosure.app.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3(Phase 8 ③ — 주입 "이미지에 키 파일 포함"): 두 이미지의 모든 레이어의 모든 파일에 키 파일 이름·PEM 개인키 블록·로컬 비밀 바이트·허구 개인정보
 * 센티널(데모 고객 이름·전화 — 생년월일 {@code 1900-01-0x}은 런타임 자료와 우연히 겹칠 수 있어 바늘에서 뺐다)이 없다. 스캐너가 헛돌지 않는지 먼저 본다
 * (같은 베이스에 개인키 파일 하나를 더한 이미지는 걸려야 한다 — 6B D-5 교훈).
 */
class ImageLayerScanTest {

    /** 로컬에 이미 있는 비밀(데모 스크립트의 기본 디렉터리)과 데모 고객의 이름·전화. */
    static List<byte[]> needles() throws IOException {
        List<byte[]> out = new ArrayList<>();
        Path secrets = Path.of(System.getProperty("user.home"), ".ga-disclosure", "secrets");
        if (Files.isDirectory(secrets)) {
            try (Stream<Path> files = Files.walk(secrets)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    byte[] raw = Files.readAllBytes(f);
                    String text = new String(raw, StandardCharsets.US_ASCII).trim();
                    if (text.length() >= 16) {
                        out.add(text.getBytes(StandardCharsets.US_ASCII));
                    }
                    try {
                        byte[] decoded = Base64.getDecoder().decode(text);
                        if (decoded.length >= 16) {
                            out.add(decoded);
                        }
                    } catch (IllegalArgumentException notBase64) {
                        // PEM 등 — 위의 원문 바늘과 PEM 블록 검사가 본다
                    }
                }
            }
        }
        JsonNode customers = JsonMapper.builder().build().readTree(Files.readString(Docker.repoRoot().resolve("disclosure-demo/src/main/resources/customers.json")));
        for (JsonNode c : customers.get("customers")) {
            out.add(c.get("name").asString().getBytes(StandardCharsets.UTF_8));
            if (c.has("phone")) {
                out.add(c.get("phone").asString().getBytes(StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    @Test
    void theScannerFindsAPrivateKeyAddedOnTopOfTheWebImage(@TempDir Path dir) throws Exception {
        var pair = KeyPairGenerator.getInstance("EC").generateKeyPair();
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(pair.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(dir.resolve("leaked.pem"), pem);
        Files.writeString(dir.resolve("Dockerfile"), "FROM " + Docker.WEB + "\nCOPY leaked.pem /tmp/leaked.pem\n");
        String tag = "ga-disclosure/scan-control:dev";
        Docker.run("build", "--quiet", "-t", tag, dir.toString());
        try {
            LayerScanner.Result r = LayerScanner.scan(Docker.save(tag, dir), List.of());
            assertThat(r.hits()).extracting(LayerScanner.Hit::path, LayerScanner.Hit::why).contains(org.assertj.core.groups.Tuple.tuple("tmp/leaked.pem",
                    "PEM private key block"));
        } finally {
            Docker.run("rmi", "-f", tag);
        }
    }

    @Test
    void neitherImageCarriesKeysSecretsOrPersonalData(@TempDir Path dir) throws Exception {
        List<byte[]> needles = needles();
        assertThat(needles).as("at least the demo customers' names and phones").hasSizeGreaterThanOrEqualTo(5);
        for (String image : List.of(Docker.APP, Docker.WEB)) {
            LayerScanner.Result r = LayerScanner.scan(Docker.save(image, dir), needles);
            assertThat(r.layers()).as(image + " layers").isGreaterThan(1);
            assertThat(r.files()).as(image + " files").isGreaterThan(100);
            assertThat(r.hits()).as(image).isEmpty();
        }
    }
}
