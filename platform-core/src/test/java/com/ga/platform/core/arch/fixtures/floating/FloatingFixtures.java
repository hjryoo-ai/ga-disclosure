package com.ga.platform.core.arch.fixtures.floating;

/** ArchRulesTest 전용 위반 표본. 운영 코드가 아니다. */
public final class FloatingFixtures {

    private FloatingFixtures() {
    }

    public static final class DoubleField {
        double value;
    }

    public static final class FloatParameter {
        long accept(float f) {
            return (long) f;
        }
    }

    public static final class BoxedReturn {
        Double produce() {
            return null;
        }
    }

    /** 지역 변수로만 쓰는 우회 — 호출 대상(Double.parseDouble)으로 잡힌다. */
    public static final class ParsesRatio {
        long parse(String ratio) {
            return (long) (Double.parseDouble(ratio) * 100);
        }
    }

    public static final class CallsDoubleReturningMethod {
        long random() {
            return (long) Math.random();
        }
    }

    public static final class Clean {
        long add(long a, long b) {
            return Math.addExact(a, b);
        }
    }
}
