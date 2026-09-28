package com.ga.platform.spring.jdbc;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DB 없이 "DB에 도달했는가"를 관찰하기 위한 기록용 JDBC 가짜. JDK 동적 프록시만 쓴다.
 * 모든 조회는 빈 결과를 돌려준다.
 */
final class FakeJdbc {

    /** 커넥션에서 일어난 일(순서대로). 예: {@code getConnection}, {@code prepare:SELECT ...}, {@code param:1=TA}, {@code commit}. */
    final List<String> events = Collections.synchronizedList(new ArrayList<>());

    final DataSource dataSource = proxy(DataSource.class, (p, m, args) -> switch (m.getName()) {
        case "getConnection" -> {
            events.add("getConnection");
            yield connection();
        }
        case "isWrapperFor" -> false;
        case "toString" -> "FakeDataSource";
        case "hashCode" -> System.identityHashCode(p);
        case "equals" -> p == args[0];
        default -> defaultValue(m.getReturnType());
    });

    long connectionsObtained() {
        return events.stream().filter("getConnection"::equals).count();
    }

    List<String> statements() {
        return events.stream().filter(e -> e.startsWith("prepare:")).map(e -> e.substring("prepare:".length())).toList();
    }

    private Connection connection() {
        boolean[] autoCommit = {true};
        return proxy(Connection.class, (p, m, args) -> switch (m.getName()) {
            case "prepareStatement" -> {
                events.add("prepare:" + args[0]);
                yield statement();
            }
            case "getAutoCommit" -> autoCommit[0];
            case "setAutoCommit" -> {
                autoCommit[0] = (Boolean) args[0];
                yield null;
            }
            case "commit", "rollback", "close" -> {
                events.add(m.getName());
                yield null;
            }
            case "isClosed", "isReadOnly" -> false;
            case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
            case "toString" -> "FakeConnection";
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == args[0];
            default -> defaultValue(m.getReturnType());
        });
    }

    private PreparedStatement statement() {
        return proxy(PreparedStatement.class, (p, m, args) -> switch (m.getName()) {
            case "setString", "setObject" -> {
                events.add("param:" + args[0] + "=" + args[1]);
                yield null;
            }
            case "execute" -> true;
            case "executeUpdate" -> 1;
            case "executeQuery" -> emptyResultSet();
            case "toString" -> "FakePreparedStatement";
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == args[0];
            default -> defaultValue(m.getReturnType());
        });
    }

    private ResultSet emptyResultSet() {
        return proxy(ResultSet.class, (p, m, args) -> switch (m.getName()) {
            case "next" -> false;
            case "toString" -> "FakeResultSet";
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == args[0];
            default -> defaultValue(m.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(FakeJdbc.class.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        return 0; // int (부동소수 반환 메서드는 호출되지 않는다)
    }
}
