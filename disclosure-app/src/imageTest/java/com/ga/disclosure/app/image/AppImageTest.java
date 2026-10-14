package com.ga.disclosure.app.image;

import com.ga.disclosure.infra.testing.PostgresHarness;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * app 이미지(Phase 8 ③): 비루트(UID 10001)·읽기 전용 루트 FS(쓰기는 /tmp tmpfs만)로 마이그레이션 Job의 형태 — {@code db migrate} — 가 빈 데이터베이스를
 * 배포물의 최고 버전까지 올린다. 이미지 안의 jar가 그 목록의 출처다(앱의 스키마 버전 가드와 같은 jar). 접속은 호스트에 매핑된 하네스 포트로.
 */
class AppImageTest {

    @Test
    void theImageRunsAsNonRootWithAReadOnlyRootAndMigratesAnEmptyDatabase() {
        PostgresHarness db = PostgresHarness.get();
        String name = "img_" + Long.toHexString(System.nanoTime());
        db.emptyDatabase(name);
        URI jdbc = URI.create(db.jdbcUrl().substring("jdbc:".length()));
        String url = "jdbc:postgresql://host.docker.internal:" + jdbc.getPort() + "/" + name;

        String uid = Docker.run("run", "--rm", "--entrypoint", "id", Docker.APP, "-u").trim();
        assertThat(uid).isEqualTo("10001");

        String out = Docker.run("run", "--rm", "--read-only", "--tmpfs", "/tmp", "--add-host", "host.docker.internal:host-gateway",
                Docker.APP, "db", "migrate", "--ga.migrator.url=" + url);
        String bundled = latest();
        assertThat(out).contains("DB_MIGRATE applied=" + bundled + " version=" + bundled);
        // 두 번째는 적용 0(멱등)
        assertThat(Docker.run("run", "--rm", "--read-only", "--tmpfs", "/tmp", "--add-host", "host.docker.internal:host-gateway",
                Docker.APP, "db", "migrate", "--ga.migrator.url=" + url)).contains("DB_MIGRATE applied=0 version=" + bundled);
    }

    /** 저장소의 최고 V* 번호(이미지 안 jar와 같은 원천). */
    static String latest() {
        try (var files = java.nio.file.Files.list(Docker.repoRoot().resolve("disclosure-infra/src/main/resources/db/migration"))) {
            return Integer.toString(files.map(p -> p.getFileName().toString()).filter(n -> n.matches("V[0-9]+__.*\\.sql"))
                    .mapToInt(n -> Integer.parseInt(n.substring(1, n.indexOf("__")))).max().orElseThrow());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
