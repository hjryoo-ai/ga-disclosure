package com.ga.platform.core.arch.fixtures.layers.low;

import com.ga.platform.core.arch.fixtures.layers.high.High;

/** 하위 레이어가 상위 레이어를 참조하는 위반 표본. */
public final class UpwardAccess {

    public int value(High high, Low low) {
        return high.value(low);
    }
}
