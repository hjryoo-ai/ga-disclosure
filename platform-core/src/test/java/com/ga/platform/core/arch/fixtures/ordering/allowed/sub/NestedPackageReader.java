package com.ga.platform.core.arch.fixtures.ordering.allowed.sub;

import com.ga.platform.core.arch.fixtures.ordering.OrderingFixtures.Label;

/** 허용 패키지의 하위 패키지는 허용 범위가 아니다(패키지 허용은 정확한 FQN). */
public final class NestedPackageReader {

    public String read(Label label) {
        return label.value();
    }
}
