package com.ga.disclosure.rules.bundle;

import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 데모 전용 GLOBAL 번들 {@code DISC-DEMO-SHORT}(5 계획 §8.7, 승인 Q5): 규제 번들 디렉터리 밖(disclosure-demo)에 두고 짧은 보존 데모 테넌트에만 배포한다.
 * 형식·해시는 일반 번들과 같고, 본문은 DISC-2026-07과 보존 길이(0년 1일 — 스키마의 합계 ≥ 1일 하한)만 다르다.
 */
class DemoShortBundleTest {

    static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));

    static Bundle load(Path p) throws IOException {
        return BundleLoader.parse(p.toString(), Files.readString(p));
    }

    @Test
    void itDiffersFromTheRegulatoryBundleOnlyInTheRetentionLength() throws IOException {
        RuleBundle demo = (RuleBundle) load(ROOT.resolve("disclosure-demo/src/main/resources/demo/bundles/rules/DISC-DEMO-SHORT.bundle.json"));
        RuleBundle regulatory = (RuleBundle) load(ROOT.resolve("contracts/rules/bundles/rules/DISC-2026-07.bundle.json"));

        assertThat(demo.bundleId()).startsWith("DISC-DEMO-SHORT@");
        assertThat(demo.body().get("retentionYears").asInt()).isZero();
        assertThat(demo.body().get("retentionDays").asInt()).isEqualTo(1);
        ObjectNode a = (ObjectNode) demo.body().deepCopy();
        ObjectNode b = (ObjectNode) regulatory.body().deepCopy();
        for (ObjectNode n : new ObjectNode[]{a, b}) {
            n.remove("retentionYears");
            n.remove("retentionDays");
        }
        assertThat(Canonicalizer.canonicalize(a)).isEqualTo(Canonicalizer.canonicalize(b));
        assertThat(Files.exists(ROOT.resolve("contracts/rules/bundles/rules/DISC-DEMO-SHORT.bundle.json"))).as("규제 번들 디렉터리에 두지 않는다").isFalse();
    }
}
