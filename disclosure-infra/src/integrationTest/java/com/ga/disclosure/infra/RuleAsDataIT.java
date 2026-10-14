package com.ga.disclosure.infra;

import com.ga.disclosure.domain.enums.ManagerConfirmMode;
import com.ga.disclosure.domain.enums.SignerRole;
import com.ga.disclosure.domain.enums.TemplateType;
import com.ga.disclosure.domain.enums.ValidationStage;
import com.ga.disclosure.rules.bundle.Bundle;
import com.ga.disclosure.rules.resolve.EffectiveRule;
import com.ga.disclosure.rules.template.FieldScope;
import com.ga.disclosure.rules.template.TemplateResolution;
import com.ga.disclosure.rules.testing.Bundles;
import com.ga.disclosure.rules.testing.TestItem;
import com.ga.disclosure.rules.testing.TestSubject;
import com.ga.disclosure.rules.validation.ValidationRegistry;
import com.ga.disclosure.rules.validation.ValidationResult;
import com.ga.disclosure.rules.validation.standard.StandardValidations;
import com.ga.platform.core.tenant.TenantId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 C1 — 룰은 코드가 아니라 데이터다(설계서 대전제 2). 세 시나리오 모두 <b>같은 빌드 산출물</b>(같은 JVM, 같은 검증
 * 레지스트리 인스턴스)로 두 데이터셋을 실행하며, 두 데이터셋의 차이는 번들 본문의 JSON 포인터 몇 개뿐임을 함께 단언한다.
 * <ol>
 *   <li>(a) DISC-2027-01 배포 후 상담일 2026-12-31은 minCompare=3·관리자 REQUIRED, 2027-01-01은 4·OFF(부록 A-4).</li>
 *   <li>(b) STANDARD-v1에 required 필드 1개를 더한 v2(테스트 픽스처) 배포 후 같은 입력이 R-FIELD-REQUIRED 실패로 바뀐다.</li>
 *   <li>(c) reasonCodes에 코드 1개를 더한 룰(테스트 픽스처) 배포 후 그 코드로 R-REASON이 통과한다.</li>
 * </ol>
 */
class RuleAsDataIT {

    private static final LocalDate EVE = LocalDate.parse("2026-12-31");
    private static final LocalDate NEW_YEAR = LocalDate.parse("2027-01-01");
    private static final Instant SEALED = Instant.parse("2026-12-31T01:00:00Z");
    private static final ValidationRegistry REGISTRY = StandardValidations.registry();

    private final Governance g = new Governance();

    // ------------------------------------------------------------------ 공통

    private TenantId tenantWith(Bundle... bundles) {
        TenantId t = g.freshTenant("RAD");
        for (Bundle b : bundles) {
            g.distribution.distribute(b, t, Governance.OPERATOR);
        }
        for (String day : List.of("2026-09-23", "2027-01-01")) {
            new Governance(LocalDate.parse(day).atStartOfDay(Governance.SEOUL).toInstant().toString()).activation.run(t, Governance.OPERATOR);
        }
        return t;
    }

    private record Evaluation(EffectiveRule rule, TemplateResolution template, Map<String, ValidationResult> results) {
    }

    private Evaluation evaluate(TenantId t, LocalDate consultDate, List<TestItem> items, List<SignerRole> signers) {
        return g.in(t, () -> {
            EffectiveRule rule = g.resolver.resolve(t, consultDate);
            TemplateResolution template = g.templateResolver.resolve(t, TemplateType.STANDARD, consultDate);
            TestSubject subject = TestSubject.of("PG-HEALTH", consultDate, items).withLargeGa(false)
                    .withDeadline(SEALED.plusSeconds(30L * 86_400));
            for (int i = 0; i < signers.size(); i++) {
                subject = subject.signedBy(signers.get(i), SEALED.plusSeconds(60L * (i + 1)));
            }
            // 봉인 단계(완료 단계 규칙을 뺀 전부)와 완료 단계를 합치면 룰의 모든 규칙이다(단계는 룰 데이터, Phase 2 선행 A).
            Map<String, ValidationResult> results = new HashMap<>();
            for (ValidationStage stage : List.of(ValidationStage.SEAL, ValidationStage.COMPLETE)) {
                REGISTRY.run(stage, subject, rule, template).forEach(r -> results.put(r.ruleId(), r));
            }
            return new Evaluation(rule, template, results);
        });
    }

    /** 두 JSON의 차이 지점(JSON Pointer). 배열은 인덱스별로 비교한다. */
    static Set<String> diff(JsonNode a, JsonNode b) {
        Set<String> out = new TreeSet<>();
        diff("", a, b, out);
        return out;
    }

    private static void diff(String at, JsonNode a, JsonNode b, Set<String> out) {
        if (a != null && b != null && a.isObject() && b.isObject()) {
            Set<String> keys = new TreeSet<>(a.propertyNames());
            keys.addAll(b.propertyNames());
            keys.forEach(k -> diff(at + "/" + k, a.get(k), b.get(k), out));
        } else if (a != null && b != null && a.isArray() && b.isArray()) {
            for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
                diff(at + "/" + i, a.get(i), b.get(i), out);
            }
        } else if (a == null || !a.equals(b)) {
            out.add(at);
        }
    }

    private static TestItem item(String insurer, String product) {
        return TestItem.of(insurer, product, "PG-HEALTH");
    }

    /**
     * 시험 번들은 계약 밖에 산다. Phase 8부터 계약에 실제 STANDARD v2(환급금 표 열 — 렌더러 판 2)가 있으므로, 파일 이름이 아니라 <b>내용</b>으로 본다:
     * 시험 번들(rule-as-data 전부)의 번들 ID가 계약 번들 어디에도 없다 — 이름을 바꾼 사본도 잡는다.
     */
    @Test
    void fixturesLiveOutsideProductionSourcesAndContracts() throws java.io.IOException {
        Path root = Path.of(System.getProperty("ga.repoRoot"));
        Path fixtures = root.resolve("disclosure-infra/src/integrationTest/resources/rule-as-data");
        assertThat(fixtures.resolve("templates/STANDARD-v2.bundle.json")).isRegularFile();
        java.util.Set<String> fixtureIds = bundleIds(fixtures);
        java.util.Set<String> contractIds = bundleIds(root.resolve("contracts/rules/bundles"));
        assertThat(fixtureIds).hasSizeGreaterThanOrEqualTo(3).doesNotContainAnyElementsOf(contractIds);
        assertThat(contractIds).contains(Bundles.load(Bundles.STANDARD_V1).bundleId()).noneMatch(id -> id.startsWith("DISC-TEST-"));
    }

    private static java.util.Set<String> bundleIds(Path dir) throws java.io.IOException {
        try (var files = Files.walk(dir)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".bundle.json"))
                    .map(f -> {
                        try {
                            return com.ga.platform.canonical.Canonicalizer.parseStrict(Files.readString(f)).get("bundleId").asString();
                        } catch (java.io.IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    // ------------------------------------------------------------------ (a) 최소 비교 개수·관리자 확인 모드

    @Test
    void scenarioA_regulationChangeOnTheBoundaryDay() {
        Bundle y2026 = Bundles.load(Bundles.DISC_2026_07);
        Bundle y2027 = Bundles.load(Bundles.DISC_2027_01);
        assertThat(diff(y2026.body(), y2027.body()))
                .as("the two datasets differ only in data")
                .containsExactly("/gateRequiresManager", "/managerConfirmMode", "/minCompare", "/signerSet/2");

        TenantId t = tenantWith(y2026, y2027, Bundles.load(Bundles.STANDARD_V1));
        List<TestItem> threeInsurers = List.of(item("INS-A", "P1"), item("INS-B", "P2"), item("INS-C", "P3"));
        List<SignerRole> customerAndAgent = List.of(SignerRole.CUSTOMER, SignerRole.AGENT);

        Evaluation eve = evaluate(t, EVE, threeInsurers, customerAndAgent);
        assertThat(eve.rule().globalRuleVersionId().value()).isEqualTo("DISC-2026-07");
        assertThat(eve.rule().minCompare()).isEqualTo(3);
        assertThat(eve.rule().managerConfirmMode()).isEqualTo(ManagerConfirmMode.REQUIRED);
        assertThat(eve.results().get("R-MIN-COMPARE").passed()).isTrue();
        assertThat(eve.results().get("R-SIGNER-SET").passed()).as("manager still required").isFalse();

        Evaluation newYear = evaluate(t, NEW_YEAR, threeInsurers, customerAndAgent);
        assertThat(newYear.rule().globalRuleVersionId().value()).isEqualTo("DISC-2027-01");
        assertThat(newYear.rule().minCompare()).isEqualTo(4);
        assertThat(newYear.rule().managerConfirmMode()).isEqualTo(ManagerConfirmMode.OFF);
        assertThat(newYear.results().get("R-MIN-COMPARE").passed()).as("3 < 4").isFalse();
        assertThat(newYear.results().get("R-SIGNER-SET").passed()).as("manager no longer in the signer set").isTrue();
    }

    // ------------------------------------------------------------------ (b) 서식 항목 추가

    @Test
    void scenarioB_templateGainsARequiredField() {
        Bundle v1 = Bundles.load(Bundles.STANDARD_V1);
        Bundle v2 = BundleFiles.fixture("templates/STANDARD-v2.bundle.json");
        // 데이터 변경 = 항목 1개 추가 + 그 항목의 배치(3B: 모든 항목은 배치 섹션 하나에 있어야 한다 — TemplateLayoutCheck). 식별부 4개가 앞에 있다.
        assertThat(diff(v1.body(), v2.body())).containsExactly("/fields/13", "/layout/sections/2/fields/7");

        TenantId t = tenantWith(Bundles.load(Bundles.DISC_2026_07), v1, v2);
        Evaluation before = evaluate(t, EVE, List.of(), List.of());
        Map<String, String> v1Values = new HashMap<>();
        before.template().requiredFields().stream().filter(f -> f.scope() == FieldScope.PER_ITEM).forEach(f -> v1Values.put(f.code(), "값"));
        List<TestItem> items = List.of(item("INS-A", "P1").fields(v1Values), item("INS-B", "P2").fields(v1Values),
                item("INS-C", "P3").fields(v1Values));

        Evaluation eve = evaluate(t, EVE, items, List.of());
        Evaluation newYear = evaluate(t, NEW_YEAR, items, List.of());
        assertThat(eve.template().ref().version()).isEqualTo(1);
        assertThat(newYear.template().ref().version()).isEqualTo(2);
        ValidationResult passed = eve.results().get("R-FIELD-REQUIRED");
        ValidationResult failed = newYear.results().get("R-FIELD-REQUIRED");
        // 문서 단위 필수 항목 값은 두 경우 모두 없으므로 메시지 비교는 추가 항목에 집중한다.
        assertThat(passed.message()).doesNotContain("TEST_ONLY_FIELD");
        assertThat(failed.passed()).isFalse();
        assertThat(failed.message()).contains("TEST_ONLY_FIELD@INS-A:P1", "TEST_ONLY_FIELD@INS-B:P2", "TEST_ONLY_FIELD@INS-C:P3");
    }

    // ------------------------------------------------------------------ (c) 사유 코드 추가

    @Test
    void scenarioC_reasonCodeIsAddedByData() {
        Bundle y2026 = Bundles.load(Bundles.DISC_2026_07);
        Bundle withCode = BundleFiles.fixture("rules/DISC-TEST-REASON.bundle.json");
        assertThat(diff(y2026.body(), withCode.body())).containsExactly("/reasonCodes/5");

        TenantId t = tenantWith(y2026, withCode, Bundles.load(Bundles.STANDARD_V1));
        List<TestItem> items = List.of(item("INS-A", "P1").recommended("TEST_ONLY_REASON"), item("INS-B", "P2"), item("INS-C", "P3"));

        Evaluation eve = evaluate(t, EVE, items, List.of());
        Evaluation newYear = evaluate(t, NEW_YEAR, items, List.of());
        assertThat(eve.results().get("R-REASON").passed()).isFalse();
        assertThat(eve.results().get("R-REASON").message()).contains("룰에 없는 사유 코드 [TEST_ONLY_REASON]");
        assertThat(newYear.rule().globalRuleVersionId().value()).isEqualTo("DISC-TEST-REASON");
        assertThat(newYear.results().get("R-REASON").passed()).isTrue();
    }
}
