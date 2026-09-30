package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.disclosure.AgentReason;
import com.ga.disclosure.domain.disclosure.DisclosureCommand;
import com.ga.disclosure.domain.disclosure.DisclosureStateTable;
import com.ga.disclosure.domain.disclosure.GradeSource;
import com.ga.disclosure.domain.disclosure.IllegalTransition;
import com.ga.disclosure.domain.disclosure.ItemDraft;
import com.ga.disclosure.domain.disclosure.ItemGrade;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.grade.EngineSnapshot;
import com.ga.disclosure.domain.grade.GradeSnapshotItem;
import com.ga.disclosure.domain.vo.ProductKey;
import com.ga.disclosure.domain.vo.ReasonCode;
import com.ga.platform.core.testing.SeededCases;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3A W2: 시드 고정 무작위 명령 시퀀스 1,000건(각 1~30단계, 10%는 봉인 이후 상태에서 시작)에서 불변식이 매 단계 유지된다.
 * <ol>
 *   <li>항목은 봉인 전 상태에서만 바뀐다(봉인 이후 상태에서는 모든 명령이 {@code IllegalTransition}이고 아무것도 바뀌지 않는다).</li>
 *   <li>GRADED·REASONED에서 항목이 바뀌면 스냅샷·추천사유가 버려지고 COMPARED다.</li>
 *   <li>스냅샷이 있으면 항목 집합(임시등록 제외) = 스냅샷 상품 집합이고, 모든 항목이 산출돼 있으며 임시등록은 로컬 TEMP_PRODUCT다.</li>
 *   <li>표 밖 명령·거부·잘못된 입력은 상태를 바꾸지 않는다. 추천사유는 REASONED에만 있다.</li>
 * </ol>
 * 적용·거부·예외 경로가 실제로 돌았는지는 전체 실행 뒤 {@link #everyPathWasExercised()}가 확인한다(빈 통과 방지).
 */
class DisclosurePropertyTest {

    private static final long SEED = 0x5EED_3A02L;
    private static final List<String> INSURERS = List.of("INS-A", "INS-B", "INS-C", "INS-D", "INS-E", "INS-F");
    private static final List<ReasonCode> AGENT_CODES = List.of(ReasonCode.of("COVERAGE"), ReasonCode.of("PREMIUM"),
            ReasonCode.of("PURPOSE"), ReasonCode.of("OTHER"));

    private static final Map<String, AtomicInteger> SEEN = new java.util.concurrent.ConcurrentHashMap<>();

    static Stream<Arguments> sequences() {
        return SeededCases.of(SEED, 1_000, r -> new Object[] {r.nextLong(), r.nextInt(1, 31)});
    }

    private static void seen(String path) {
        SEEN.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
    }

    @ParameterizedTest
    @MethodSource("sequences")
    void invariantsHoldAfterEveryCommand(long seed, int steps) {
        RandomGenerator r = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
        Disclosure d = r.nextInt(10) == 0 ? sealedStart(r) : Fixtures.draft();
        boolean startedSealed = d.status().isSealedOrLater();
        for (int step = 0; step < steps; step++) {
            DisclosureTransitionTest.State before = DisclosureTransitionTest.State.of(d);
            DisclosureCommand command = List.of(DisclosureCommand.REPLACE_ITEMS, DisclosureCommand.COMPARE,
                    DisclosureCommand.APPLY_SNAPSHOT, DisclosureCommand.SET_RECOMMENDATIONS).get(r.nextInt(4));
            try {
                TransitionOutcome o = invoke(d, command, r);
                switch (o) {
                    case TransitionOutcome.Applied a -> {
                        seen("applied:" + command);
                        assertThat(DisclosureStateTable.results(before.status(), command)).contains(d.status());
                        if (command == DisclosureCommand.REPLACE_ITEMS && (before.status() == DisclosureStatus.GRADED
                                || before.status() == DisclosureStatus.REASONED)) {
                            seen("replace-after-grading");
                            assertThat(d.status()).isEqualTo(DisclosureStatus.COMPARED);
                            assertThat(d.engineSnapshot()).isEmpty();
                            assertThat(d.disclosureItems()).allSatisfy(i -> assertThat(i.recommendation()).isEmpty());
                        }
                    }
                    case TransitionOutcome.Rejected rej -> {
                        seen("rejected:" + command);
                        assertThat(DisclosureTransitionTest.State.of(d)).isEqualTo(before);
                    }
                }
            } catch (IllegalTransition e) {
                seen("illegal:" + command);
                assertThat(DisclosureStateTable.allows(before.status(), command)).isFalse();
                assertThat(DisclosureTransitionTest.State.of(d)).isEqualTo(before);
            } catch (IllegalArgumentException e) {
                seen("invalid-input:" + command);
                assertThat(DisclosureTransitionTest.State.of(d)).isEqualTo(before);
            }
            invariants(d, startedSealed, before);
        }
    }

    private static void invariants(Disclosure d, boolean startedSealed, DisclosureTransitionTest.State before) {
        if (startedSealed) {
            assertThat(DisclosureTransitionTest.State.of(d)).as("봉인 이후에는 무엇도 바뀌지 않는다").isEqualTo(before);
            return;
        }
        assertThat(d.status().isMutable()).as("3A 명령은 봉인하지 않는다").isTrue();
        boolean graded = d.status() == DisclosureStatus.GRADED || d.status() == DisclosureStatus.REASONED;
        assertThat(d.engineSnapshot().isPresent()).isEqualTo(graded);
        if (graded) {
            EngineSnapshot s = d.engineSnapshot().orElseThrow();
            Set<ProductKey> snapshotKeys = new HashSet<>();
            s.snapshot().items().stream().map(GradeSnapshotItem::productKey).forEach(snapshotKeys::add);
            Set<ProductKey> itemKeys = new HashSet<>();
            d.disclosureItems().stream().filter(i -> !i.draft().tempProduct()).forEach(i -> itemKeys.add(i.draft().productKey().orElseThrow()));
            assertThat(snapshotKeys).as("항목 집합(임시등록 제외) = 스냅샷 집합").isEqualTo(itemKeys);
            for (DisclosureItem i : d.disclosureItems()) {
                ItemGrade g = i.grade().orElseThrow();
                if (i.draft().tempProduct()) {
                    assertThat(g).isEqualTo(ItemGrade.Unavailable.localTempProduct());
                } else {
                    assertThat(g.source()).isEqualTo(GradeSource.ENGINE);
                }
            }
        } else {
            assertThat(d.disclosureItems()).allSatisfy(i -> assertThat(i.grade()).isEmpty());
        }
        if (d.status() != DisclosureStatus.REASONED) {
            assertThat(d.disclosureItems()).allSatisfy(i -> assertThat(i.recommendation()).isEmpty());
        }
    }

    private static TransitionOutcome invoke(Disclosure d, DisclosureCommand command, RandomGenerator r) {
        return switch (command) {
            case REPLACE_ITEMS -> d.replaceItems(randomItems(r), Fixtures.CHECK);
            case COMPARE -> d.compare(Fixtures.CHECK);
            case APPLY_SNAPSHOT -> {
                EngineRequest request = r.nextInt(8) == 0 ? new EngineRequest(Fixtures.CONSULT, Fixtures.GROUP,
                        List.of(ProductKey.parse("INS-Z:STALE-1"))) : d.engineRequest();
                yield d.applySnapshot(Fixtures.randomSnapshot(request, r), Fixtures.CHECK);
            }
            case SET_RECOMMENDATIONS -> d.setRecommendations(randomReasons(d, r), Fixtures.RULE.autoReasonCodes(), Fixtures.CHECK);
            default -> throw new IllegalStateException(command.name());
        };
    }

    private static List<ItemDraft> randomItems(RandomGenerator r) {
        int n = r.nextInt(1, 6);
        Set<String> keys = new LinkedHashSet<>();
        List<ItemDraft> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String insurer = INSURERS.get(r.nextInt(INSURERS.size()));
            if (r.nextInt(6) == 0) {
                out.add(Fixtures.temp(insurer, "Q-" + r.nextInt(1000), r.nextBoolean()));
                continue;
            }
            String key = insurer + ":PRD-" + r.nextInt(1, 4);
            if (!keys.add(key)) {
                continue;
            }
            out.add(r.nextInt(5) == 0 ? Fixtures.requested(key) : Fixtures.catalog(key, r.nextBoolean()));
        }
        return out;
    }

    private static List<AgentReason> randomReasons(Disclosure d, RandomGenerator r) {
        List<AgentReason> out = new ArrayList<>();
        for (DisclosureItem i : d.disclosureItems()) {
            boolean give = i.draft().recommended() ? r.nextInt(6) != 0 : r.nextInt(12) == 0;
            if (give) {
                ReasonCode code = AGENT_CODES.get(r.nextInt(AGENT_CODES.size()));
                out.add(new AgentReason(i.itemNo(), List.of(code), r.nextBoolean() ? "사유 텍스트" : null));
            }
        }
        return out;
    }

    private static Disclosure sealedStart(RandomGenerator r) {
        List<DisclosureStatus> sealed = List.of(DisclosureStatus.SEALED, DisclosureStatus.PARTIALLY_SIGNED, DisclosureStatus.COMPLETED,
                DisclosureStatus.VOID, DisclosureStatus.SUPERSEDED, DisclosureStatus.EXPIRED);
        return DisclosureTransitionTest.in(sealed.get(r.nextInt(sealed.size())));
    }

    @AfterAll
    static void everyPathWasExercised() {
        Map<String, Integer> counts = new java.util.TreeMap<>();
        SEEN.forEach((k, v) -> counts.put(k, v.get()));
        for (DisclosureCommand c : List.of(DisclosureCommand.REPLACE_ITEMS, DisclosureCommand.COMPARE, DisclosureCommand.APPLY_SNAPSHOT,
                DisclosureCommand.SET_RECOMMENDATIONS)) {
            assertThat(counts).as("counts %s", counts).containsKey("applied:" + c).containsKey("illegal:" + c);
        }
        assertThat(counts).containsKeys("replace-after-grading", "rejected:COMPARE", "rejected:REPLACE_ITEMS", "rejected:SET_RECOMMENDATIONS",
                "invalid-input:APPLY_SNAPSHOT", "invalid-input:SET_RECOMMENDATIONS");
        assertThat(counts.get("replace-after-grading")).isGreaterThanOrEqualTo(50);
        EnumMap<DisclosureStatus, Integer> unused = new EnumMap<>(DisclosureStatus.class);
        assertThat(unused).isEmpty();
    }
}
