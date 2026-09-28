package com.ga.platform.core.testing;

import org.junit.jupiter.params.provider.Arguments;

import java.util.Locale;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * 시드 고정 속성 테스트 케이스 생성기 (JUnit 6 {@code @ParameterizedTest} + {@code @MethodSource}용).
 *
 * <p>jqwik을 대체한다(CLAUDE.md 절대 규칙 9). 규약:
 * <ul>
 *   <li>시드는 테스트 소스에 상수로 적는다. 같은 시드 → 같은 케이스 순서·값({@value #ALGORITHM}).</li>
 *   <li>각 케이스는 {@link Arguments#argumentSet(String, Object...)}로 만들어 표시 이름이 {@code seed=0x…, case=N}이 된다.
 *       실패 보고에 시드와 인덱스가 그대로 찍혀 재현이 가능하다.</li>
 *   <li>로컬 탐색용으로 환경변수 {@value #SEED_OVERRIDE_ENV}(10진 또는 {@code 0x} 16진)로 시드를 덮어쓸 수 있다.
 *       단 {@code CI=true} 환경에서는 덮어쓰기를 무시하고 소스 상수만 쓴다.</li>
 * </ul>
 *
 * <pre>{@code
 * private static final long SEED = 0x5EED_0001L;
 * static Stream<Arguments> sums() {
 *     return SeededCases.of(SEED, r -> new Object[] {r.nextLong(-1_000, 1_000), r.nextLong(-1_000, 1_000)});
 * }
 * @ParameterizedTest @MethodSource("sums")
 * void commutative(long a, long b) { ... }
 * }</pre>
 */
public final class SeededCases {

    public static final int DEFAULT_COUNT = 500;
    public static final String ALGORITHM = "L64X128MixRandom";
    public static final String SEED_OVERRIDE_ENV = "GA_SEEDED_CASES_SEED";

    private SeededCases() {
    }

    public static Stream<Arguments> of(long seed, Function<RandomGenerator, Object[]> generator) {
        return of(seed, DEFAULT_COUNT, generator);
    }

    public static Stream<Arguments> of(long seed, int count, Function<RandomGenerator, Object[]> generator) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        long effective = effectiveSeed(seed);
        RandomGenerator random = generator(effective);
        String prefix = "seed=0x" + Long.toHexString(effective).toUpperCase(Locale.ROOT) + ", case=";
        // 순차 생성: 케이스 i의 값은 시드와 i에 의해 결정된다(앞선 케이스를 같은 순서로 생성하므로).
        return IntStream.range(0, count)
                .<Arguments>mapToObj(i -> Arguments.argumentSet(prefix + i, generator.apply(random)))
                .toList()
                .stream();
    }

    /** 소스 상수 시드에 환경변수 덮어쓰기 규칙을 적용한 실제 시드. */
    public static long effectiveSeed(long sourceSeed) {
        if ("true".equalsIgnoreCase(System.getenv("CI"))) {
            return sourceSeed;
        }
        String override = System.getenv(SEED_OVERRIDE_ENV);
        if (override == null || override.isBlank()) {
            return sourceSeed;
        }
        String s = override.strip();
        return s.startsWith("0x") || s.startsWith("0X") ? Long.parseUnsignedLong(s.substring(2), 16) : Long.parseLong(s);
    }

    public static RandomGenerator generator(long seed) {
        return RandomGeneratorFactory.of(ALGORITHM).create(seed);
    }
}
