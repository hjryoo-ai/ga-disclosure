package com.ga.disclosure.app.image;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** docker CLI 호출(이미지 시험 — 빌드·save·일회 실행). 실패하면 출력과 함께 예외. */
final class Docker {

    static final String APP = System.getProperty("ga.image.app");
    static final String WEB = System.getProperty("ga.image.web");

    private Docker() {
    }

    static Path repoRoot() {
        return Path.of(System.getProperty("ga.repoRoot"));
    }

    static String run(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                throw new IllegalStateException("docker " + args[0] + " failed (" + p.exitValue() + "):\n" + out);
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static Path save(String image, Path dir) {
        Path out = dir.resolve(image.replaceAll("[^A-Za-z0-9]+", "_") + ".tar");
        run("save", "-o", out.toString(), image);
        return out;
    }
}
