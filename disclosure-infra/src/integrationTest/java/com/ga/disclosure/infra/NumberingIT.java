package com.ga.disclosure.infra;

import com.ga.disclosure.domain.vo.DisclosureId;
import com.ga.disclosure.domain.vo.DisclosureNo;
import com.ga.disclosure.infra.engine.EngineClientSettings;
import com.ga.disclosure.workflow.disclosure.SealService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3B S5: 같은 테넌트 동시 봉인 50건 → 번호 {1..50} 정확히(중복 0, 빈 번호 0), 체인 순번 {1..50}. 거부를 일으키는 20건을 섞어도 번호는 {1..50}
 * (거부는 채번 전에 끝나 번호를 쓰지 않는다). 다른 테넌트를 병행해도 각자 1부터. 연말 경계(승인 Q7): 12-31 23:59 KST 봉인과 01-01 00:00 KST 봉인을
 * 동시에 → 연도별 카운터가 각각 1, 체인은 갭·중복 없이 1·2, 교착 없음.
 */
class NumberingIT {

    private static final int SEALS = 50;
    private static final int REJECTED = 20;

    private static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<T> out = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
        }
        return out;
    }

    private static List<Integer> sequences(List<SealService.Outcome> outcomes) {
        return outcomes.stream().filter(SealService.Outcome::sealed).map(o -> o.number().orElseThrow().sequence()).sorted().toList();
    }

    @Test
    void fiftyConcurrentSealsWithTwentyRejectionsMixedInAndAnotherTenantInParallel() throws Exception {
        try (SealSetup a = new SealSetup(); SealSetup b = new SealSetup()) {
            List<DisclosureId> good = IntStream.range(0, SEALS).mapToObj(i -> a.w.reasoned()).toList();
            List<DisclosureId> bad = IntStream.range(0, REJECTED).mapToObj(i -> a.w.reasoned()).toList();
            bad.forEach(id -> SealScenarios.dropRecommendation(a.w, id));          // 거부 유도(VALIDATION_BLOCKED)
            List<DisclosureId> other = IntStream.range(0, 10).mapToObj(i -> b.w.reasoned()).toList();

            List<Callable<SealService.Outcome>> tasks = new ArrayList<>();
            good.forEach(id -> tasks.add(() -> a.seal.seal(a.w.tenant, WorkflowSetup.AGENT, id)));
            bad.forEach(id -> tasks.add(() -> a.seal.seal(a.w.tenant, WorkflowSetup.AGENT, id)));
            other.forEach(id -> tasks.add(() -> b.seal.seal(b.w.tenant, WorkflowSetup.AGENT, id)));
            java.util.Collections.shuffle(tasks, new java.util.Random(20261001L));
            List<SealService.Outcome> outcomes = concurrently(tasks);

            List<SealService.Outcome> ofA = outcomes.stream().filter(o -> good.contains(o.id()) || bad.contains(o.id())).toList();
            List<SealService.Outcome> ofB = outcomes.stream().filter(o -> other.contains(o.id())).toList();
            assertThat(ofA.stream().filter(SealService.Outcome::sealed).map(SealService.Outcome::id)).containsExactlyInAnyOrderElementsOf(good);
            assertThat(sequences(ofA)).containsExactlyElementsOf(IntStream.rangeClosed(1, SEALS).boxed().toList());
            assertThat(sequences(ofB)).containsExactlyElementsOf(IntStream.rangeClosed(1, 10).boxed().toList());
            assertThat(ofA.stream().filter(o -> !o.sealed()).count()).isEqualTo(REJECTED);
            assertThat(a.count("SELECT seq FROM disclosure_counter WHERE tenant_id = ? AND year = 2026", a.w.tenant.value())).isEqualTo(SEALS);
            assertThat(a.count("SELECT count(DISTINCT chain_seq) FROM disclosure WHERE tenant_id = ? AND chain_seq BETWEEN 1 AND ?",
                    a.w.tenant.value(), (long) SEALS)).isEqualTo(SEALS);
            assertThat(a.count("SELECT chain_seq FROM disclosure_chain_head WHERE tenant_id = ?", a.w.tenant.value())).isEqualTo(SEALS);
        }
    }

    @Test
    void yearBoundarySealsTakeTheirOwnYearCountersAndOneChain() throws Exception {
        // 엔진 스냅샷이 노후하지 않도록 산출도 연말에 한다(시계 2026-12-31 23:50 KST)
        WorkflowSetup w = new WorkflowSetup("2026-12-31T14:50:00Z", new EngineClientSettings(Duration.ofSeconds(2), Duration.ofSeconds(3), 3));
        try (SealSetup s = new SealSetup(w)) {
            DisclosureId eve = w.reasoned();
            DisclosureId newYear = w.reasoned();
            SealService beforeMidnight = s.sealAt(Clock.fixed(Instant.parse("2026-12-31T14:59:00Z"), SealService.SEOUL), s.bucket, s.records);
            SealService afterMidnight = s.sealAt(Clock.fixed(Instant.parse("2026-12-31T15:00:00Z"), SealService.SEOUL), s.bucket, s.records);
            List<SealService.Outcome> outcomes = concurrently(List.of(
                    () -> beforeMidnight.seal(w.tenant, WorkflowSetup.AGENT, eve),
                    () -> afterMidnight.seal(w.tenant, WorkflowSetup.AGENT, newYear)));
            assertThat(outcomes).allMatch(SealService.Outcome::sealed);
            DisclosureNo first = outcomes.get(0).number().orElseThrow();
            DisclosureNo second = outcomes.get(1).number().orElseThrow();
            assertThat(first.value()).isEqualTo(w.tenant.value() + "-2026-000001");
            assertThat(second.value()).isEqualTo(w.tenant.value() + "-2027-000001");
            assertThat(s.count("SELECT count(*) FROM disclosure_counter WHERE tenant_id = ? AND seq = 1", w.tenant.value())).isEqualTo(2);
            assertThat(s.count("SELECT count(DISTINCT chain_seq) FROM disclosure WHERE tenant_id = ? AND chain_seq IN (1, 2)", w.tenant.value()))
                    .isEqualTo(2);
            assertThat(s.count("SELECT chain_seq FROM disclosure_chain_head WHERE tenant_id = ?", w.tenant.value())).isEqualTo(2);
        }
    }
}
