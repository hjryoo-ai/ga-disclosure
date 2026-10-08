package com.ga.disclosure.architecture;

import com.ga.disclosure.workflow.authz.Action;
import com.ga.disclosure.workflow.authz.AuthorizationPort;
import com.ga.disclosure.workflow.authz.Caller;
import com.ga.disclosure.workflow.authz.NotAnEntry;
import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.ga.platform.core.arch.ArchRules.Allowed;
import com.ga.platform.core.tenant.TenantId;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6A 계획 §3.4(3단계): 인가 누락을 구조로 막는다. 대상은 {@code workflow}의 공개 클래스의 공개 인스턴스 메서드다.
 * <ol>
 *   <li><b>표식 전수</b>: 테넌트({@link TenantId})·호출자({@link Caller})·테넌트 목록을 받거나 {@code rawToken} 인자가 있으면 {@link UseCaseEntry}
 *       또는 {@link NotAnEntry}가 있어야 한다(둘 다는 안 된다).</li>
 *   <li><b>진입점은 스스로 인가한다</b>: {@link UseCaseEntry} 메서드는 자기 몸통이나 자기 람다({@code lambda$이름$N})에서
 *       {@link AuthorizationPort#require} 또는 {@link AuthorizationPort#requireList}(목록 범위)를 부르고, 거기서 읽는 {@link Action} 상수 집합이 표식의 행위 집합과 같다.</li>
 *   <li><b>컨트롤러는 진입점만 부른다</b>(계획 §3.4 규칙 3): {@code disclosure-api}의 클래스가 유스케이스 클래스(진입점이 하나라도 있는
 *       {@code workflow} 클래스)에서 부르는 메서드는 {@link UseCaseEntry}뿐이다. 결과 레코드의 접근자 등 유스케이스 클래스가 아닌 타입은 대상이 아니다.</li>
 *   <li><b>내부 단계는 밖에서 부를 수 없다</b>: {@link NotAnEntry} 메서드 목록은 아래 닫힌 FQN 열거와 같고, {@code workflow} 밖(컨트롤러·CLI)에서
 *       호출되지 않는다 — 예외는 (메서드, 호출 클래스) 쌍의 닫힌 열거 {@link #OUTSIDE_CALLERS}뿐(멱등 인터셉터).</li>
 * </ol>
 * 규칙이 거짓 양성을 내면 규칙을 좁히지 않고 코드를 옮긴다(CLAUDE.md).
 */
class AuthorizationCoverageTest {

    static final String WORKFLOW = "com.ga.disclosure.workflow";

    /** 진입점이 아닌 테넌트 메서드(FQN#이름) — 닫힌 열거, 사유는 소스의 {@link NotAnEntry} 값과 같다. */
    static final List<Allowed> NOT_AN_ENTRY = List.of(
            new Allowed(WORKFLOW + ".customer.CustomerRefService#lookup",
                    "vault read with a CUSTOMER_VIEW audit row; no HTTP or CLI caller — customer APIs are a separate 6B plan (6A approval Q17)"),
            new Allowed(WORKFLOW + ".customer.CustomerRefService#phoneForNotification",
                    "internal step of the notification dispatcher, which has authorized NOTIFY_DISPATCH in the same transaction"),
            new Allowed(WORKFLOW + ".idempotency.IdempotencyService#claim",
                    "write-path plumbing called only by the API idempotency interceptor; the use case behind it authorizes, rows live in the caller's own key space"),
            new Allowed(WORKFLOW + ".idempotency.IdempotencyService#complete",
                    "write-path plumbing called only by the API idempotency interceptor after the use case answered; records the closed receipt tuple"),
            new Allowed(WORKFLOW + ".idempotency.IdempotencyService#release",
                    "write-path plumbing called only by the API idempotency interceptor after an unstored response; deletes the caller's own in-progress claim"),
            new Allowed(WORKFLOW + ".flag.FlagPolicyResolver#at",
                    "flag raise plumbing: copies today's rule policy for the flag type inside the raising use case's transaction; reads rule data only"),
            new Allowed(WORKFLOW + ".sign.PublicSignLimits#perMinute",
                    "public sign gate plumbing: reads the known tenant's rate limit before any token is checked; reads rule data only"));

    /**
     * 규칙 4의 예외 — {@code workflow} 밖에서 내부 단계를 부를 수 있는 (메서드, 호출 클래스) 쌍. 닫힌 FQN 열거이고 실제로 없는 쌍은 실패한다(폐기 항목).
     * 멱등 청구·완료·해제는 유스케이스 앞뒤에 도는 HTTP 장치라 진입점이 될 수 없다(6A 계획 §4.2). 공개 서명 한도 읽기는 토큰 검사 전의 문이 부른다(§5.4).
     */
    static final List<String> OUTSIDE_CALLERS = List.of(
            WORKFLOW + ".idempotency.IdempotencyService#claim <- com.ga.disclosure.api.idempotency.IdempotencyInterceptor",
            WORKFLOW + ".idempotency.IdempotencyService#complete <- com.ga.disclosure.api.idempotency.IdempotencyInterceptor",
            WORKFLOW + ".idempotency.IdempotencyService#release <- com.ga.disclosure.api.idempotency.IdempotencyInterceptor",
            WORKFLOW + ".sign.PublicSignLimits#perMinute <- com.ga.disclosure.api.security.PublicSignGate");

    /** 진입점이 스스로 부를 인가 메서드: 대상 하나({@code require}) 또는 목록 범위({@code requireList} — 6A 6c, 범위로 걸러진 목록). */
    static final Set<String> AUTHORIZING = Set.of("require", "requireList");

    static JavaClasses classes;

    @BeforeAll
    static void importProductionClasses() {
        classes = new ClassFileImporter().withImportOption(ArchitectureRulesTest.PRODUCTION_ONLY).importPackages("com.ga");
    }

    static Stream<JavaMethod> candidates() {
        return classes.stream()
                .filter(c -> c.getPackageName().startsWith(WORKFLOW))
                .filter(c -> !c.isInterface() && !c.isAnnotation() && c.getModifiers().contains(JavaModifier.PUBLIC))
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.getModifiers().contains(JavaModifier.PUBLIC) && !m.getModifiers().contains(JavaModifier.STATIC)
                        && !m.getModifiers().contains(JavaModifier.SYNTHETIC) && !m.getModifiers().contains(JavaModifier.BRIDGE));
    }

    static boolean tenantShaped(JavaMethod m) {
        for (JavaType t : m.getParameterTypes()) {
            if (t.toErasure().isEquivalentTo(TenantId.class) || t.toErasure().isEquivalentTo(Caller.class)) {
                return true;
            }
            if (t instanceof JavaParameterizedType p && p.toErasure().isAssignableTo(java.util.Collection.class)
                    && p.getActualTypeArguments().stream().anyMatch(a -> a.toErasure().isEquivalentTo(TenantId.class))) {
                return true;
            }
        }
        return Arrays.stream(m.reflect() instanceof java.lang.reflect.Method r ? r.getParameters() : new Parameter[0])
                .anyMatch(p -> p.getName().equals("rawToken"));
    }

    static String id(JavaMethod m) {
        return m.getOwner().getName() + "#" + m.getName();
    }

    /** 이 메서드와 그 람다들(같은 클래스의 {@code lambda$이름$N} — 람다 안 람다 포함). */
    static List<JavaMethod> withLambdas(JavaMethod m) {
        List<JavaMethod> out = new ArrayList<>();
        out.add(m);
        String prefix = "lambda$" + m.getName() + "$";
        m.getOwner().getMethods().stream().filter(x -> x.getName().startsWith(prefix)).forEach(out::add);
        return out;
    }

    @Test
    void theCandidateSetIsNotEmpty() {
        List<String> entries = candidates().filter(m -> m.isAnnotatedWith(UseCaseEntry.class)).map(AuthorizationCoverageTest::id).toList();
        assertThat(entries).as("annotations must be visible to the importer (CLASS retention)").hasSizeGreaterThanOrEqualTo(39)
                .contains(WORKFLOW + ".disclosure.DisclosureService#createDraft", WORKFLOW + ".anchor.AnchorJob#run",
                        WORKFLOW + ".disclosure.SignService#capture");
    }

    @Test
    void everyTenantShapedPublicMethodIsMarked() {
        List<String> violations = candidates().filter(AuthorizationCoverageTest::tenantShaped)
                .filter(m -> m.isAnnotatedWith(UseCaseEntry.class) == m.isAnnotatedWith(NotAnEntry.class))
                .map(m -> m.getFullName() + (m.isAnnotatedWith(UseCaseEntry.class) ? " has both marks" : " has no mark"))
                .sorted().toList();
        assertThat(violations).isEmpty();
    }

    @Test
    void everyEntryAuthorizesItsDeclaredActions() {
        List<String> violations = new ArrayList<>();
        candidates().filter(m -> m.isAnnotatedWith(UseCaseEntry.class)).forEach(m -> {
            List<JavaMethod> body = withLambdas(m);
            boolean requires = body.stream().flatMap(b -> b.getMethodCallsFromSelf().stream())
                    .anyMatch(c -> c.getTargetOwner().isEquivalentTo(AuthorizationPort.class) && AUTHORIZING.contains(c.getName()));
            if (!requires) {
                violations.add(m.getFullName() + " never calls AuthorizationPort.require/requireList");
            }
            Set<String> read = body.stream().flatMap(b -> b.getFieldAccesses().stream())
                    .filter(a -> a.getTargetOwner().isEquivalentTo(Action.class) && a.getAccessType() == JavaFieldAccess.AccessType.GET)
                    .map(a -> a.getName()).collect(Collectors.toCollection(TreeSet::new));
            JavaAnnotation<JavaMethod> mark = m.getAnnotationOfType(UseCaseEntry.class.getName());
            Set<String> declared = Arrays.stream((Object[]) mark.get("value").orElseThrow())
                    .map(v -> ((com.tngtech.archunit.core.domain.JavaEnumConstant) v).name()).collect(Collectors.toCollection(TreeSet::new));
            if (!read.equals(declared)) {
                violations.add(m.getFullName() + " declares " + declared + " but requires " + read);
            }
        });
        assertThat(violations).isEmpty();
    }

    @Test
    void controllersCallOnlyEntriesOfUseCaseClasses() {
        Set<String> useCaseClasses = candidates().filter(m -> m.isAnnotatedWith(UseCaseEntry.class)).map(m -> m.getOwner().getName())
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(useCaseClasses).contains(WORKFLOW + ".disclosure.DisclosureService", WORKFLOW + ".disclosure.SignService");
        List<String> violations = classes.stream().filter(c -> c.getPackageName().startsWith("com.ga.disclosure.api"))
                .flatMap(c -> c.getMethodCallsFromSelf().stream())
                .filter(call -> useCaseClasses.contains(call.getTargetOwner().getName()))
                .filter(call -> call.getTarget().resolveMember().map(m -> !m.isAnnotatedWith(UseCaseEntry.class)).orElse(true))
                .map(JavaMethodCall::getDescription).sorted().toList();
        assertThat(violations).isEmpty();
    }

    @Test
    void internalStepsAreClosedAndCalledOnlyInsideTheWorkflow() {
        List<JavaMethod> marked = candidates().filter(m -> m.isAnnotatedWith(NotAnEntry.class)).toList();
        assertThat(marked.stream().map(AuthorizationCoverageTest::id).distinct().sorted().toList())
                .as("closed FQN enumeration").isEqualTo(NOT_AN_ENTRY.stream().map(Allowed::fqn).sorted().toList());
        for (JavaMethod m : marked) {
            String reason = (String) m.getAnnotationOfType(NotAnEntry.class.getName()).get("value").orElseThrow();
            assertThat(NOT_AN_ENTRY).as("reason in the list matches the source mark").contains(new Allowed(id(m), reason));
        }
        List<JavaMethodCall> outsideCalls = marked.stream().flatMap(m -> m.getCallsOfSelf().stream())
                .filter(c -> !c.getOriginOwner().getPackageName().startsWith(WORKFLOW)).toList();
        List<String> outside = outsideCalls.stream()
                .filter(c -> !OUTSIDE_CALLERS.contains(id(c.getTarget().resolveMember().orElseThrow()) + " <- " + c.getOriginOwner().getName()))
                .map(JavaMethodCall::getDescription).sorted().toList();
        assertThat(outside).isEmpty();
        assertThat(outsideCalls.stream().map(c -> id(c.getTarget().resolveMember().orElseThrow()) + " <- " + c.getOriginOwner().getName()).distinct())
                .as("no stale outside-caller entries").containsExactlyInAnyOrderElementsOf(OUTSIDE_CALLERS);
    }
}
