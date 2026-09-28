package com.ga.platform.core.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.AccessTarget;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.Architectures;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 포털(ga-agent-portal)과 ga-disclosure가 공유하는 ArchUnit 규칙 라이브러리.
 *
 * <p>규칙 객체를 코드로 공유해 두 저장소가 같은 정의로 검사받게 하는 것이 목적이다. 이 클래스는 ArchUnit을
 * {@code compileOnly}로만 참조하므로 소비자(아키텍처 테스트 소스셋)가 {@code com.tngtech.archunit:archunit}을 직접 선언한다.
 * Spring 타입은 이름(문자열)으로만 다룬다 — platform-core는 Spring 무의존이다.
 */
public final class ArchRules {

    private ArchRules() {
    }

    // -----------------------------------------------------------------------------------------
    // double / float 금지
    // -----------------------------------------------------------------------------------------

    private static final Set<String> FLOATING_TYPES = Set.of("double", "float", "java.lang.Double", "java.lang.Float");

    /**
     * {@code double}/{@code float}/{@code Double}/{@code Float}를 필드·파라미터·반환 타입으로 쓰거나, 그 타입의 멤버를
     * 호출·참조하거나({@code Double.parseDouble} 등), 부동소수를 반환하는 메서드를 호출하는 클래스를 금지한다.
     * 지역 변수 타입은 바이트코드에 남지 않지만, 부동소수 값을 "만드는" 호출을 막아 우회 경로를 닫는다.
     */
    public static ArchRule noDoubleOrFloat() {
        ArchCondition<JavaClass> condition = new ArchCondition<>(
                "not use double/float/Double/Float in fields, parameters, return types, calls or references") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaField field : javaClass.getFields()) {
                    if (FLOATING_TYPES.contains(field.getRawType().getName())) {
                        events.add(SimpleConditionEvent.violated(field, field.getFullName() + " has floating type"));
                    }
                }
                for (JavaCodeUnit unit : javaClass.getCodeUnits()) {
                    if (FLOATING_TYPES.contains(unit.getRawReturnType().getName())) {
                        events.add(SimpleConditionEvent.violated(unit, unit.getFullName() + " returns a floating type"));
                    }
                    for (JavaClass param : unit.getRawParameterTypes()) {
                        if (FLOATING_TYPES.contains(param.getName())) {
                            events.add(SimpleConditionEvent.violated(unit, unit.getFullName() + " has a floating parameter"));
                        }
                    }
                }
                for (JavaAccess<?> access : accessesFromSelf(javaClass)) {
                    AccessTarget target = access.getTarget();
                    boolean floatingOwner = FLOATING_TYPES.contains(target.getOwner().getName());
                    boolean floatingValue = switch (target) {
                        case AccessTarget.CodeUnitAccessTarget cu -> FLOATING_TYPES.contains(cu.getRawReturnType().getName());
                        case AccessTarget.FieldAccessTarget f -> FLOATING_TYPES.contains(f.getRawType().getName());
                        default -> false;
                    };
                    if (floatingOwner || floatingValue) {
                        events.add(SimpleConditionEvent.violated(access, access.getDescription() + " touches a floating type"));
                    }
                }
                for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
                    if (FLOATING_TYPES.contains(dependency.getTargetClass().getName())) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
        return classes().should(condition).as("no double/float anywhere (CLAUDE.md 코드 규약)");
    }

    // -----------------------------------------------------------------------------------------
    // Spring·DB 무의존 모듈
    // -----------------------------------------------------------------------------------------

    /**
     * {@code packages}에 속한 클래스(단, {@code excludedPackages} 제외)는 Spring과 JDBC({@code java.sql}, {@code javax.sql})에
     * 의존할 수 없다.
     */
    public static ArchRule springFreeModules(Collection<String> packages, Collection<String> excludedPackages) {
        String[] pkgs = packages.toArray(String[]::new);
        var that = noClasses().that().resideInAnyPackage(pkgs);
        var scoped = excludedPackages.isEmpty() ? that : that.and().resideOutsideOfPackages(excludedPackages.toArray(String[]::new));
        return scoped.should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "java.sql..", "javax.sql..")
                .allowEmptyShould(true)
                .as("modules " + packages + " (except " + excludedPackages + ") are Spring/DB free");
    }

    // -----------------------------------------------------------------------------------------
    // DB 접근 경로
    // -----------------------------------------------------------------------------------------

    private static final List<String> RAW_JDBC_ENTRY_TYPES = List.of(
            "javax.sql.DataSource",
            "java.sql.DriverManager",
            "org.springframework.jdbc.core.simple.JdbcClient",
            "org.springframework.jdbc.core.JdbcTemplate",
            "org.springframework.jdbc.core.JdbcOperations",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations");

    /**
     * DB 접근은 {@code repositoryBase}를 상속한 클래스에서만 한다.
     * <ol>
     *   <li>JDBC 계열 패키지({@code java.sql}, {@code javax.sql}, {@code org.springframework.jdbc})에 의존하는 클래스는
     *       {@code repositoryBase}의 하위 클래스이거나 {@code infrastructure}(및 그 중첩 클래스)여야 한다.</li>
     *   <li>원시 접근 진입점({@code DataSource}, {@code JdbcClient}, {@code JdbcTemplate} 등)을 직접 참조하는 클래스는
     *       {@code repositoryBase} 자신(및 중첩 클래스)과 {@code infrastructure}(및 중첩 클래스)뿐이다 —
     *       하위 저장소도 원시 진입점을 만질 수 없고 기반 클래스의 테넌트 주입 경로만 쓴다.</li>
     * </ol>
     */
    public static ArchRule dbAccessOnlyVia(Class<?> repositoryBase, Class<?>... infrastructure) {
        List<Class<?>> infra = List.of(infrastructure);
        DescribedPredicate<JavaClass> repositoryOrInfra = DescribedPredicate.describe(
                "a subclass of " + repositoryBase.getSimpleName() + " (or nested in it) or " + names(infra),
                c -> c.isAssignableTo(repositoryBase) || isOrNestedIn(c, List.of(repositoryBase)) || isOrNestedIn(c, infra));
        DescribedPredicate<JavaClass> baseOrInfra = DescribedPredicate.describe(
                repositoryBase.getSimpleName() + " itself or " + names(infra),
                c -> isOrNestedIn(c, List.of(repositoryBase)) || isOrNestedIn(c, infra));

        ArchRule jdbcPackages = noClasses().that(DescribedPredicate.not(repositoryOrInfra))
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..", "org.springframework.jdbc..")
                .allowEmptyShould(true)
                .as("JDBC packages are used only by " + repositoryBase.getSimpleName() + " subclasses and " + names(infra));
        ArchRule rawEntries = noClasses().that(DescribedPredicate.not(baseOrInfra))
                .should().dependOnClassesThat(DescribedPredicate.describe(
                        "raw JDBC entry points " + RAW_JDBC_ENTRY_TYPES,
                        (JavaClass t) -> RAW_JDBC_ENTRY_TYPES.contains(t.getName())))
                .allowEmptyShould(true)
                .as("DataSource/JdbcClient/JdbcTemplate are referenced only by " + repositoryBase.getSimpleName() + " and " + names(infra));
        return CompositeArchRule.of(jdbcPackages).and(rawEntries);
    }

    // -----------------------------------------------------------------------------------------
    // 모듈(레이어) 의존 방향
    // -----------------------------------------------------------------------------------------

    /**
     * 모듈 레이어 정의. {@code mayBeAccessedBy}가 비어 있으면 어떤 레이어도 접근할 수 없다.
     *
     * @param name            레이어 이름
     * @param packages        레이어에 속하는 패키지 식별자(예: {@code "com.ga.disclosure.domain.."})
     * @param mayBeAccessedBy 이 레이어에 접근할 수 있는 다른 레이어 이름
     */
    public record Layer(String name, List<String> packages, Set<String> mayBeAccessedBy) {
        public Layer {
            packages = List.copyOf(packages);
            mayBeAccessedBy = Set.copyOf(mayBeAccessedBy);
        }

        public static Layer of(String name, String packageIdentifier, String... mayBeAccessedBy) {
            return new Layer(name, List.of(packageIdentifier), Set.of(mayBeAccessedBy));
        }
    }

    /** 레이어 간 의존 방향. 레이어 밖(JDK·서드파티) 의존은 이 규칙의 관심사가 아니다. 빈 레이어는 허용한다. */
    public static ArchRule layeredDependencies(Layer... layers) {
        Architectures.LayeredArchitecture arch = Architectures.layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .withOptionalLayers(true);
        for (Layer layer : layers) {
            arch = arch.layer(layer.name()).definedBy(layer.packages().toArray(String[]::new));
        }
        for (Layer layer : layers) {
            arch = layer.mayBeAccessedBy().isEmpty()
                    ? arch.whereLayer(layer.name()).mayNotBeAccessedByAnyLayer()
                    : arch.whereLayer(layer.name()).mayOnlyBeAccessedByLayers(layer.mayBeAccessedBy().stream().sorted().toArray(String[]::new));
        }
        return arch;
    }

    // -----------------------------------------------------------------------------------------
    // 타입·메서드 사용 범위 제한 (수수료율 연산 금지 등 도메인 규칙의 재료)
    // -----------------------------------------------------------------------------------------

    /** {@code types}는 {@code allowedPackages} 안에서만 참조할 수 있다. */
    public static ArchRule typesOnlyUsedIn(Collection<Class<?>> types, String... allowedPackages) {
        return noClasses().that().resideOutsideOfPackages(allowedPackages)
                .should().dependOnClassesThat().belongToAnyOf(types.toArray(Class<?>[]::new))
                .allowEmptyShould(true)
                .as(names(types) + " may be referenced only in " + Arrays.toString(allowedPackages));
    }

    /**
     * {@code owner#methodName}의 호출과 메서드 참조({@code Owner::method})는 {@code allowedPackages} 안에서만 허용한다
     * ({@code owner} 자신은 제외).
     */
    public static ArchRule methodOnlyInvokedFrom(Class<?> owner, String methodName, String... allowedPackages) {
        ArchCondition<JavaClass> condition = new ArchCondition<>(
                "not invoke or reference " + owner.getSimpleName() + "." + methodName + "()") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaAccess<?> access : codeUnitAccessesFromSelf(javaClass)) {
                    if (access.getTargetOwner().isEquivalentTo(owner) && access.getName().equals(methodName)) {
                        events.add(SimpleConditionEvent.violated(access, access.getDescription()));
                    }
                }
            }
        };
        return classes().that().resideOutsideOfPackages(allowedPackages).and().doNotBelongToAnyOf(owner)
                .should(condition)
                .allowEmptyShould(true)
                .as(owner.getSimpleName() + "." + methodName + "() may be used only in " + Arrays.toString(allowedPackages));
    }

    /** {@code types}는 {@link Comparable}을 구현하지 않는다. */
    public static ArchRule notComparable(Class<?>... types) {
        return classes().that().belongToAnyOf(types)
                .should().notImplement(Comparable.class)
                .as(names(List.of(types)) + " are not Comparable");
    }

    /** 순서를 매기는 API. 소유 타입 → 메서드 이름(빈 집합이면 모든 메서드·생성자). */
    private static final Map<String, Set<String>> ORDERING_APIS = Map.ofEntries(
            Map.entry("java.util.Comparator", Set.of()),
            Map.entry("java.lang.Comparable", Set.of("compareTo")),
            Map.entry("java.util.stream.Stream", Set.of("sorted", "max", "min")),
            Map.entry("java.util.stream.IntStream", Set.of("sorted", "max", "min")),
            Map.entry("java.util.stream.LongStream", Set.of("sorted", "max", "min")),
            Map.entry("java.util.stream.Collectors", Set.of("maxBy", "minBy")),
            Map.entry("java.util.Collections", Set.of("sort", "max", "min", "reverseOrder")),
            Map.entry("java.util.List", Set.of("sort")),
            Map.entry("java.util.Arrays", Set.of("sort", "parallelSort")),
            Map.entry("java.lang.Integer", Set.of("compare", "compareTo", "max", "min")),
            Map.entry("java.lang.Long", Set.of("compare", "compareTo", "max", "min")),
            Map.entry("java.lang.Short", Set.of("compare", "compareTo")),
            Map.entry("java.lang.String", Set.of("compareTo", "compareToIgnoreCase")),
            Map.entry("java.lang.Math", Set.of("max", "min")),
            Map.entry("java.util.TreeSet", Set.of()),
            Map.entry("java.util.TreeMap", Set.of()),
            Map.entry("java.util.PriorityQueue", Set.of()),
            Map.entry("java.util.concurrent.ConcurrentSkipListSet", Set.of()),
            Map.entry("java.util.concurrent.ConcurrentSkipListMap", Set.of()));

    /**
     * {@code elementTypes}를 참조하는 클래스는 순서를 매기는 API({@code Comparator}·{@code sorted}·{@code max}·{@code min}·
     * {@code sort}·{@code compare}·정렬 컬렉션)를 호출·참조할 수 없다. 단 {@code allowedClasses}(및 중첩 클래스)는 예외다.
     *
     * <p>스트림 원소의 제네릭 타입은 바이트코드에서 사라지므로 "이 타입을 원소로 하는 정렬"을 정확히 판정할 수 없다.
     * 그래서 한 클래스 안에서 두 가지가 함께 나타나는지(동시 사용)로 판정한다 — 의도보다 넓게 잡는 보수적 근사다.
     * 람다 본문은 같은 클래스의 합성 메서드이므로 함께 검사된다.
     */
    public static ArchRule noOrderingInClassesUsing(Collection<Class<?>> elementTypes, Class<?>... allowedClasses) {
        List<Class<?>> allowed = List.of(allowedClasses);
        ArchCondition<JavaClass> condition = new ArchCondition<>(
                "not use ordering APIs while referencing " + names(elementTypes)) {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean usesElement = javaClass.getDirectDependenciesFromSelf().stream()
                        .anyMatch(d -> elementTypes.stream().anyMatch(t -> d.getTargetClass().isEquivalentTo(t)));
                if (!usesElement) {
                    return;
                }
                for (JavaAccess<?> access : codeUnitAccessesFromSelf(javaClass)) {
                    Set<String> methods = ORDERING_APIS.get(access.getTargetOwner().getName());
                    if (methods != null && (methods.isEmpty() || methods.contains(access.getName()))) {
                        events.add(SimpleConditionEvent.violated(access,
                                javaClass.getName() + " references " + names(elementTypes) + " and orders via " + access.getDescription()));
                    }
                }
            }
        };
        return classes().that(DescribedPredicate.describe(
                        "are not " + names(elementTypes) + " or " + names(allowed),
                        (JavaClass c) -> !isOrNestedIn(c, List.copyOf(elementTypes)) && !isOrNestedIn(c, allowed)))
                .should(condition)
                .allowEmptyShould(true)
                .as("ordering of " + names(elementTypes) + " only in " + names(allowed));
    }

    /** 단순 이름에 {@code fragments} 중 하나라도 포함한 클래스를 금지한다. */
    public static ArchRule noClassNameContaining(String... fragments) {
        ArchCondition<JavaClass> condition = new ArchCondition<>("not have a simple name containing " + Arrays.toString(fragments)) {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (String fragment : fragments) {
                    if (javaClass.getSimpleName().contains(fragment)) {
                        events.add(SimpleConditionEvent.violated(javaClass, javaClass.getName() + " contains '" + fragment + "'"));
                    }
                }
            }
        };
        return classes().should(condition).as("no class name contains " + Arrays.toString(fragments));
    }

    // -----------------------------------------------------------------------------------------

    private static List<JavaAccess<?>> accessesFromSelf(JavaClass javaClass) {
        List<JavaAccess<?>> out = new ArrayList<>(codeUnitAccessesFromSelf(javaClass));
        for (JavaFieldAccess access : javaClass.getFieldAccessesFromSelf()) {
            out.add(access);
        }
        return out;
    }

    /** 메서드·생성자 호출과 메서드·생성자 참조({@code X::m}, {@code X::new}). */
    private static List<JavaAccess<?>> codeUnitAccessesFromSelf(JavaClass javaClass) {
        return Stream.<Collection<? extends JavaAccess<?>>>of(
                        javaClass.getMethodCallsFromSelf(),
                        javaClass.getConstructorCallsFromSelf(),
                        javaClass.getMethodReferencesFromSelf(),
                        javaClass.getConstructorReferencesFromSelf())
                .<JavaAccess<?>>flatMap(Collection::stream)
                .toList();
    }

    private static boolean isOrNestedIn(JavaClass candidate, Collection<Class<?>> anchors) {
        for (Optional<JavaClass> c = Optional.of(candidate); c.isPresent(); c = c.get().getEnclosingClass()) {
            JavaClass current = c.get();
            if (anchors.stream().anyMatch(current::isEquivalentTo)) {
                return true;
            }
        }
        return false;
    }

    private static String names(Collection<? extends Class<?>> types) {
        return types.stream().map(Class::getSimpleName).toList().toString();
    }
}
