package com.ga.platform.core.arch.fixtures.misc;

import java.math.BigDecimal;
import java.sql.Connection;

/** ArchRulesTest 전용 표본: 이름 금지, BigDecimal 범위, Spring·DB 무의존. */
public final class MiscFixtures {

    private MiscFixtures() {
    }

    public static final class CommissionRateCalculator {
    }

    public static final class RankingHelper {
    }

    public static final class UsesBigDecimal {
        String scale(String raw) {
            return new BigDecimal(raw).toPlainString();
        }
    }

    public static final class UsesJdbc {
        boolean open(Connection connection) throws java.sql.SQLException {
            return !connection.isClosed();
        }
    }
}
