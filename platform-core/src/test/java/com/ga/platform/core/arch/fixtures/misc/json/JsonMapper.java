package com.ga.platform.core.arch.fixtures.misc.json;

import java.math.BigDecimal;

/** 허용 패키지(..json..)에서의 BigDecimal 사용 표본. */
public final class JsonMapper {

    public String map(BigDecimal value) {
        return value.toPlainString();
    }
}
