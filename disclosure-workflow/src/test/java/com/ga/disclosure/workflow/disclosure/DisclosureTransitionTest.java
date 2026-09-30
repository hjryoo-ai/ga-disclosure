package com.ga.disclosure.workflow.disclosure;

import com.ga.disclosure.domain.disclosure.AgentReason;
import com.ga.disclosure.domain.disclosure.DisclosureCommand;
import com.ga.disclosure.domain.disclosure.DisclosureStateTable;
import com.ga.disclosure.domain.disclosure.IllegalTransition;
import com.ga.disclosure.domain.enums.DisclosureStatus;
import com.ga.disclosure.domain.grade.EngineSnapshot;
import com.ga.disclosure.domain.vo.ReasonCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 3A W1(애그리게이트): 구현된 명령 4종 × 상태 10종 전수 — 표 안이면 전이하고(결과 상태가 표의 결과 중 하나), 표 밖이면
 * {@link IllegalTransition}(from, command)이며 상태가 바뀌지 않는다. 구현된 명령 목록은 이 테스트가 따로 가진다(계획 승인 B3) —
 * 3B·Phase 4가 명령을 구현할 때 이 목록만 늘린다. 표 자체와 설계서의 대조는 {@code disclosure-domain}의 DisclosureStateTableTest.
 */
class DisclosureTransitionTest {

    static final List<DisclosureCommand> IMPLEMENTED = List.of(DisclosureCommand.REPLACE_ITEMS, DisclosureCommand.COMPARE,
            DisclosureCommand.APPLY_SNAPSHOT, DisclosureCommand.SET_RECOMMENDATIONS);

    record State(DisclosureStatus status, List<DisclosureItem> items, Optional<EngineSnapshot> snapshot) {
        static State of(Disclosure d) {
            return new State(d.status(), d.disclosureItems(), d.engineSnapshot());
        }
    }

    /** 상태 {@code status}의 확인서: 가변 상태는 명령으로 도달하고, 봉인 이후 상태는 REASONED 내용으로 복원한다. */
    static Disclosure in(DisclosureStatus status) {
        Disclosure d = Fixtures.draft();
        d.replaceItems(Fixtures.threeItems(), Fixtures.CHECK);
        if (status == DisclosureStatus.DRAFT) {
            return d;
        }
        applied(d.compare(Fixtures.CHECK));
        if (status == DisclosureStatus.COMPARED) {
            return d;
        }
        applied(d.applySnapshot(Fixtures.snapshotFor(d.engineRequest()), Fixtures.CHECK));
        if (status == DisclosureStatus.GRADED) {
            return d;
        }
        applied(d.setRecommendations(reasons(), Fixtures.RULE.autoReasonCodes(), Fixtures.CHECK));
        if (status == DisclosureStatus.REASONED) {
            return d;
        }
        return Disclosure.restore(d.id(), d.agentId(), d.customerRef(), d.groupCode(), d.consultDate(), d.ruleVersionId(), null,
                d.template(), d.issuerMode(), Fixtures.CONTEXT, status, d.disclosureItems(), d.engineSnapshot().orElseThrow());
    }

    static List<AgentReason> reasons() {
        return List.of(new AgentReason(1, List.of(ReasonCode.of("PREMIUM")), null),
                new AgentReason(3, List.of(ReasonCode.of("COVERAGE")), null));
    }

    private static void applied(TransitionOutcome o) {
        assertThat(o).as("%s", o.results()).isInstanceOf(TransitionOutcome.Applied.class);
    }

    static TransitionOutcome invoke(Disclosure d, DisclosureCommand command) {
        return switch (command) {
            case REPLACE_ITEMS -> d.replaceItems(Fixtures.threeItems(), Fixtures.CHECK);
            case COMPARE -> d.compare(Fixtures.CHECK);
            case APPLY_SNAPSHOT -> d.applySnapshot(Fixtures.snapshotFor(d.engineRequest()), Fixtures.CHECK);
            case SET_RECOMMENDATIONS -> d.setRecommendations(reasons(), Fixtures.RULE.autoReasonCodes(), Fixtures.CHECK);
            default -> throw new IllegalArgumentException(command + " is not implemented in 3A");
        };
    }

    static Stream<Arguments> everyStatusTimesImplementedCommand() {
        return Arrays.stream(DisclosureStatus.values()).flatMap(s -> IMPLEMENTED.stream().map(c -> Arguments.of(s, c)));
    }

    @ParameterizedTest(name = "{0} × {1}")
    @MethodSource("everyStatusTimesImplementedCommand")
    void implementedCommandsFollowTheTableExactly(DisclosureStatus status, DisclosureCommand command) {
        Disclosure d = in(status);
        State before = State.of(d);
        if (DisclosureStateTable.allows(status, command)) {
            TransitionOutcome o = invoke(d, command);
            applied(o);
            assertThat(DisclosureStateTable.results(status, command)).contains(d.status());
            assertThat(o.from()).isEqualTo(status);
        } else {
            IllegalTransition e = catchThrowableOfType(IllegalTransition.class, () -> invoke(d, command));
            assertThat(e).as("%s × %s must be illegal", status, command).isNotNull();
            assertThat(e.from()).isEqualTo(status);
            assertThat(e.command()).isEqualTo(command);
            assertThat(State.of(d)).isEqualTo(before);
        }
    }

    @Test
    void implementedListCoversEveryCommandMethodOfTheAggregate() {
        List<String> commandMethods = Arrays.stream(Disclosure.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && m.getReturnType() == TransitionOutcome.class)
                .map(Method::getName).sorted().toList();
        assertThat(commandMethods).containsExactly("applySnapshot", "compare", "replaceItems", "setRecommendations");
        assertThat(IMPLEMENTED).hasSize(commandMethods.size());
    }

    /** 상담일 변경 명령은 없다: 필드는 final이고 상담일을 받는 공개 메서드는 생성·복원뿐이다. */
    @Test
    void consultDateCannotChange() throws NoSuchFieldException {
        Field f = Disclosure.class.getDeclaredField("consultDate");
        assertThat(Modifier.isFinal(f.getModifiers())).isTrue();
        List<String> takingDate = Arrays.stream(Disclosure.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> Arrays.asList(m.getParameterTypes()).contains(java.time.LocalDate.class))
                .map(Method::getName).sorted().toList();
        assertThat(takingDate).containsExactly("draft", "isInsurerOnPanel", "restore");
    }
}
