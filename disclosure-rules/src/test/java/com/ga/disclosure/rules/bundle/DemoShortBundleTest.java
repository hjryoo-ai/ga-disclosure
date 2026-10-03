package com.ga.disclosure.rules.bundle;

import com.ga.platform.canonical.Canonicalizer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 데모 전용 GLOBAL 번들 {@code DISC-DEMO-SHORT}(5 계획 §8.7, 승인 Q5): 규제 번들 디렉터리 밖(disclosure-demo)에 두고 짧은 보존 데모 테넌트에만 배포한다.
 * 형식·해시는 일반 번들과 같고, 본문은 데모 시계의 기준일에 시행되는 규제 번들(DISC-2026-07)과 보존 길이(0년 1일 — 스키마의 합계 ≥ 1일 하한)만 다르다.
 *
 * <p>5 수용심사 R2(표류 방지): "다른 최상위 키의 집합"이 정확히 보존 키 둘이다 — 한쪽에만 있는 키도, 보존 외에 값이 다른 키도 없고, 보존 키가
 * 규제값으로 돌아가도(차이가 사라져도) 실패한다. 규제 번들이 바뀌면 데모 번들도 같은 커밋에서 바뀌어야 한다.
 */
class DemoShortBundleTest {

    static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));
    static final Set<String> RETENTION = Set.of("retentionYears", "retentionDays");

    static Bundle load(Path p) throws IOException {
        return BundleLoader.parse(p.toString(), Files.readString(p));
    }

    static RuleBundle rule(String file) throws IOException {
        return (RuleBundle) load(ROOT.resolve("contracts/rules/bundles/rules").resolve(file));
    }

    /** 두 본문에서 한쪽에만 있거나 값(JCS 바이트)이 다른 최상위 키. */
    static Set<String> differingKeys(JsonNode a, JsonNode b) {
        Set<String> keys = new TreeSet<>();
        a.propertyNames().forEach(keys::add);
        b.propertyNames().forEach(keys::add);
        Set<String> out = new TreeSet<>();
        for (String k : keys) {
            if (!a.has(k) || !b.has(k) || !jcs(a.get(k)).equals(jcs(b.get(k)))) {
                out.add(k);
            }
        }
        return out;
    }

    /** 정규화기는 객체만 받으므로 값 하나를 {@code {"v": 값}}으로 싸서 비교한다. */
    static String jcs(JsonNode value) {
        ObjectNode wrapped = JsonNodeFactory.instance.objectNode();
        wrapped.set("v", value);
        return new String(Canonicalizer.canonicalize(wrapped), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void itDiffersFromTheRegulatoryBundleExactlyInTheRetentionKeys() throws IOException {
        RuleBundle demo = (RuleBundle) load(ROOT.resolve("disclosure-demo/src/main/resources/demo/bundles/rules/DISC-DEMO-SHORT.bundle.json"));
        RuleBundle regulatory = rule("DISC-2026-07.bundle.json");

        assertThat(demo.bundleId()).startsWith("DISC-DEMO-SHORT@");
        assertThat(demo.body().get("retentionYears").asInt()).isZero();
        assertThat(demo.body().get("retentionDays").asInt()).isEqualTo(1);
        assertThat(differingKeys(demo.body(), regulatory.body())).isEqualTo(RETENTION);
        assertThat(Files.exists(ROOT.resolve("contracts/rules/bundles/rules/DISC-DEMO-SHORT.bundle.json"))).as("규제 번들 디렉터리에 두지 않는다").isFalse();
    }

    /** 다음 규제 번들과의 차이 = 두 규제 번들 사이의 차이(보존 외) ∪ 보존 키 — 데모는 2026-07을 따라가고, 2027-01과의 차이는 거기서만 온다. */
    @Test
    void againstTheNextRegulatoryBundleOnlyTheRegulatoryChangesAndTheRetentionKeysDiffer() throws IOException {
        RuleBundle demo = (RuleBundle) load(ROOT.resolve("disclosure-demo/src/main/resources/demo/bundles/rules/DISC-DEMO-SHORT.bundle.json"));
        Set<String> regulatoryChange = new TreeSet<>(differingKeys(rule("DISC-2026-07.bundle.json").body(), rule("DISC-2027-01.bundle.json").body()));
        regulatoryChange.addAll(RETENTION);

        assertThat(differingKeys(demo.body(), rule("DISC-2027-01.bundle.json").body())).isEqualTo(regulatoryChange);
    }
}
