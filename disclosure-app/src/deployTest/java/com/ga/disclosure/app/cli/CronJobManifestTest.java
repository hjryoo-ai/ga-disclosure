package com.ga.disclosure.app.cli;

import com.ga.disclosure.deploy.Manifests;
import com.ga.disclosure.workflow.job.JobKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CronJob = {@link JobKind} 전수 1:1(Phase 8 ③, G13 주입 "CronJob {@code Allow}"): 라벨 {@code ga/job-kind}로 양방향 대조, 모두 Asia/Seoul·{@code Forbid}·
 * 시작 기한·재시도 상한·실행 기한. 명령은 {@code jobs run <KIND>}이고, 처리기 표 밖 종류({@link JobCommands#DEDICATED} — CLI가 {@code jobs run}을 거부하는
 * 바로 그 목록)만 전용 명령이다. 이 패키지에 있는 이유: 그 목록을 다시 적지 않고 CLI의 것을 읽는다.
 */
class CronJobManifestTest {

    static final List<String> OVERLAYS = Manifests.OVERLAYS;

    /** 전용 명령의 앞부분(CLI 문서 문장이 아니라 실행 인자). */
    static final Map<JobKind, List<String>> DEDICATED_COMMANDS = Map.of(
            JobKind.ANCHOR, List.of("anchor", "run"),
            JobKind.KEK_REWRAP, List.of("crypto", "kek", "rewrap"),
            JobKind.CONTRACT_LINK_IMPORT, List.of("contract-links", "import"));

    @ParameterizedTest
    @FieldSource("OVERLAYS")
    void everyJobKindHasExactlyOneCronJobThatCannotOverlapItself(String overlay) {
        assertThat(DEDICATED_COMMANDS.keySet()).isEqualTo(JobCommands.DEDICATED.keySet());
        Map<String, JsonNode> byKind = new TreeMap<>();
        List<String> bad = new ArrayList<>();
        List<JsonNode> cronJobs = Manifests.ofKind(Manifests.docs(overlay), "CronJob").toList();
        // JobKind 밖 CronJob은 닫힌 목록 하나(정기 백업 — 작업 실행기를 지나지 않는 DB 밖 저장소 작업). 같은 스케줄 규칙을 지킨다.
        List<JsonNode> others = cronJobs.stream().filter(c -> !c.path("metadata").path("labels").path("app.kubernetes.io/name").asString("").equals("ga-job")).toList();
        assertThat(others).extracting(Manifests::name).containsExactly("ga-backup");
        others.forEach(c -> schedule(c.path("metadata").path("name").asString(), c, bad));
        cronJobs.stream().filter(c -> !others.contains(c)).forEach(c -> {
            String kind = c.path("metadata").path("labels").path("ga/job-kind").asString("");
            if (byKind.put(kind, c) != null) {
                bad.add(kind + ": two CronJobs");
            }
        });
        assertThat(byKind.keySet()).isEqualTo(new TreeSet<>(Arrays.stream(JobKind.values()).map(Enum::name).toList()));
        byKind.forEach((kind, c) -> {
            schedule(kind, c, bad);
            JsonNode job = c.path("spec").path("jobTemplate").path("spec");
            List<String> args = Manifests.strings(job.path("template").path("spec").path("containers").get(0).path("args"));
            List<String> command = args.stream().filter(a -> !a.startsWith("--spring.profiles.active=")).toList();
            List<String> expected = DEDICATED_COMMANDS.getOrDefault(JobKind.valueOf(kind), List.of("jobs", "run", kind));
            if (!args.getFirst().startsWith("--spring.profiles.active=cli,") || command.size() < expected.size()
                    || !command.subList(0, expected.size()).equals(expected) || !command.contains("--operator")) {
                bad.add(kind + ": args " + args);
            }
        });
        assertThat(bad).isEmpty();
    }

    /** 모든 CronJob 공통: Asia/Seoul·Forbid·시작 기한·재시도 상한·실행 기한. */
    static void schedule(String name, JsonNode c, List<String> bad) {
        JsonNode spec = c.path("spec");
        JsonNode job = spec.path("jobTemplate").path("spec");
        if (!spec.path("timeZone").asString("").equals("Asia/Seoul")) {
            bad.add(name + ": timeZone " + spec.path("timeZone"));
        }
        if (!spec.path("concurrencyPolicy").asString("").equals("Forbid")) {
            bad.add(name + ": concurrencyPolicy " + spec.path("concurrencyPolicy"));
        }
        if (spec.path("startingDeadlineSeconds").asInt(0) <= 0) {
            bad.add(name + ": startingDeadlineSeconds");
        }
        if (!job.has("backoffLimit") || job.path("backoffLimit").asInt() > 2) {
            bad.add(name + ": backoffLimit");
        }
        if (job.path("activeDeadlineSeconds").asInt(0) <= 0) {
            bad.add(name + ": activeDeadlineSeconds");
        }
    }
}
