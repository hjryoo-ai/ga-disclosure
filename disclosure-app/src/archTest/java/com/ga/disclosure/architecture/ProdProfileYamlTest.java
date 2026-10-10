package com.ga.disclosure.architecture;

import com.ga.disclosure.app.config.ProdStartupGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3 "필수 키 기본값 0"(8 계획 ③): {@code application-prod.yaml}이 운영 필수 키를 전부 <b>기본값 없는</b> 자리표시 {@code ${NAME}}으로 덮는다 —
 * {@code ${NAME:기본값}}이면 운영이 로컬 허구 자격 증명·예시 URL로 조용히 뜰 수 있다. 가드의 목록과 이 파일이 양방향으로 맞는다.
 */
class ProdProfileYamlTest {

    private static final Path PROD = Path.of(System.getProperty("ga.repoRoot"), "disclosure-app/src/main/resources/application-prod.yaml");

    static Properties prod() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(PROD));
        return yaml.getObject();
    }

    @Test
    void everyRequiredKeyIsAPlaceholderWithoutDefault() {
        Properties p = prod();
        List<String> bad = new ArrayList<>();
        for (String key : ProdStartupGuard.REQUIRED) {
            String value = p.getProperty(key);
            if (value == null || !value.matches("\\$\\{[A-Z][A-Z0-9_]*}")) {
                bad.add(key + "=" + value);
            }
        }
        assertThat(bad).as("application-prod.yaml의 필수 키는 ${NAME}(기본값 없음)").isEmpty();
    }

    /** 자리표시 값을 가진 키는 전부 필수 목록에 있다(마이그레이터 키는 Job 전용 — 앱 가드 밖이라 예외로 명시). */
    @Test
    void everyPlaceholderInTheProdFileIsGuarded() {
        Properties p = prod();
        List<String> unguarded = p.stringPropertyNames().stream().sorted()
                .filter(k -> p.getProperty(k).contains("${"))
                .filter(k -> !ProdStartupGuard.REQUIRED.contains(k) && !k.startsWith("ga.migrator."))
                .toList();
        assertThat(unguarded).isEmpty();
        assertThat(p.stringPropertyNames()).contains("ga.migrator.url", "ga.migrator.username", "ga.migrator.password");
        assertThat(p.getProperty("ga.migrator.password")).matches("\\$\\{[A-Z][A-Z0-9_]*}");
    }

    /** 스캔이 일하는지: 기본값을 단 자리표시는 잡힌다(공회전 방지). */
    @Test
    void theScanRejectsAPlaceholderWithADefault() {
        assertThat("${GA_TSA_URL:https://tsa.example}").doesNotMatch("\\$\\{[A-Z][A-Z0-9_]*}");
        assertThat(prod()).isNotEmpty();
    }
}
