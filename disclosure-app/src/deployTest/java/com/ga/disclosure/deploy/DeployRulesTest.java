package com.ga.disclosure.deploy;

import com.ga.disclosure.app.config.ProdStartupGuard;
import com.ga.disclosure.workflow.secret.SecretName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.JsonNode;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.ga.disclosure.deploy.Manifests.docs;
import static com.ga.disclosure.deploy.Manifests.name;
import static com.ga.disclosure.deploy.Manifests.ofKind;
import static com.ga.disclosure.deploy.Manifests.pods;
import static com.ga.disclosure.deploy.Manifests.stream;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배포 린트 — 우리 규칙(Phase 8 ③, G4·G13). 렌더 결과를 본다.
 * <ul>
 *   <li>Q5 3중 분리: 공개 컨트롤러의 라우트에 {@code /internal}·내부 서비스·8081이 없고, 내부 컨트롤러는 모든 연결에 클라이언트 인증서, NetworkPolicy는
 *       8081을 내부 진입점 파드에만(주입 "공개 인그레스에 /internal"). 컨트롤러는 자기 네임스페이스만, 클러스터 범위 권한 0, 공개 서비스는 원 IP 보존.</li>
 *   <li>모든 라우트의 첫 미들웨어가 인증서 주체 헤더 지움(앱이 믿는 헤더 이름과 같은 이름), 공개 서명 API는 IP 한도·크기 한도.</li>
 *   <li>이미지는 digest로만, 제3자 이미지는 tools.lock의 줄 그대로. 저장소에 들인 사본(CRD·RBAC)은 tools.lock 해시와 같다.</li>
 *   <li>파드: 비루트·권한 상승 없음·읽기 전용 루트·능력 전부 버림, 우리 파드는 SA 토큰 자동 마운트 끔. 비밀 볼륨은 items(Secret 키 → 비밀 이름 경로)를
 *       빠짐없이. 마이그레이터 자격 증명은 마이그레이션 Job에만. 렌더 결과에 Secret 0(값은 저장소에 없다).</li>
 *   <li>운영 기동 가드를 각 파드가 받을 환경(ConfigMap·ESO 대상 키·컨테이너 env)으로 돌려 빠진·금지 키 0.</li>
 * </ul>
 */
class DeployRulesTest {

    static final List<String> OVERLAYS = Manifests.OVERLAYS;
    static final String PUBLIC_CLASS = "ga-public";
    static final String INTERNAL_CLASS = "ga-internal";
    static final String STRIP = "strip-client-cert";

    static String ingressClass(JsonNode route) {
        return route.path("metadata").path("annotations").path("kubernetes.io/ingress.class").asString("");
    }

    static String namespace(JsonNode doc) {
        return doc.path("metadata").path("namespace").asString("");
    }

    /** 라우트의 서비스 → 앱 쪽 대상(같은 네임스페이스의 ExternalName 서비스가 가리키는 {@code <서비스>.ga-disclosure.svc.cluster.local}:포트). */
    static String target(List<JsonNode> docs, JsonNode route, JsonNode service) {
        JsonNode svc = ofKind(docs, "Service").filter(d -> namespace(d).equals(namespace(route)) && name(d).equals(service.path("name").asString()))
                .findFirst().orElse(null);
        if (svc == null || !svc.path("spec").path("type").asString("").equals("ExternalName")) {
            return "not an ExternalName service in " + namespace(route) + ": " + service.path("name").asString();
        }
        return svc.path("spec").path("externalName").asString() + ":" + service.path("port").asString();
    }

    /**
     * Q5 인그레스 겹 + 9b 커밋 보안 검토 셋: 공개 컨트롤러(ga-ingress)의 라우트는 앱 8080·웹만, 내부 컨트롤러(ga-ingress-internal)는 /internal → 앱 8081만.
     * 컨트롤러마다 자기 네임스페이스만 지켜보고, 내부 네임스페이스의 기본 TLS 옵션이 mTLS(라우트별 옵션은 SNI로 골라져 Host 규칙 없는 라우트에서 빠질
     * 수 있었다). 평문 진입점 0.
     */
    @ParameterizedTest
    @FieldSource("OVERLAYS")
    void publicRoutesNeverReachTheInternalPortAndEveryInternalConnectionNeedsAClientCertificate(String overlay) {
        List<JsonNode> docs = docs(overlay);
        List<JsonNode> routes = ofKind(docs, "IngressRoute").toList();
        assertThat(routes).isNotEmpty();
        Map<String, String> namespaceOf = Map.of(PUBLIC_CLASS, "ga-ingress", INTERNAL_CLASS, "ga-ingress-internal");
        List<String> bad = new ArrayList<>();
        for (JsonNode r : routes) {
            String cls = ingressClass(r);
            List<String> entryPoints = Manifests.strings(r.path("spec").path("entryPoints"));
            if (!namespace(r).equals(namespaceOf.getOrDefault(cls, "?"))) {
                bad.add(name(r) + ": class '" + cls + "' in namespace " + namespace(r));
                continue;
            }
            boolean pub = cls.equals(PUBLIC_CLASS);
            if (!entryPoints.equals(List.of(pub ? "websecure" : "internal"))) {
                bad.add(name(r) + ": entry points " + entryPoints);
            }
            for (JsonNode rule : r.path("spec").path("routes")) {
                String match = rule.path("match").asString();
                if (pub ? match.toLowerCase(java.util.Locale.ROOT).contains("internal") : !match.equals("PathPrefix(`/internal/`)")) {
                    bad.add(name(r) + ": match " + match);
                }
                for (JsonNode s : rule.path("services")) {
                    String t = target(docs, r, s);
                    Set<String> allowed = pub ? Set.of("ga-app.ga-disclosure.svc.cluster.local:8080", "ga-web.ga-disclosure.svc.cluster.local:8080")
                            : Set.of("ga-app-internal.ga-disclosure.svc.cluster.local:8081");
                    if (!allowed.contains(t)) {
                        bad.add(name(r) + ": service " + t);
                    }
                }
            }
            if (r.path("spec").path("tls").path("secretName").asString("").isEmpty() || r.path("spec").path("tls").has("options")) {
                bad.add(name(r) + ": TLS secret missing or a per-route TLS option (the namespace default decides)");
            }
        }
        // 공개 네임스페이스에 내부 포트로 가는 서비스 0
        ofKind(docs, "Service").filter(d -> namespace(d).equals("ga-ingress")).forEach(d -> {
            if (d.path("spec").path("externalName").asString("").startsWith("ga-app-internal.") || stream(d.path("spec").path("ports")).anyMatch(pt -> pt.path("port").asInt() == 8081)) {
                bad.add("ga-ingress/" + name(d) + ": reaches the internal port");
            }
        });
        // 내부 네임스페이스의 TLS 옵션은 default 하나 = 클라이언트 인증서 필수
        List<JsonNode> internalOptions = ofKind(docs, "TLSOption").filter(d -> namespace(d).equals("ga-ingress-internal")).toList();
        if (internalOptions.size() != 1 || !name(internalOptions.getFirst()).equals("default")
                || !internalOptions.getFirst().path("spec").path("clientAuth").path("clientAuthType").asString().equals("RequireAndVerifyClientCert")
                || internalOptions.getFirst().path("spec").path("clientAuth").path("secretNames").isEmpty()) {
            bad.add("ga-ingress-internal: the default TLS option must require a verified client certificate");
        }
        // 컨트롤러: 자기 네임스페이스·자기 클래스만, 모든 진입점 TLS(평문 없음)
        for (JsonNode d : ofKind(docs, "Deployment").filter(d -> name(d).startsWith("traefik-")).toList()) {
            List<String> args = Manifests.strings(d.path("spec").path("template").path("spec").path("containers").get(0).path("args"));
            String cls = name(d).equals("traefik-public") ? PUBLIC_CLASS : INTERNAL_CLASS;
            if (!namespace(d).equals(namespaceOf.get(cls)) || !args.contains("--providers.kubernetescrd.ingressclass=" + cls)
                    || !args.contains("--providers.kubernetescrd.namespaces=" + namespaceOf.get(cls))) {
                bad.add(name(d) + ": must watch only " + namespaceOf.get(cls) + " and class " + cls);
            }
            List<String> entries = args.stream().filter(a -> a.matches("--entrypoints\\.[a-z]+\\.address=.*")).map(a -> a.split("\\.")[1]).filter(e -> !e.equals("ping")).toList();
            for (String e : entries) {
                if (!args.contains("--entrypoints." + e + ".http.tls=true")) {
                    bad.add(name(d) + ": entry point " + e + " without TLS");
                }
            }
        }
        assertThat(bad).isEmpty();
    }

    /** 9b 커밋 보안 검토: 클러스터 범위 권한 0, 바인딩은 자기 네임스페이스의 SA에만, 앱 네임스페이스에는 바인딩 0(인그레스가 앱 Secret을 읽을 길 없음). */
    @ParameterizedTest
    @FieldSource("OVERLAYS")
    void noControllerCanReadTheAppNamespaceSecrets(String overlay) {
        List<JsonNode> docs = docs(overlay);
        assertThat(ofKind(docs, "ClusterRole").map(Manifests::name)).isEmpty();
        assertThat(ofKind(docs, "ClusterRoleBinding").map(Manifests::name)).isEmpty();
        List<String> bad = new ArrayList<>();
        ofKind(docs, "RoleBinding").forEach(b -> {
            if (namespace(b).equals("ga-disclosure")) {
                bad.add(name(b) + ": binding in the app namespace");
            }
            stream(b.path("subjects")).filter(sub -> !sub.path("namespace").asString("").equals(namespace(b)))
                    .forEach(sub -> bad.add(name(b) + ": subject from " + sub.path("namespace").asString()));
        });
        assertThat(bad).isEmpty();
    }

    /** 9b 커밋 보안 검토: IP 단위 한도의 키는 연결의 원격 주소 — 공개 서비스는 원 IP를 보존해야 한다(Cluster 정책이면 노드 IP로 SNAT). */
    @ParameterizedTest
    @FieldSource("OVERLAYS")
    void thePublicEntryPointKeepsTheClientAddress(String overlay) {
        JsonNode svc = ofKind(docs(overlay), "Service").filter(d -> namespace(d).equals("ga-ingress") && name(d).equals("traefik-public")).findFirst().orElseThrow();
        assertThat(svc.path("spec").path("type").asString()).isIn("NodePort", "LoadBalancer");
        assertThat(svc.path("spec").path("externalTrafficPolicy").asString("")).isEqualTo("Local");
        JsonNode rate = ofKind(docs(overlay), "Middleware").filter(Manifests.named("sign-rate-limit")).findFirst().orElseThrow().path("spec").path("rateLimit");
        assertThat(rate.path("sourceCriterion").path("ipStrategy").path("depth").asInt(-1)).as("X-Forwarded-For is not trusted").isZero();
    }

    @ParameterizedTest
    @FieldSource("OVERLAYS")
    void networkPoliciesAdmitEachAppPortFromItsEntryPointOnly(String overlay) {
        List<JsonNode> policies = ofKind(docs(overlay), "NetworkPolicy").toList();
        assertThat(policies).anySatisfy(p -> {
            assertThat(p.path("spec").path("podSelector").isEmpty()).isTrue();
            assertThat(Manifests.strings(p.path("spec").path("policyTypes"))).contains("Ingress");
            assertThat(p.path("spec").has("ingress")).isFalse();
        });
        Map<String, Set<String>> sourcesByPort = new LinkedHashMap<>();
        List<String> bad = new ArrayList<>();
        for (JsonNode p : policies) {
            String target = p.path("spec").path("podSelector").path("matchLabels").path("app.kubernetes.io/name").asString("");
            if (!target.equals("ga-app") && !target.equals("ga-web")) {
                continue;
            }
            for (JsonNode rule : p.path("spec").path("ingress")) {
                if (rule.path("ports").isEmpty() || rule.path("from").isEmpty()) {
                    bad.add(name(p) + ": rule without ports or sources (allows every port or every source)");
                }
                for (JsonNode port : rule.path("ports")) {
                    for (JsonNode from : rule.path("from")) {
                        String source = from.path("namespaceSelector").path("matchLabels").toString() + from.path("podSelector").path("matchLabels").toString()
                                + from.path("ipBlock").toString();
                        sourcesByPort.computeIfAbsent(target + ":" + port.path("port").asString(), k -> new TreeSet<>()).add(source);
                    }
                }
            }
        }
        String publicIngress = "{\"kubernetes.io/metadata.name\":\"ga-ingress\"}{\"app.kubernetes.io/name\":\"traefik-public\"}";
        String internalIngress = "{\"kubernetes.io/metadata.name\":\"ga-ingress-internal\"}{\"app.kubernetes.io/name\":\"traefik-internal\"}";
        assertThat(bad).isEmpty();
        assertThat(sourcesByPort).containsEntry("ga-app:8080", Set.of(publicIngress)).containsEntry("ga-app:8081", Set.of(internalIngress))
                .containsEntry("ga-web:8080", Set.of(publicIngress)).containsOnlyKeys("ga-app:8080", "ga-app:8081", "ga-app:8082", "ga-web:8080");
        assertThat(sourcesByPort.get("ga-app:8082")).noneMatch(s -> s.contains("traefik"));
    }

    @ParameterizedTest
    @FieldSource("OVERLAYS")
    void everyRouteFirstStripsTheHeaderTheAppTrustsAndTheSignApiIsLimited(String overlay) {
        List<JsonNode> docs = docs(overlay);
        String header = ofKind(docs, "ConfigMap").filter(c -> name(c).startsWith("ga-app-config")).findFirst().orElseThrow()
                .path("data").path("GA_CLIENT_CERT_SUBJECT_HEADER").asString("");
        assertThat(header).isNotBlank();
        List<String> bad = new ArrayList<>();
        for (JsonNode r : ofKind(docs, "IngressRoute").toList()) {
            // 미들웨어는 라우트의 네임스페이스에서 찾는다 — 그 네임스페이스의 지움 미들웨어가 앱이 믿는 헤더 이름을 지워야 한다
            JsonNode set = ofKind(docs, "Middleware").filter(m -> namespace(m).equals(namespace(r)) && name(m).equals(STRIP)).findFirst()
                    .map(m -> m.path("spec").path("headers").path("customRequestHeaders")).orElse(null);
            if (set == null || !set.has(header) || !set.path(header).asString().isEmpty()) {
                bad.add(namespace(r) + ": no " + STRIP + " middleware that removes " + header);
            }
            for (JsonNode rule : r.path("spec").path("routes")) {
                List<String> mws = stream(rule.path("middlewares")).map(m -> m.path("name").asString()).toList();
                if (mws.isEmpty() || !mws.getFirst().equals(STRIP)) {
                    bad.add(name(r) + " " + rule.path("match").asString() + ": first middleware " + mws);
                }
                if (rule.path("match").asString().contains("/public/") && !mws.containsAll(List.of("sign-rate-limit", "sign-body-limit"))) {
                    bad.add(name(r) + ": /public without IP and size limits " + mws);
                }
            }
        }
        assertThat(bad).isEmpty();
        JsonNode rate = ofKind(docs, "Middleware").filter(Manifests.named("sign-rate-limit")).findFirst().orElseThrow().path("spec").path("rateLimit");
        assertThat(rate.path("sourceCriterion").has("ipStrategy")).isTrue();
        assertThat(rate.path("average").asInt()).isPositive();
        assertThat(ofKind(docs, "Middleware").filter(Manifests.named("sign-body-limit")).findFirst().orElseThrow()
                .path("spec").path("buffering").path("maxRequestBodyBytes").asLong()).isPositive();
    }

    @ParameterizedTest
    @FieldSource("OVERLAYS")
    void imagesArePinnedByDigestAndThirdPartyImagesAreTheLockedOnes(String overlay) {
        Set<String> locked = new TreeSet<>(ToolsLock.rows().stream().filter(ToolsLock::isImage).map(ToolsLock::ref).toList());
        List<String> bad = new ArrayList<>();
        for (Manifests.Pod pod : pods(docs(overlay))) {
            pod.containers().forEach(c -> {
                String image = c.path("image").asString();
                if (!image.matches("[^@]+@sha256:[0-9a-f]{64}")) {
                    bad.add(pod.label() + ": " + image + " is not pinned by digest");
                } else if (!image.contains("/ga-disclosure/") && !locked.contains(image)) {
                    bad.add(pod.label() + ": " + image + " is not in deploy/tools.lock");
                }
            });
        }
        assertThat(bad).isEmpty();
    }

    @org.junit.jupiter.api.Test
    void vendoredFilesMatchTheirLockedHashes() throws Exception {
        List<ToolsLock> files = ToolsLock.rows().stream().filter(ToolsLock::isRepoFile).toList();
        // 양방향: 저장소에 들인 상류 사본(components/*/traefik·crds) = tools.lock의 파일 줄 — 지운 사본의 줄이 남아도, 줄 없는 사본이 있어도 실패
        java.util.Set<String> vendored = new TreeSet<>();
        try (var walk = java.nio.file.Files.walk(Manifests.repoRoot().resolve("deploy/components"))) {
            walk.filter(java.nio.file.Files::isRegularFile).map(f -> Manifests.repoRoot().relativize(f).toString())
                    .filter(f -> f.contains("/traefik/") || f.contains("/crds/")).forEach(vendored::add);
        }
        assertThat(files).extracting(ToolsLock::ref).containsExactlyInAnyOrderElementsOf(vendored);
        assertThat(vendored).hasSize(3);
        for (ToolsLock f : files) {
            byte[] bytes = java.nio.file.Files.readAllBytes(Manifests.repoRoot().resolve(f.ref()));
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))).as(f.ref()).isEqualTo(f.sha256());
        }
    }

    @ParameterizedTest
    @FieldSource("OVERLAYS")
    void podsAreRestrictedAndOursCarryNoServiceAccountToken(String overlay) {
        List<String> bad = new ArrayList<>();
        for (Manifests.Pod pod : pods(docs(overlay))) {
            JsonNode sc = pod.spec().path("securityContext");
            if (!sc.path("runAsNonRoot").asBoolean(false) || !sc.path("seccompProfile").path("type").asString("").equals("RuntimeDefault")) {
                bad.add(pod.label() + ": pod security context");
            }
            boolean ours = !name(pod.owner()).startsWith("traefik-");
            if (ours && pod.spec().path("automountServiceAccountToken").asBoolean(true)) {
                bad.add(pod.label() + ": service account token mounted");
            }
            pod.containers().forEach(c -> {
                JsonNode csc = c.path("securityContext");
                if (csc.path("allowPrivilegeEscalation").asBoolean(true) || !csc.path("readOnlyRootFilesystem").asBoolean(false)
                        || !Manifests.strings(csc.path("capabilities").path("drop")).contains("ALL")) {
                    bad.add(pod.label() + "/" + c.path("name").asString() + ": container security context");
                }
            });
        }
        assertThat(bad).isEmpty();
    }

    @ParameterizedTest
    @FieldSource("OVERLAYS")
    void secretVolumesMapEveryKeyToASecretNameAndOnlyTheMigrationJobGetsTheMigrator(String overlay) {
        List<JsonNode> docs = docs(overlay);
        assertThat(ofKind(docs, "Secret")).as("no secret values in the repository").isEmpty();
        List<String> bad = new ArrayList<>();
        int volumes = 0;
        for (Manifests.Pod pod : pods(docs)) {
            for (JsonNode v : pod.spec().path("volumes")) {
                if (!v.path("secret").path("secretName").asString("").equals("ga-secrets")) {
                    continue;
                }
                volumes++;
                JsonNode items = v.path("secret").path("items");
                if (items.isEmpty()) {
                    bad.add(pod.label() + ": ga-secrets without items");
                }
                for (JsonNode item : items) {
                    String path = item.path("path").asString();
                    try {
                        SecretName.of(path);
                    } catch (IllegalArgumentException e) {
                        bad.add(pod.label() + ": " + path + " is not a secret name");
                    }
                    if (!item.path("key").asString().equals(path.replace('/', '.'))) {
                        bad.add(pod.label() + ": key " + item.path("key").asString() + " for " + path);
                    }
                }
            }
            boolean migrator = pod.containers().anyMatch(c -> stream(c.path("envFrom")).anyMatch(e -> e.path("secretRef").path("name").asString("").equals("ga-db-migrator")));
            if (migrator != (pod.owner().path("kind").asString().equals("Job") && name(pod.owner()).equals("ga-db-migrate"))) {
                bad.add(pod.label() + ": migrator credentials " + (migrator ? "mounted" : "missing"));
            }
        }
        assertThat(volumes).as("app and every CronJob mount ga-secrets").isEqualTo(16);
        assertThat(bad).isEmpty();
    }

    /** 운영 기동 가드를 파드가 받을 환경으로: ConfigMap(envFrom)·Secret(envFrom — ESO 대상 키, 값은 자리 값)·컨테이너 env. 비밀 키 이름은 ConfigMap에 없다. */
    @ParameterizedTest
    @FieldSource("OVERLAYS")
    void theProdGuardPassesOnTheEnvironmentEveryAppPodReceives(String overlay) {
        List<JsonNode> docs = docs(overlay);
        Map<String, Map<String, String>> sources = new LinkedHashMap<>();
        ofKind(docs, "ConfigMap").forEach(c -> {
            Map<String, String> data = new LinkedHashMap<>();
            c.path("data").properties().forEach(e -> data.put(e.getKey(), e.getValue().asString()));
            sources.put("configMap:" + name(c), data);
            assertThat(data.keySet()).as(name(c)).noneMatch(k -> k.contains("PASSWORD") || k.contains("SECRET") || k.contains("ACCESS_KEY"));
        });
        ofKind(docs, "ExternalSecret").forEach(es -> {
            Map<String, String> data = new LinkedHashMap<>();
            stream(es.path("spec").path("data")).forEach(d -> data.put(d.path("secretKey").asString(), "deploy-lint-" + d.path("secretKey").asString()));
            sources.put("secret:" + es.path("spec").path("target").path("name").asString(), data);
        });
        int checked = 0;
        for (Manifests.Pod pod : pods(docs)) {
            JsonNode c = pod.spec().path("containers").get(0);
            if (!c.path("image").asString().contains("/ga-disclosure/app") || pod.owner().path("kind").asString().equals("Job")) {
                continue;
            }
            Map<String, Object> env = new LinkedHashMap<>();
            for (JsonNode from : c.path("envFrom")) {
                String key = from.has("configMapRef") ? "configMap:" + from.path("configMapRef").path("name").asString() : "secret:" + from.path("secretRef").path("name").asString();
                assertThat(sources).as(pod.label() + " envFrom").containsKey(key);
                env.putAll(sources.get(key));
            }
            for (JsonNode e : c.path("env")) {
                if (e.has("value")) {
                    env.put(e.path("name").asString(), e.path("value").asString());
                }
            }
            StandardEnvironment environment = new StandardEnvironment();
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            environment.getPropertySources().addLast(new MapPropertySource("pod", env));
            environment.getPropertySources().addLast(new PropertiesPropertySource("application-prod", yaml("application-prod.yaml")));
            environment.getPropertySources().addLast(new PropertiesPropertySource("application", yaml("application.yaml")));
            environment.setActiveProfiles("prod");
            assertThat(env).as(pod.label()).containsEntry("SPRING_PROFILES_ACTIVE", "prod");
            assertThat(ProdStartupGuard.problems(environment)).as(pod.label()).isEmpty();
            checked++;
        }
        assertThat(checked).as("ga-app and every CronJob").isEqualTo(16);
    }

    static java.util.Properties yaml(String file) {
        YamlPropertiesFactoryBean y = new YamlPropertiesFactoryBean();
        y.setResources(new FileSystemResource(Manifests.repoRoot().resolve("disclosure-app/src/main/resources/" + file)));
        return y.getObject();
    }
}
