package com.ga.disclosure.audit.tsa;

import org.bouncycastle.tsp.TimeStampToken;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G4: BouncyCastle은 Boot BOM 밖이라 카탈로그 + 락 파일로 고정한다(5 계획 §3). 카탈로그·락의 버전과 실제로 클래스패스에 올라온 jar가
 * 같은 1.86인지 확인한다 — 락이 어긋나면 Gradle이 해석 단계에서 실패하고, 이 테스트는 그 고정값이 무엇인지를 문서와 맞춘다.
 */
class BouncyCastlePinTest {

    static final String VERSION = "1.86";
    static final List<String> COORDINATES = List.of("bcpkix-jdk18on", "bcprov-jdk18on", "bcutil-jdk18on");

    @Test
    void catalogLockAndRuntimeAgreeOnTheApprovedVersion() throws Exception {
        Path root = Path.of(System.getProperty("ga.repoRoot"));
        assertThat(Files.readAllLines(root.resolve("gradle/libs.versions.toml")))
                .anyMatch(l -> l.startsWith("bouncycastle = \"" + VERSION + "\""));
        List<String> lock = Files.readAllLines(root.resolve("disclosure-audit/gradle.lockfile"));
        for (String artifact : COORDINATES) {
            assertThat(lock).as(artifact).anyMatch(l -> l.startsWith("org.bouncycastle:" + artifact + ":" + VERSION + "="));
            assertThat(lock).as(artifact).noneMatch(l -> l.startsWith("org.bouncycastle:" + artifact + ":") && !l.contains(":" + VERSION + "="));
        }
        String jar = TimeStampToken.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        assertThat(jar).endsWith("bcpkix-jdk18on-" + VERSION + ".jar");
    }
}
