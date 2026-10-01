package com.ga.disclosure.seal.renderer;

import com.ga.disclosure.seal.canonical.CanonicalDocument;
import com.ga.disclosure.seal.canonical.SealFixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3B 지시문 §4: 렌더 시간 측정(p95 목표 3초). 워밍업 5회 뒤 순차 50회와 동시 10건의 p50·p95를 표준 출력에 남긴다(보고서 재료). 러너 편차가 있어
 * 목표치로 실패시키지 않는다 — 판정은 보고서에서 한다. 동시 렌더 결과가 순차 결과와 바이트가 같은지(공유 상태 없음)는 단언한다.
 */
class RenderTimingTest {

    private static final DisclosurePdfRenderer RENDERER = new DisclosurePdfRenderer();

    @Test
    void measureSequentialAndConcurrent() throws Exception {
        CanonicalDocument doc = RenderFixtures.canonical(SealFixtures.case02());
        String no = RenderFixtures.number(7);
        byte[] reference = RENDERER.render(doc, RenderFixtures.STANDARD, no).pdf();
        for (int i = 0; i < 5; i++) {
            RENDERER.render(doc, RenderFixtures.STANDARD, no);
        }
        List<Long> sequential = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            long t0 = System.nanoTime();
            RENDERER.render(doc, RenderFixtures.STANDARD, no);
            sequential.add((System.nanoTime() - t0) / 1_000_000);
        }
        List<Long> concurrent = new ArrayList<>();
        List<byte[]> outputs = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            List<Future<long[]>> futures = new ArrayList<>();
            List<Future<byte[]>> bytes = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                java.util.concurrent.CompletableFuture<byte[]> out = new java.util.concurrent.CompletableFuture<>();
                futures.add(pool.submit(() -> {
                    long t0 = System.nanoTime();
                    out.complete(RENDERER.render(doc, RenderFixtures.STANDARD, no).pdf());
                    return new long[] {(System.nanoTime() - t0) / 1_000_000};
                }));
                bytes.add(out);
            }
            for (Future<long[]> f : futures) {
                concurrent.add(f.get()[0]);
            }
            for (Future<byte[]> b : bytes) {
                outputs.add(b.get());
            }
        }
        System.out.println("RENDER_TIMING sequential50 p50=" + percentile(sequential, 50) + "ms p95=" + percentile(sequential, 95)
                + "ms max=" + percentile(sequential, 100) + "ms | concurrent10 p50=" + percentile(concurrent, 50) + "ms p95="
                + percentile(concurrent, 95) + "ms max=" + percentile(concurrent, 100) + "ms | cores="
                + Runtime.getRuntime().availableProcessors());
        assertThat(outputs).allSatisfy(b -> assertThat(b).isEqualTo(reference));
    }

    /** 최근접 순위 백분위(정수 ms). */
    static long percentile(List<Long> values, int p) {
        List<Long> sorted = values.stream().sorted().toList();
        int rank = Math.max(1, (p * sorted.size() + 99) / 100);     // 정수 올림(부동소수 없음)
        return sorted.get(rank - 1);
    }
}
