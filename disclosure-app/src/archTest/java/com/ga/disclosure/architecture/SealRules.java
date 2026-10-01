package com.ga.disclosure.architecture;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * 3B: 봉인 본문·렌더러의 환경 독립 규칙(ArchitectureRulesTest가 실행). 금지: 벽시계({@code now()}·{@code Clock}·{@code currentTimeMillis}),
 * 난수({@code Random}·{@code SecureRandom}·{@code UUID.randomUUID}·{@code Math.random}), 기본 로케일·시간대({@code Locale.getDefault},
 * {@code TimeZone.getDefault}, {@code ZoneId.systemDefault}).
 */
final class SealRules {

    /** 의존 자체를 금지하는 타입. */
    private static final Set<String> FORBIDDEN_TYPES = Set.of("java.time.Clock", "java.security.SecureRandom", "java.util.Random",
            "java.util.concurrent.ThreadLocalRandom", "java.util.random.RandomGenerator");

    /** 호출을 금지하는 멤버(소유 타입#이름). */
    private static final Set<String> FORBIDDEN_CALLS = Set.of(
            "java.time.Instant#now", "java.time.LocalDate#now", "java.time.LocalDateTime#now", "java.time.LocalTime#now",
            "java.time.ZonedDateTime#now", "java.time.OffsetDateTime#now", "java.time.Year#now", "java.time.ZoneId#systemDefault",
            "java.lang.System#currentTimeMillis", "java.lang.System#nanoTime", "java.util.Locale#getDefault",
            "java.util.TimeZone#getDefault", "java.util.UUID#randomUUID", "java.lang.Math#random", "java.util.Calendar#getInstance",
            "java.util.Date#<init>");

    private SealRules() {
    }

    static ArchRule noEnvironmentAccess(List<String> packages) {
        ArchCondition<JavaClass> condition = new ArchCondition<>("not read the wall clock, randomness or the default locale/time zone") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (Dependency d : javaClass.getDirectDependenciesFromSelf()) {
                    if (FORBIDDEN_TYPES.contains(d.getTargetClass().getName())) {
                        events.add(SimpleConditionEvent.violated(d, d.getDescription()));
                    }
                }
                for (JavaAccess<?> access : javaClass.getAccessesFromSelf()) {
                    String key = access.getTarget().getOwner().getName() + "#" + access.getTarget().getName();
                    if (FORBIDDEN_CALLS.contains(key)) {
                        events.add(SimpleConditionEvent.violated(access, access.getDescription()));
                    }
                }
            }
        };
        return classes().that().resideInAnyPackage(packages.toArray(String[]::new)).should(condition)
                .as("seal body and renderer are environment-free (3B S1·S2)");
    }
}
