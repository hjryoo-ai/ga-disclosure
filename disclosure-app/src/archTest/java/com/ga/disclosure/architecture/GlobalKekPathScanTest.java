package com.ga.disclosure.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 1b(8 계획 승인 Q2 — "이행 완료 후 전역 키 ID 읽기 경로를 제거, 남겨 두면 영원히 산다"): 전역 시절 KEK 경로(로컬 KEK 파일 어댑터·그 설정 키·
 * 그 CLI·그 환경변수·테넌트 없는 {@code currentKekId()})를 가리키는 문자열이 저장소의 코드·설정·스크립트 어디에도 없다. 문서(설계서·Phase 문서·README 등
 * Markdown)는 이력이라 대상이 아니다. 이행 시험 자체는 1a 커밋 {@code 270e18d}에 남아 있다.
 */
class GlobalKekPathScanTest {

    static final List<String> TOKENS = List.of("LocalFileKeyProvider", "local-kek-file", "init-kek", "GA_LOCAL_KEK_FILE", "currentKekId()");
    private static final Set<String> SKIP_DIRS = Set.of(".git", "build", "node_modules", ".gradle", "docs", ".idea", "test-results", "playwright-report");
    private static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));
    private static final String SELF = "GlobalKekPathScanTest.java";

    @Test
    void noCodeConfigOrScriptReachesTheGlobalEraKek() {
        assertThat(hits()).as("전역 시절 KEK 경로 참조(1b에서 지웠다)").isEmpty();
    }

    /** 스캔 자체가 일하는지: 문자열을 담은 임시 파일은 잡힌다(공회전 방지). */
    @Test
    void theScanFindsAPlantedReference() throws IOException {
        Path planted = Files.createTempFile("planted", ".yaml");
        Files.writeString(planted, "ga:\n  crypto:\n    local-kek-file: x\n");
        assertThat(scan(planted)).containsExactly(planted.getFileName() + ": local-kek-file");
    }

    static List<String> hits() {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> ROOT.relativize(p).getNameCount() > 0 && !skipped(ROOT.relativize(p)))
                    .filter(p -> !p.getFileName().toString().endsWith(".md") && !p.getFileName().toString().equals(SELF))
                    .filter(p -> p.getFileName().toString().matches(".*\\.(java|kts|ya?ml|properties|sh|mjs|ts|tsx|json|sql|toml|conf|Dockerfile)$")
                            || p.getFileName().toString().equals("Dockerfile"))
                    .forEach(p -> out.addAll(scan(p).stream().map(h -> ROOT.relativize(p.getParent()) + "/" + h).toList()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static boolean skipped(Path relative) {
        for (Path part : relative) {
            if (SKIP_DIRS.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    static List<String> scan(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return TOKENS.stream().filter(text::contains).map(t -> file.getFileName() + ": " + t).toList();
    }
}
