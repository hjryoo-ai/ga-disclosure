package com.ga.disclosure.architecture;

import com.ga.disclosure.workflow.authz.UseCaseEntry;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6A 계획 §9.2 {@code ApiLayerRulesTest} — 컨트롤러에 업무 규칙이 없다(G11). 스프링 타입은 이름으로만 본다(archTest 컴파일 경로에 웹 모듈이 없다).
 * <ol type="a">
 *   <li>{@code @RestController}·{@code @Controller}는 {@code api.rest}({@code /api/v1})·{@code api.internal}({@code /internal/v1})·
 *       {@code api.publicsign}({@code /public/v1})에만 있고, 클래스 {@code @RequestMapping} 접두가 패키지와 맞다.</li>
 *   <li>컨트롤러가 의존하는 것: 같은 패키지, {@code api.dto}·{@code api.mapper}·{@code api.error}, {@code java..}, 스프링 웹·HTTP, Jackson 노드(작업
 *       매개변수), 그리고 workflow에서는 유스케이스 클래스와 {@link UseCaseEntry} 시그니처에 나오는 타입뿐. 저장소·infra·rules·domain·audit·seal·sign은 금지 —
 *       값 타입 변환은 매퍼가 한다.</li>
 *   <li>컨트롤러의 매핑 메서드는 유스케이스 메서드를 정확히 1번 부른다(분기·조합은 유스케이스로).</li>
 *   <li>{@code Jwt}에 닿는 클래스는 {@code TenantBindingFilter}뿐이다(클레임은 {@code sub}·{@code tenant_id}만).</li>
 *   <li>{@code HttpServletRequest}의 원 URI·질의·헤더 읽기는 {@code api.security}에서만(로그·컨트롤러가 원문을 다루지 않게).</li>
 * </ol>
 */
class ApiLayerRulesTest {

    static final String P = "com.ga.disclosure.";
    static final String WORKFLOW = P + "workflow";
    static final String REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
    static final String CONTROLLER = "org.springframework.stereotype.Controller";
    static final String REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
    static final String JWT = "org.springframework.security.oauth2.jwt.Jwt";
    static final String TENANT_BINDING_FILTER = P + "api.security.TenantBindingFilter";

    /** 컨트롤러 패키지 → 클래스 매핑 접두(승인 Q15). */
    static final Map<String, String> CONTROLLER_PACKAGES = Map.of(
            P + "api.rest", "/api/v1",
            P + "api.internal", "/internal/v1",
            P + "api.publicsign", "/public/v1");

    static final List<String> ALLOWED_PACKAGES = List.of("java.", "org.springframework.web.", "org.springframework.http.", "tools.jackson.databind.",
            P + "api.dto.", P + "api.mapper.", P + "api.error.");

    static final Set<String> RAW_REQUEST_READS = Set.of("getQueryString", "getRequestURI", "getRequestURL", "getHeader", "getHeaders",
            "getHeaderNames");

    static JavaClasses classes;

    @BeforeAll
    static void importProductionClasses() {
        classes = new ClassFileImporter().withImportOption(ArchitectureRulesTest.PRODUCTION_ONLY).importPackages("com.ga");
    }

    static boolean isController(JavaClass c) {
        return c.isAnnotatedWith(REST_CONTROLLER) || c.isAnnotatedWith(CONTROLLER);
    }

    static List<JavaClass> controllers() {
        return classes.stream().filter(ApiLayerRulesTest::isController).toList();
    }

    static boolean isMapping(JavaMethod m) {
        return m.getAnnotations().stream().map(a -> a.getRawType().getName())
                .anyMatch(n -> n.startsWith("org.springframework.web.bind.annotation.") && n.endsWith("Mapping"));
    }

    static List<JavaMethod> entries() {
        return classes.stream().filter(c -> c.getPackageName().startsWith(WORKFLOW)).flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(UseCaseEntry.class)).toList();
    }

    static Set<String> useCaseClasses() {
        return entries().stream().map(m -> m.getOwner().getName()).collect(Collectors.toCollection(TreeSet::new));
    }

    /** 진입점 시그니처(인자·반환, 제네릭 인자 포함)에 나오는 workflow 타입과 그 바깥 클래스. workflow 밖 타입(TenantId 등)은 넣지 않는다 — 값 변환은 매퍼. */
    static Set<String> entrySignatureTypes() {
        Set<String> out = new TreeSet<>();
        for (JavaMethod m : entries()) {
            List<JavaType> types = new ArrayList<>(m.getParameterTypes());
            types.add(m.getReturnType());
            for (JavaType t : types) {
                t.getAllInvolvedRawTypes().forEach(raw -> {
                    for (JavaClass c = raw; c != null; c = c.getEnclosingClass().orElse(null)) {
                        if (c.getPackageName().startsWith(WORKFLOW)) {
                            out.add(c.getName());
                        }
                    }
                });
            }
        }
        return out;
    }

    @Test
    void controllersExistOnlyInPrefixPackagesAndMatchTheirPrefix() {
        List<JavaClass> controllers = controllers();
        assertThat(controllers).extracting(JavaClass::getName).contains(P + "api.rest.JobsController", P + "api.internal.InternalJobsController");
        List<String> violations = new ArrayList<>();
        for (JavaClass c : controllers) {
            String prefix = CONTROLLER_PACKAGES.get(c.getPackageName());
            if (prefix == null) {
                violations.add(c.getName() + " is a controller outside " + CONTROLLER_PACKAGES.keySet());
                continue;
            }
            List<String> paths = c.tryGetAnnotationOfType(REQUEST_MAPPING)
                    .map(a -> Stream.of("value", "path").flatMap(k -> Arrays.stream((Object[]) a.get(k).orElse(new Object[0]))).map(String::valueOf).toList())
                    .orElse(List.of());
            if (paths.isEmpty() || !paths.stream().allMatch(p -> p.equals(prefix) || p.startsWith(prefix + "/"))) {
                violations.add(c.getName() + " maps " + paths + " but its package requires " + prefix);
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void controllersDependOnlyOnEntriesDtosMappersAndWeb() {
        Set<String> workflowAllowed = new TreeSet<>(useCaseClasses());
        workflowAllowed.addAll(entrySignatureTypes());
        List<String> violations = new ArrayList<>();
        for (JavaClass c : controllers()) {
            for (Dependency d : c.getDirectDependenciesFromSelf()) {
                JavaClass target = d.getTargetClass().getBaseComponentType();
                String name = target.getName();
                if (target.isPrimitive() || name.startsWith(c.getPackageName() + ".") || ALLOWED_PACKAGES.stream().anyMatch(name::startsWith)
                        || workflowAllowed.contains(name)) {
                    continue;
                }
                violations.add(d.getDescription());
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void eachMappingMethodCallsExactlyOneUseCaseMethod() {
        Set<String> useCases = useCaseClasses();
        List<String> violations = new ArrayList<>();
        for (JavaClass c : controllers()) {
            for (JavaMethod m : c.getMethods()) {
                if (!isMapping(m)) {
                    continue;
                }
                List<String> calls = AuthorizationCoverageTest.withLambdas(m).stream().flatMap(x -> x.getMethodCallsFromSelf().stream())
                        .filter(call -> useCases.contains(call.getTargetOwner().getName())).map(JavaMethodCall::getName).toList();
                if (calls.size() != 1) {
                    violations.add(m.getFullName() + " calls use cases " + calls);
                }
            }
        }
        assertThat(violations).isEmpty();
        assertThat(controllers().stream().flatMap(c -> c.getMethods().stream()).filter(ApiLayerRulesTest::isMapping)).isNotEmpty();
    }

    @Test
    void onlyTheTenantBindingFilterTouchesTheJwt() {
        List<String> touching = classes.stream().filter(c -> !c.getName().equals(TENANT_BINDING_FILTER))
                .flatMap(c -> c.getDirectDependenciesFromSelf().stream())
                .filter(d -> d.getTargetClass().getName().equals(JWT)).map(Dependency::getDescription).sorted().toList();
        assertThat(touching).isEmpty();
        assertThat(classes.get(TENANT_BINDING_FILTER).getDirectDependenciesFromSelf()).anyMatch(d -> d.getTargetClass().getName().equals(JWT));
    }

    @Test
    void rawRequestUriQueryAndHeadersAreReadOnlyInApiSecurity() {
        List<String> violations = classes.stream().filter(c -> !c.getPackageName().startsWith(P + "api.security"))
                .flatMap(c -> c.getMethodCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner().isAssignableTo("jakarta.servlet.http.HttpServletRequest")
                        && RAW_REQUEST_READS.contains(call.getName()))
                .map(JavaMethodCall::getDescription).sorted().toList();
        assertThat(violations).isEmpty();
    }

    @Test
    void ruleInputsAreNotEmpty() {
        assertThat(useCaseClasses()).contains(WORKFLOW + ".job.JobRunner", WORKFLOW + ".job.JobQueryService");
        assertThat(entrySignatureTypes()).contains(WORKFLOW + ".authz.Caller", WORKFLOW + ".job.JobRecord")
                .allMatch(n -> n.startsWith(WORKFLOW + "."));
        assertThat(classes.stream().filter(c -> c.getModifiers().contains(JavaModifier.PUBLIC)).map(JavaClass::getName))
                .contains(TENANT_BINDING_FILTER);
    }
}
