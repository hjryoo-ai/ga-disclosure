package com.ga.disclosure.app.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 운영 기동 가드(Phase 8, 8 계획 ③, G3): {@code prod} 프로파일이면 <b>어떤 빈보다 먼저</b>(스키마 버전 가드보다도) 닫힌 목록을 검사한다 — 필수 키가 없거나
 * 비었거나(기본값 없는 자리표시가 풀리지 않음 포함), 운영에서 금지된 값(스텁 엔진·스텁 TSA·환경변수 비밀·버킷 생성·정적 공개키·예시 서명 링크)이거나
 * {@code demo} 프로파일이 함께 켜져 있으면 멈춘다.
 * 문장에는 <b>키 이름만</b> 싣는다(값·기본값·경로 0). 6B의 {@code ClientCertHeaderGuard}를 이 목록으로 흡수했다.
 */
public final class ProdStartupGuard implements BeanFactoryPostProcessor, EnvironmentAware, PriorityOrdered {

    /** 운영 필수 키(비어 있으면 안 된다). 값의 출처는 {@code application-prod.yaml}의 기본값 없는 자리표시 → 배포 환경. */
    public static final List<String> REQUIRED = List.of(
            "spring.datasource.url", "spring.datasource.username", "spring.datasource.password",
            "ga.health.url", "ga.health.username", "ga.health.password",
            "ga.tenant-directory.url", "ga.tenant-directory.username", "ga.tenant-directory.password",
            "ga.job-lock.url", "ga.job-lock.username", "ga.job-lock.password",
            "ga.secrets.dir",
            "ga.storage.s3.endpoint", "ga.storage.s3.region", "ga.storage.s3.bucket", "ga.storage.s3.access-key-id", "ga.storage.s3.secret-access-key",
            "ga.api.jwt.issuer", "ga.api.jwt.audience", "ga.api.jwt.jwk-set-uri",
            "ga.api.client-cert.subject-header",
            "ga.public-sign.min-response-millis",
            "ga.sign.link-base-url",
            "ga.tsa.url");

    /**
     * 운영에서 이 값이면 안 되는 키(키 → 금지 값, 대소문자 무시). 빈 값은 "설정돼 있으면 안 됨". Phase 8 9단계: 로컬 기본 자격 증명(init-roles.sql·
     * application.yaml의 {@code *_local_only}·허구 S3 키)은 compose·하네스 전용 — 운영 오버레이가 그 값을 쓰면 기동하지 않는다(문장에는 키 이름만).
     */
    public static final Map<String, List<String>> FORBIDDEN = Map.ofEntries(
            Map.entry("ga.engine.mode", List.of("stub")),
            Map.entry("ga.tsa.mode", List.of("stub")),
            Map.entry("ga.secrets.source", List.of("env")),
            Map.entry("ga.storage.s3.create-bucket", List.of("true")),
            Map.entry("ga.api.jwt.public-key-location", List.of("")),
            Map.entry("ga.sign.link-base-url", List.of("https://sign.example.invalid/s#")),
            Map.entry("spring.datasource.password", List.of("app_local_only")),
            Map.entry("ga.health.password", List.of("health_local_only")),
            Map.entry("ga.tenant-directory.password", List.of("operator_local_only")),
            Map.entry("ga.job-lock.password", List.of("job_lock_local_only")),
            Map.entry("ga.migrator.password", List.of("migrator_local_only")),
            Map.entry("ga.storage.s3.access-key-id", List.of("ga-local-access")),
            Map.entry("ga.storage.s3.secret-access-key", List.of("ga-local-secret-not-a-real-key")));

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            List<String> problems = problems(environment);
            if (!problems.isEmpty()) {
                throw new IllegalStateException("prod profile cannot start — missing or forbidden settings: " + String.join(", ", problems));
            }
        }
    }

    /** 문제가 있는 키 이름 목록(정렬: 프로파일 → 필수 키 순서 → 금지 키 이름순). 값은 싣지 않는다. */
    public static List<String> problems(Environment env) {
        Objects.requireNonNull(env, "env");
        List<String> out = new ArrayList<>();
        // 데모 프로파일(데모 OIDC 발급자·화면 서빙·시계 오프셋)은 운영과 함께 켜지지 않는다
        if (env.acceptsProfiles(Profiles.of("demo"))) {
            out.add("spring.profiles.active (demo not allowed with prod)");
        }
        for (String key : REQUIRED) {
            String value = read(env, key);
            if (value == null || value.isBlank()) {
                out.add(key);
            }
        }
        FORBIDDEN.keySet().stream().sorted().forEach(key -> {
            String value = read(env, key);
            if (value == null || out.contains(key)) {
                return;
            }
            for (String bad : FORBIDDEN.get(key)) {
                if (bad.isEmpty() ? !value.isBlank() : bad.equalsIgnoreCase(value.strip())) {
                    out.add(key + " (not allowed in prod)");
                    return;
                }
            }
        });
        return List.copyOf(out);
    }

    /** 풀리지 않는 자리표시(기본값 없는 {@code ${X}}의 X가 없음)는 없는 것으로 — 예외 문장(자리표시 원문)을 싣지 않는다. */
    private static String read(Environment env, String key) {
        try {
            return env.getProperty(key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
