package com.ga.platform.core.arch.fixtures.layers.high;

import com.ga.platform.core.arch.fixtures.layers.low.Low;

/** 상위 레이어 표본(하위 접근은 허용). */
public final class High {

    public int value(Low low) {
        return low.value() + 1;
    }
}
