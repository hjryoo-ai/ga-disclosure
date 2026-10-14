package com.ga.disclosure.infra;

import com.ga.disclosure.infra.testing.PostgresHarness;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 ③: 외부 이미지는 태그가 아니라 다중 아키텍처 인덱스 digest로 고정하고 쓰는 곳 전부에서 같다. PostgreSQL(하네스·버전 카탈로그·compose·E2E)은
 * SeaweedFS({@code SeaweedArtifactStoreIT#imageDigestIsPinnedTheSameEverywhere})와 같은 방식으로, 배포 이미지 두 Dockerfile의 베이스도 digest다
 * (kind 매니페스트는 deploy 린트가 같은 값을 본다).
 */
class ImageDigestPinIT {

    private static final Path ROOT = Path.of(System.getProperty("ga.repoRoot"));

    @Test
    void postgresIsPinnedByDigestTheSameEverywhere() throws Exception {
        assertThat(PostgresHarness.IMAGE).matches("postgres@sha256:[0-9a-f]{64}");
        assertThat(Files.readString(ROOT.resolve("gradle/libs.versions.toml"))).contains("postgres-image = \"" + PostgresHarness.IMAGE + "\"");
        assertThat(Files.readString(ROOT.resolve("docker-compose.yml"))).contains("image: " + PostgresHarness.IMAGE);
        assertThat(Files.readString(ROOT.resolve("disclosure-web/e2e/env.mjs"))).contains("const PG_IMAGE = '" + PostgresHarness.IMAGE + "'");
        // 태그로 남은 곳이 없다
        for (String file : new String[] {"docker-compose.yml", "disclosure-web/e2e/env.mjs", "gradle/libs.versions.toml"}) {
            assertThat(Files.readString(ROOT.resolve(file))).as(file).doesNotContainPattern("postgres:1[0-9]");
        }
    }

    @Test
    void bothDeployImagesBuildOnADigestPinnedBase() throws Exception {
        Pattern base = Pattern.compile("(?m)^ARG BASE=([^\\s@]+:[^\\s@]+)@sha256:[0-9a-f]{64}$");
        for (String dockerfile : new String[] {"deploy/images/app.Dockerfile", "deploy/images/web.Dockerfile"}) {
            String text = Files.readString(ROOT.resolve(dockerfile));
            Matcher m = base.matcher(text);
            assertThat(m.find()).as(dockerfile).isTrue();
            assertThat(text).as(dockerfile + ": every FROM is the pinned base or an earlier stage").doesNotContainPattern("(?m)^FROM (?!\\$\\{BASE\\})");
        }
    }
}
