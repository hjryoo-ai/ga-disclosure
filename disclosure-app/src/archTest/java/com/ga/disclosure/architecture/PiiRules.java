package com.ga.disclosure.architecture;

import com.ga.disclosure.domain.pii.BirthDate;
import com.ga.disclosure.domain.pii.CustomerName;
import com.ga.disclosure.domain.pii.PhoneNumber;
import com.ga.disclosure.domain.pii.Sensitive;
import com.ga.disclosure.domain.pii.SensitiveValue;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Phase 2 P5·P6: 개인정보 값객체가 새는 구조적 경로를 막는 규칙(ArchitectureRulesTest가 실행). */
final class PiiRules {

    private static final Set<Class<?>> PII_VALUES = Set.of(CustomerName.class, PhoneNumber.class, BirthDate.class, SensitiveValue.class);

    private PiiRules() {
    }

    /**
     * {@code record}는 {@link Sensitive}를 컴포넌트로 가질 수 없다 — 제네릭 인자({@code Optional<Sensitive<…>>}, {@code List<…>})까지.
     * record의 자동 {@code toString}·접근자·직렬화가 API 응답·로그로 원문을 내보내는 경로를 닫는다.
     */
    static ArchRule noRecordHoldsSensitive() {
        ArchCondition<JavaClass> condition = new ArchCondition<>("not have a component involving Sensitive") {
            @Override
            public void check(JavaClass record, ConditionEvents events) {
                for (JavaField field : record.getFields()) {
                    if (!field.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.STATIC)
                            && field.getType().getAllInvolvedRawTypes().stream().anyMatch(t -> t.isEquivalentTo(Sensitive.class))) {
                        events.add(SimpleConditionEvent.violated(field, record.getName() + " record component " + field.getName()
                                + " holds " + field.getType().getName()));
                    }
                }
            }
        };
        return classes().that().areRecords().should(condition).allowEmptyShould(true)
                .as("no record has a component of type Sensitive (DTO leak path, Phase 2 P5)");
    }

    /** 원문 값 타입(이름·전화·생년월일)은 {@code domain.pii} 밖에서 필드 타입이 될 수 없다 — 밖에서는 {@code Sensitive}로만 들고 다닌다. */
    static ArchRule piiValuesAreHeldOnlyInsideSensitive(String piiPackage) {
        ArchCondition<JavaClass> condition = new ArchCondition<>("not declare fields of a raw PII value type") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaField field : javaClass.getFields()) {
                    if (PII_VALUES.stream().anyMatch(t -> field.getRawType().isEquivalentTo(t))) {
                        events.add(SimpleConditionEvent.violated(field, javaClass.getName() + "." + field.getName() + " is a raw "
                                + field.getRawType().getSimpleName()));
                    }
                }
            }
        };
        return classes().that().resideOutsideOfPackage(piiPackage).should(condition).allowEmptyShould(true)
                .as("raw PII value types are fields only inside " + piiPackage);
    }

    /** {@code javax.crypto}는 {@code allowedPackage}(정확한 패키지)에서만. */
    static ArchRule cryptoOnlyIn(String allowedPackage) {
        return noClasses().that().resideOutsideOfPackage(allowedPackage)
                .should().dependOnClassesThat().resideInAPackage("javax.crypto..")
                .as("javax.crypto only in " + allowedPackage);
    }

    /**
     * P6: {@link BirthDate#matches}는 {@link MessageDigest#isEqual}로 비교하고, 짧게 끊기는 비교({@code String.equals},
     * {@code Arrays.equals}, {@code Objects.equals}, {@code contentEquals})를 쓰지 않는다.
     */
    static ArchRule birthDateMatchIsConstantTime() {
        ArchCondition<JavaClass> condition = new ArchCondition<>("compare birth dates with MessageDigest.isEqual only") {
            @Override
            public void check(JavaClass birthDate, ConditionEvents events) {
                JavaMethod matches = birthDate.getMethods().stream().filter(m -> m.getName().equals("matches")).findFirst().orElse(null);
                if (matches == null) {
                    events.add(SimpleConditionEvent.violated(birthDate, "BirthDate.matches is missing"));
                    return;
                }
                List<JavaMethodCall> calls = List.copyOf(matches.getMethodCallsFromSelf());
                boolean constantTime = calls.stream().anyMatch(c -> c.getTargetOwner().isEquivalentTo(MessageDigest.class)
                        && c.getName().equals("isEqual"));
                if (!constantTime) {
                    events.add(SimpleConditionEvent.violated(matches, "BirthDate.matches does not call MessageDigest.isEqual"));
                }
                for (JavaMethodCall c : calls) {
                    boolean shortCircuit = (c.getName().equals("equals") && (c.getTargetOwner().isEquivalentTo(String.class)
                            || c.getTargetOwner().isEquivalentTo(Arrays.class) || c.getTargetOwner().isEquivalentTo(Objects.class)))
                            || c.getName().equals("contentEquals") || c.getName().equals("equalsIgnoreCase");
                    if (shortCircuit) {
                        events.add(SimpleConditionEvent.violated(c, "BirthDate.matches uses a short-circuit comparison: " + c.getDescription()));
                    }
                }
            }
        };
        return classes().that().belongToAnyOf(BirthDate.class).should(condition)
                .as("BirthDate.matches compares in constant time (Phase 2 P6)");
    }
}
