package com.ga.platform.core.arch.fixtures.ordering.allowed;

import com.ga.platform.core.arch.fixtures.ordering.OrderingFixtures.Label;

/** 허용 패키지에서의 {@code Label.value()} 호출 표본. */
public final class LabelRenderer {

    public String render(Label label) {
        return "[" + label.value() + "]";
    }
}
