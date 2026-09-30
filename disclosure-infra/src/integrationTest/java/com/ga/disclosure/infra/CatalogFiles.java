package com.ga.disclosure.infra;

import java.util.List;
import java.util.stream.Collectors;

/** 카탈로그 수입 파일(JSON) 조립 도우미. 상품군 코드는 부록 B 규격의 가상 코드({@code PG-…})다. */
final class CatalogFiles {

    private CatalogFiles() {
    }

    static String groups(String asOf, String... groups) {
        return file("PRODUCT_GROUPS", asOf, "groups", List.of(groups));
    }

    static String group(String code, String from, String to) {
        return "{\"groupCode\":\"" + code + "\",\"name\":\"(가상) " + code + "\",\"line\":\"LIFE\",\"applyFrom\":\"" + from + "\",\"applyTo\":"
                + date(to) + "}";
    }

    static String products(String asOf, String... products) {
        return file("PRODUCTS", asOf, "products", List.of(products));
    }

    static String product(String key, String group, String name, String from, String to) {
        String insurer = key.substring(0, key.indexOf(':'));
        return "{\"productKey\":\"" + key + "\",\"insurerCode\":\"" + insurer + "\",\"groupCode\":\"" + group + "\",\"productName\":\"" + name
                + "\",\"saleFrom\":\"" + from + "\",\"saleTo\":" + date(to) + ",\"defaults\":{\"PREMIUM_EXAMPLE_WON\":32100}}";
    }

    static String panel(String asOf, String... insurers) {
        return file("INSURER_PANEL", asOf, "insurers", List.of(insurers));
    }

    static String insurer(String code, String from, String to) {
        return "{\"insurerCode\":\"" + code + "\",\"insurerName\":\"(가상) " + code + "\",\"line\":\"LIFE\",\"activeFrom\":\"" + from
                + "\",\"activeTo\":" + date(to) + "}";
    }

    private static String file(String kind, String asOf, String array, List<String> rows) {
        return "{\"schemaVersion\":1,\"kind\":\"" + kind + "\",\"source\":\"TEST_FILE\",\"asOf\":\"" + asOf + "\",\"" + array + "\":["
                + rows.stream().collect(Collectors.joining(",")) + "]}";
    }

    private static String date(String d) {
        return d == null ? "null" : "\"" + d + "\"";
    }
}
