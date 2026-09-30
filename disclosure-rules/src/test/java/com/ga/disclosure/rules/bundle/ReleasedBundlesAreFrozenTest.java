package com.ga.disclosure.rules.bundle;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 번들 형식 변경은 첫 운영 배포 전에만 제자리 수정할 수 있다(설계서 §5, Phase 1 수용 심사 §3-1). 운영 배포된 번들은
 * {@code contracts/rules/released-bundles.txt}에 적고, 적힌 번들의 파일이 사라지거나 본문 해시가 바뀌면 실패한다.
 */
class ReleasedBundlesAreFrozenTest {

    private static final Path CONTRACTS = Path.of(System.getProperty("ga.repoRoot"), "contracts");

    /** 목록 줄 {@code <bundleId> <sha256>}와 현재 번들 파일을 대조해 위반을 모은다. */
    static List<String> violations(List<String> releasedLines, Map<String, String> currentHashById) {
        List<String> out = new ArrayList<>();
        for (String raw : releasedLines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length != 2 || !parts[1].matches("[0-9a-f]{64}")) {
                out.add("malformed line: " + line);
                continue;
            }
            String current = currentHashById.get(parts[0]);
            if (current == null) {
                out.add(parts[0] + " was released but no bundle file carries that id any more (edited in place?)");
            } else if (!current.equals(parts[1])) {
                out.add(parts[0] + " body hash changed: released " + parts[1] + ", now " + current);
            }
        }
        return out;
    }

    private static Map<String, String> currentBundles() {
        Map<String, String> byId = new HashMap<>();
        try (Stream<Path> files = Files.walk(CONTRACTS.resolve("rules/bundles"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".bundle.json")).toList()) {
                Bundle b = BundleLoader.parse(f.toString(), Files.readString(f));
                byId.put(b.bundleId(), b.bodyHash());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return byId;
    }

    @Test
    void releasedBundlesAreUnchanged() throws IOException {
        List<String> released = Files.readAllLines(CONTRACTS.resolve("rules/released-bundles.txt"));
        assertThat(violations(released, currentBundles())).isEmpty();
    }

    /** 검사기 대조: 목록에 적힌 번들을 제자리 수정한 경우(ID가 바뀜)와 해시가 다른 경우를 잡는다. */
    @Test
    void theCheckCatchesInPlaceEdits() {
        Map<String, String> current = currentBundles();
        String someId = current.keySet().iterator().next();
        String prefix = someId.substring(0, someId.indexOf('@'));
        assertThat(violations(List.of(prefix + "@000000000000 " + "0".repeat(64)), current)).singleElement().asString()
                .contains("no bundle file carries that id");
        assertThat(violations(List.of(someId + " " + "0".repeat(64)), current)).singleElement().asString().contains("body hash changed");
        assertThat(violations(List.of(someId + " " + current.get(someId)), current)).isEmpty();
        assertThat(violations(List.of("# comment", "", "garbage"), current)).containsExactly("malformed line: garbage");
    }
}
