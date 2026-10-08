package com.ga.disclosure.rules.resolve;

import com.ga.disclosure.domain.vo.RuleVersionId;
import com.ga.disclosure.rules.testing.Bundles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDate;
import java.util.OptionalInt;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 6B 룰 키(6B 계획 §5·§7, 승인 §3): 준법 큐 정책·징구율 산식·초안 폐기·미매칭 보관·게이트 한도가 번들 값 그대로 읽히고, 빠지거나 형식이 틀리면
 * 기본값 없이 예외다. "없음"은 키가 있고 값이 null일 때뿐이다(키 누락과 다르다).
 */
class EffectiveRuleComplianceKeysTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate D = LocalDate.parse("2026-10-09");

    private static EffectiveRule rule(Consumer<ObjectNode> edit) {
        ObjectNode body = (ObjectNode) Bundles.rule(Bundles.DISC_2026_07).body().deepCopy();
        edit.accept(body);
        return EffectiveRule.of(D, RuleVersionId.of("DISC-2026-07"), null, body);
    }

    private static final EffectiveRule BUNDLE = rule(b -> { });

    @Test
    void bundleValuesAreReadAsData() {
        assertThat(BUNDLE.complianceQueue()).hasSize(11);
        assertThat(BUNDLE.complianceQueue().values()).noneMatch(FlagTypePolicy::visibleToAgent)
                .allMatch(p -> p.slaHours().isEmpty());
        FlagTypePolicy chain = BUNDLE.flagPolicy("CHAIN_BROKEN");
        assertThat(chain.assignedRole()).isEqualTo(FlagAssignee.COMPLIANCE);
        assertThat(chain.requiresEvidence()).isTrue();
        assertThat(chain.allowsResolution("VERIFIED_MATCH")).isTrue();
        assertThat(chain.allowsResolution("FALSE_POSITIVE")).isFalse();
        FlagTypePolicy scan = BUNDLE.flagPolicy("PAPER_SCAN_REVIEW");
        assertThat(scan.assignedRole()).isEqualTo(FlagAssignee.MANAGER);
        assertThat(scan.manuallyResolvable()).isFalse();
        assertThat(BUNDLE.complianceQueue().entrySet()).filteredOn(e -> !e.getValue().manuallyResolvable()).extracting(e -> e.getKey())
                .containsExactly("VALIDATION_OVERRIDE", "RULE_SUPERSEDED_DRAFT", "PAPER_SCAN_REVIEW");
        assertThat(BUNDLE.collectionRateFormula()).isEqualTo(CollectionRateFormula.LINKED_COMPLETED_BY_CONTRACT_DATE);
        assertThat(BUNDLE.draftAbandonAfterDays()).isEqualTo(OptionalInt.empty());
        assertThat(BUNDLE.draftAbandonReasons()).extracting(LifecycleReasonRule::code).containsExactly("CUSTOMER_DECLINED", "DUPLICATE", "ENTRY_ERROR");
        assertThat(BUNDLE.contractLinkUnmatchedRetentionDays()).isEqualTo(OptionalInt.empty());
        assertThat(BUNDLE.gatePerMinutePerPrincipal()).isEqualTo(600);
    }

    @Test
    void nullMeansNoneButANumberIsRead() {
        EffectiveRule set = rule(b -> {
            ((ObjectNode) b.get("draft")).put("abandonAfterDays", 30);
            ((ObjectNode) b.get("contractLink")).put("unmatchedRetentionDays", 90);
            ((ObjectNode) b.at("/complianceQueue/types/SIGN_EXPIRED")).put("slaHours", 48);
        });
        assertThat(set.draftAbandonAfterDays()).isEqualTo(OptionalInt.of(30));
        assertThat(set.contractLinkUnmatchedRetentionDays()).isEqualTo(OptionalInt.of(90));
        assertThat(set.flagPolicy("SIGN_EXPIRED").slaHours()).isEqualTo(OptionalInt.of(48));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/draft/abandonAfterDays", "/contractLink/unmatchedRetentionDays", "/gate/perMinutePerPrincipal", "/collectionRate/formula",
            "/complianceQueue/types/CHAIN_BROKEN/slaHours", "/complianceQueue/types/CHAIN_BROKEN/assignedRole",
            "/complianceQueue/types/CHAIN_BROKEN/visibleToAgent", "/complianceQueue/types/CHAIN_BROKEN/resolutionCodes",
            "/complianceQueue/types/CHAIN_BROKEN/requiresEvidence"})
    void aMissingKeyHasNoDefault(String pointer) {
        int slash = pointer.lastIndexOf('/');
        EffectiveRule broken = rule(b -> ((ObjectNode) b.at(pointer.substring(0, slash))).remove(pointer.substring(slash + 1)));
        assertThatThrownBy(() -> read(broken, pointer)).isInstanceOf(MissingRuleKeyException.class);
    }

    @Test
    void wrongShapesAreRejected() {
        assertThatThrownBy(() -> rule(b -> ((ObjectNode) b.get("collectionRate")).put("formula", "COMPLETED_LINKED / SUBJECT")).collectionRateFormula())
                .isInstanceOf(MissingRuleKeyException.class);
        assertThatThrownBy(() -> rule(b -> ((ObjectNode) b.get("draft")).put("abandonAfterDays", 0)).draftAbandonAfterDays())
                .isInstanceOf(MissingRuleKeyException.class);
        assertThatThrownBy(() -> rule(b -> ((ObjectNode) b.get("draft")).put("abandonAfterDays", "30")).draftAbandonAfterDays())
                .isInstanceOf(MissingRuleKeyException.class);
        assertThatThrownBy(() -> rule(b -> ((ObjectNode) b.at("/complianceQueue/types/CHAIN_BROKEN")).put("assignedRole", "AGENT"))
                .flagPolicy("CHAIN_BROKEN")).isInstanceOf(MissingRuleKeyException.class);
        // 근거가 필요한 유형은 수동 해소 코드가 있어야 한다
        assertThatThrownBy(() -> rule(b -> ((ObjectNode) b.at("/complianceQueue/types/CHAIN_BROKEN")).set("resolutionCodes", JSON.createArrayNode()))
                .flagPolicy("CHAIN_BROKEN")).isInstanceOf(MissingRuleKeyException.class);
        assertThatThrownBy(() -> BUNDLE.flagPolicy("MISSING")).isInstanceOf(MissingRuleKeyException.class);
        assertThatThrownBy(() -> rule(b -> ((ObjectNode) b.get("draft")).set("abandonReasons", JSON.createArrayNode())).draftAbandonReasons())
                .isInstanceOf(MissingRuleKeyException.class);
    }

    private static Object read(EffectiveRule r, String pointer) {
        return switch (pointer.split("/")[1]) {
            case "draft" -> r.draftAbandonAfterDays();
            case "contractLink" -> r.contractLinkUnmatchedRetentionDays();
            case "gate" -> r.gatePerMinutePerPrincipal();
            case "collectionRate" -> r.collectionRateFormula();
            case "complianceQueue" -> r.flagPolicy("CHAIN_BROKEN");
            default -> throw new IllegalArgumentException(pointer);
        };
    }
}
